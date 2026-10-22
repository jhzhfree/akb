package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ExternalIdentityLink;
import com.jh.knowledgeos.domain.ExternalPrincipal;
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
import java.util.List;
import java.util.Map;

/** Validates delegated OIDC identity and manages explicit issuer/subject bindings. */
@Service
public class DelegatedIdentityService {

    private final ExternalApiMapper mapper;
    private final KnowledgeProperties.External.DelegatedIdentity properties;
    private final JwtDecoder decoder;

    public DelegatedIdentityService(ExternalApiMapper mapper, KnowledgeProperties knowledgeProperties) {
        this.mapper = mapper;
        this.properties = knowledgeProperties.external().getDelegatedIdentity();
        if (!properties.isEnabled()) {
            this.decoder = null;
            return;
        }
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
            if (hasAudience && hasExp && hasNbf && hasSubject) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                "JWT must contain the configured audience, exp, nbf and sub claims", null));
        };
        nimbus.setJwtValidator(jwt -> {
            OAuth2TokenValidatorResult standardResult = standard.validate(jwt);
            return standardResult.hasErrors() ? standardResult : requiredClaims.validate(jwt);
        });
        this.decoder = nimbus;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
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

    public ExternalPrincipal createUserPrincipal(String code, String name, Long operatorId, IdGenerator ids) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 128) {
            throw new IllegalArgumentException("USER 主体名称必须为 1 到 128 个字符");
        }
        ExternalPrincipal principal = new ExternalPrincipal();
        principal.setPrincipalCode(code == null || code.trim().isEmpty() ? ids.next("USR_") : code.trim());
        if (principal.getPrincipalCode().length() > 128) {
            throw new IllegalArgumentException("principalCode 最多 128 个字符");
        }
        principal.setPrincipalType("USER");
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

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
