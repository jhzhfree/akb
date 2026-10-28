package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ExternalIdentityLink;
import com.jh.knowledgeos.domain.ExternalIdentityBinding;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Validates delegated OIDC identity and manages explicit issuer/subject bindings. */
@Service
public class DelegatedIdentityService {

    private static final java.util.concurrent.atomic.AtomicLong LAST_JTI_CLEANUP = new java.util.concurrent.atomic.AtomicLong();

    private final ExternalApiMapper mapper;
    private final KnowledgeProperties.External.DelegatedIdentity properties;
    private final JwtDecoder decoder;
    private final KnowledgeProperties.External.OctopIdentity octopProperties;
    private final JwtDecoder octopDecoder;

    public DelegatedIdentityService(ExternalApiMapper mapper, KnowledgeProperties knowledgeProperties) {
        this.mapper = mapper;
        this.properties = knowledgeProperties.external().getDelegatedIdentity();
        this.octopProperties = knowledgeProperties.external().getOctopIdentity();
        if (!properties.isEnabled()) {
            this.decoder = null;
        } else {
            if (blank(properties.getIssuer()) || blank(properties.getJwkSetUri()) || blank(properties.getAudience())) {
                throw new IllegalStateException("启用 delegated-identity 时必须配置 issuer、jwk-set-uri 和 audience");
            }
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
            OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
            OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
                boolean hasAudience = jwt.getAudience() != null && jwt.getAudience().contains(properties.getAudience());
                boolean hasExp = jwt.getExpiresAt() != null;
                boolean hasNbf = jwt.getNotBefore() != null;
                boolean hasSubject = !blank(jwt.getSubject());
                if (hasAudience && hasExp && hasNbf && hasSubject) return OAuth2TokenValidatorResult.success();
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "JWT must contain the configured audience, exp, nbf and sub claims", null));
            };
            nimbus.setJwtValidator(jwt -> {
                OAuth2TokenValidatorResult standardResult = standard.validate(jwt);
                return standardResult.hasErrors() ? standardResult : requiredClaims.validate(jwt);
            });
            this.decoder = nimbus;
        }
        if (!octopProperties.isEnabled()) {
            this.octopDecoder = null;
        } else {
            if (blank(octopProperties.getIssuer()) || blank(octopProperties.getJwkSetUri())
                || blank(octopProperties.getAudience()) || blank(octopProperties.getInstanceId())) {
                throw new IllegalStateException("启用 Octop identity 时必须配置 issuer、jwk-set-uri、audience 和 instance-id");
            }
            NimbusJwtDecoder octopNimbus = NimbusJwtDecoder.withJwkSetUri(octopProperties.getJwkSetUri()).build();
            OAuth2TokenValidator<Jwt> standard = JwtValidators.createDefaultWithIssuer(octopProperties.getIssuer());
            OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
                boolean hasAudience = jwt.getAudience() != null && jwt.getAudience().contains(octopProperties.getAudience());
                boolean hasIssuedAt = jwt.getIssuedAt() != null;
                boolean hasJti = !blank(jwt.getId());
                if (hasAudience && hasIssuedAt && hasJti && !blank(jwt.getSubject())) return OAuth2TokenValidatorResult.success();
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Octop assertion requires aud, iat, jti and sub", null));
            };
            octopNimbus.setJwtValidator(jwt -> {
                OAuth2TokenValidatorResult standardResult = standard.validate(jwt);
                return standardResult.hasErrors() ? standardResult : requiredClaims.validate(jwt);
            });
            this.octopDecoder = octopNimbus;
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public boolean isOctopEnabled() { return octopProperties.isEnabled(); }

    /** Verifies a short-lived Octop assertion and resolves the user through an AKB-admin binding. */
    public Long authenticateOctopAssertion(String assertion, ApiKey agentKey) {
        if (!octopProperties.isEnabled() || octopDecoder == null) throw new UnauthorizedException("Octop 身份断言验证未启用");
        if (agentKey == null || agentKey.getPrincipalId() == null || !"AGENT".equalsIgnoreCase(agentKey.getPrincipalType())) {
            throw new UnauthorizedException("Octop 委托身份必须使用绑定到 AGENT 主体的 Service Key");
        }
        if (blank(assertion)) throw new UnauthorizedException("缺少 X-Octop-Identity 身份断言");
        try {
            Jwt jwt = octopDecoder.decode(assertion.trim());
            String instanceId = jwt.getClaimAsString("octop_instance_id");
            String agentId = jwt.getClaimAsString("agent_id");
            String jti = jwt.getId();
            if (!octopProperties.getInstanceId().equals(instanceId)) throw new UnauthorizedException("Octop 实例不匹配");
            if (blank(agentId)) throw new UnauthorizedException("身份断言缺少 agent_id");
            if (jwt.getExpiresAt() == null || jwt.getIssuedAt() == null) throw new UnauthorizedException("身份断言缺少有效期");
            long lifetime = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds();
            if (lifetime <= 0 || lifetime > 300 || jwt.getIssuedAt().isAfter(java.time.Instant.now().plusSeconds(30))) {
                throw new UnauthorizedException("Octop 身份断言有效期必须在 5 分钟内");
            }
            ExternalPrincipal agent = mapper.getPrincipal(agentKey.getPrincipalId());
            if (agent == null || !"ACTIVE".equalsIgnoreCase(agent.getStatus())
                || !instanceId.equals(agent.getExternalInstanceId()) || !agentId.equals(agent.getExternalSubjectId())) {
                throw new UnauthorizedException("Agent Service Key 与身份断言不匹配");
            }
            long nowMillis = System.currentTimeMillis();
            long previousCleanup = LAST_JTI_CLEANUP.get();
            if (nowMillis - previousCleanup > 60_000L && LAST_JTI_CLEANUP.compareAndSet(previousCleanup, nowMillis)) {
                mapper.deleteExpiredOctopAssertionJtis();
            }
            long remainingSeconds = Math.max(1L, Duration.between(java.time.Instant.now(), jwt.getExpiresAt()).getSeconds());
            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(remainingSeconds);
            try {
                if (mapper.insertOctopAssertionJti(jti, expiresAt) != 1) throw new UnauthorizedException("Octop 身份断言已使用");
            } catch (DuplicateKeyException ex) {
                throw new UnauthorizedException("Octop 身份断言已使用");
            }
            ExternalPrincipal user = mapper.findActiveExternalUserBinding("OCTOP", instanceId, jwt.getSubject());
            if (user == null) throw new UnauthorizedException("Octop 用户 ID 未绑定到有效的 AKB USER 主体");
            return user.getId();
        } catch (JwtException ex) {
            throw new UnauthorizedException("Octop 身份断言无效或已过期");
        }
    }

    @Transactional
    public Map<String, Object> bindOctopUser(String instanceId, String externalUserId, Long principalId, Long operatorId, String reason) {
        ExternalPrincipal principal = mapper.getPrincipal(principalId);
        if (principal == null || !"ACTIVE".equalsIgnoreCase(principal.getStatus()) || !"USER".equalsIgnoreCase(principal.getPrincipalType())) {
            throw new IllegalArgumentException("Octop 用户只能绑定到 ACTIVE USER 主体");
        }
        String cleanInstance = required(instanceId, "providerInstanceId", 128);
        String cleanExternalUser = required(externalUserId, "externalUserId", 255);
        String cleanReason = normalizeReason(reason);
        if (octopProperties.isEnabled() && !octopProperties.getInstanceId().equals(cleanInstance)) {
            throw new IllegalArgumentException("providerInstanceId 与 AKB 当前受信任的 Octop 实例不匹配");
        }
        Long reactivatedId = null;
        for (Map<String, Object> existing : mapper.listExternalIdentityBindings("OCTOP", cleanInstance, cleanExternalUser)) {
            Object existingPrincipal = existing.get("principalId");
            if ("ACTIVE".equalsIgnoreCase(String.valueOf(existing.get("status")))) {
                if (existingPrincipal != null && Long.valueOf(String.valueOf(existingPrincipal)).equals(principalId)) return existing;
                throw new IllegalArgumentException("该 Octop 用户 ID 已绑定到其他 AKB 主体；请先吊销旧绑定");
            }
            if ("REVOKED".equalsIgnoreCase(String.valueOf(existing.get("status")))) reactivatedId = longValue(existing.get("id"));
        }
        ExternalIdentityBinding binding = new ExternalIdentityBinding();
        binding.setProvider("OCTOP");
        binding.setProviderInstanceId(cleanInstance);
        binding.setExternalUserId(cleanExternalUser);
        binding.setPrincipalId(principalId);
        binding.setOperatorId(operatorId);
        binding.setReason(cleanReason);
        if (reactivatedId != null) {
            if (mapper.reactivateExternalIdentityBinding(reactivatedId, principalId, operatorId, cleanReason) != 1) {
                throw new IllegalArgumentException("Octop 身份绑定状态已被其他操作修改");
            }
            binding.setId(reactivatedId);
        } else {
            mapper.insertExternalIdentityBinding(binding);
        }
        mapper.insertExternalIdentityBindingAudit(binding.getId(), binding.getProvider(), binding.getProviderInstanceId(),
            binding.getExternalUserId(), binding.getPrincipalId(), "BIND", operatorId, cleanReason);
        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("id", binding.getId());
        result.put("provider", binding.getProvider());
        result.put("providerInstanceId", binding.getProviderInstanceId());
        result.put("externalUserId", binding.getExternalUserId());
        result.put("principalId", principalId);
        result.put("principalCode", principal.getPrincipalCode());
        result.put("status", "ACTIVE");
        return result;
    }

    @Transactional
    public void revokeOctopUserBinding(Long bindingId, Long operatorId, String reason) {
        Map<String, Object> binding = null;
        for (Map<String, Object> row : mapper.listExternalIdentityBindings("OCTOP", null, null)) {
            if (bindingId.equals(longValue(row.get("id")))) { binding = row; break; }
        }
        if (binding == null || !"ACTIVE".equalsIgnoreCase(String.valueOf(binding.get("status")))) {
            throw new IllegalArgumentException("Octop 身份绑定不存在或已撤销");
        }
        String cleanReason = normalizeReason(reason);
        if (mapper.revokeExternalIdentityBinding(bindingId, operatorId, cleanReason) != 1) {
            throw new IllegalArgumentException("Octop 身份绑定已被其他操作修改");
        }
        mapper.insertExternalIdentityBindingAudit(bindingId, "OCTOP", String.valueOf(binding.get("providerInstanceId")),
            String.valueOf(binding.get("externalUserId")), longValue(binding.get("principalId")), "REVOKE", operatorId, cleanReason);
    }

    public List<Map<String, Object>> listOctopBindings(String instanceId, String externalUserId) {
        return mapper.listExternalIdentityBindings("OCTOP", instanceId, externalUserId);
    }

    /** Returns the mapped active USER principal; an untrusted user-id header is never consulted. */
    public Long authenticateBearer(String authorization) {
        if (!properties.isEnabled()) {
            return null;
        }
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("启用委托身份时必须提供 Authorization: Bearer <OIDC JWT>");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedException("OIDC Bearer Token 不能为空");
        }
        try {
            Jwt jwt = decoder.decode(token);
            String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
            ExternalPrincipal principal = mapper.findActiveUserPrincipalByIdentity(issuer, jwt.getSubject());
            if (principal == null) {
                throw new UnauthorizedException("OIDC 身份未绑定到有效的 AKB USER 主体");
            }
            return principal.getId();
        } catch (JwtException ex) {
            throw new UnauthorizedException("OIDC Bearer Token 无效或已过期");
        }
    }

    public ExternalPrincipal createUserPrincipal(String code, String name, Long ownerUserId, Long operatorId, IdGenerator ids) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 128) {
            throw new IllegalArgumentException("USER 主体名称必须为 1 到 128 个字符");
        }
        if (ownerUserId != null) {
            ExternalPrincipal existing = mapper.getUserPrincipal(ownerUserId);
            if (existing != null) return existing;
        }
        ExternalPrincipal principal = new ExternalPrincipal();
        principal.setPrincipalCode(code == null || code.trim().isEmpty() ? ids.next("USR_") : code.trim());
        if (principal.getPrincipalCode().length() > 128) {
            throw new IllegalArgumentException("principalCode 最多 128 个字符");
        }
        principal.setPrincipalType("USER");
        principal.setOwnerUserId(ownerUserId);
        principal.setName(cleanName);
        principal.setCreatedBy(operatorId);
        mapper.insertPrincipal(principal);
        return principal;
    }

    @Transactional
    public Map<String, Object> linkIdentity(Long principalId, String issuer, String subject, Long operatorId, String reason) {
        ExternalPrincipal principal = mapper.getPrincipal(principalId);
        if (principal == null || !"ACTIVE".equalsIgnoreCase(principal.getStatus()) || !"USER".equalsIgnoreCase(principal.getPrincipalType())) {
            throw new IllegalArgumentException("身份映射只能绑定到 ACTIVE USER 主体");
        }
        String cleanIssuer = issuer == null ? "" : issuer.trim();
        String cleanSubject = subject == null ? "" : subject.trim();
        if (!properties.getIssuer().equals(cleanIssuer)) {
            throw new IllegalArgumentException("issuer 必须与服务端配置的 OIDC issuer 完全一致");
        }
        if (cleanSubject.isEmpty() || cleanSubject.length() > 255) {
            throw new IllegalArgumentException("subject 必须为 1 到 255 个字符");
        }
        ExternalIdentityLink link = new ExternalIdentityLink();
        link.setPrincipalId(principalId);
        link.setIssuer(cleanIssuer);
        link.setSubject(cleanSubject);
        link.setOperatorId(operatorId);
        mapper.insertPrincipalIdentityLink(link);
        mapper.insertPrincipalIdentityAudit(link.getId(), principalId, "LINK", operatorId, normalizeReason(reason));
        return identityRow(link, "ACTIVE");
    }

    @Transactional
    public void revokeIdentity(Long id, Long operatorId, String reason) {
        Map<String, Object> match = null;
        for (Map<String, Object> row : mapper.listPrincipalIdentityLinks(null)) {
            Object value = row.get("id");
            if (value != null && Long.valueOf(String.valueOf(value)).equals(id)) {
                match = row;
                break;
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("身份映射不存在");
        }
        Long principalId = Long.valueOf(String.valueOf(match.get("principalId")));
        if (mapper.revokePrincipalIdentityLink(id, operatorId) == 0) {
            throw new IllegalArgumentException("身份映射已吊销");
        }
        mapper.insertPrincipalIdentityAudit(id, principalId, "REVOKE", operatorId, normalizeReason(reason));
    }

    public List<Map<String, Object>> listIdentityLinks(Long principalId) {
        return mapper.listPrincipalIdentityLinks(principalId);
    }

    private Map<String, Object> identityRow(ExternalIdentityLink link, String status) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("id", link.getId());
        row.put("principalId", link.getPrincipalId());
        row.put("issuer", link.getIssuer());
        row.put("subject", link.getSubject());
        row.put("status", status);
        return row;
    }

    private static String normalizeReason(String reason) {
        String clean = reason == null ? "" : reason.trim();
        if (clean.length() > 500) {
            throw new IllegalArgumentException("原因最多 500 个字符");
        }
        return clean.isEmpty() ? "控制台身份映射变更" : clean;
    }

    private static String required(String value, String field, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > max) throw new IllegalArgumentException(field + " 长度必须为 1 到 " + max + " 个字符");
        return clean;
    }

    private static Long longValue(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { return null; }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
