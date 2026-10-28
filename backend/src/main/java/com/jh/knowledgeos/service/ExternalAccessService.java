package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Gatekeeper for the external interface-service layer.
 *
 * <p>RESTful, MCP and SSE entry points share exactly this rule set: a valid API key, an
 * unexceeded quota, the granted scope, and a knowledge base inside the key's authorised
 * range. Keeping it in one place is what makes "one contract, three channels" true.</p>
 */
@Service
public class ExternalAccessService {

    public static final String KEY_ATTRIBUTE = "externalApiKey";

    /**
     * The token presented by an embedded page, kept on the request so the audit row can name the
     * exact token instead of only the key it came from.
     */
    public static final String EMBED_TOKEN_ATTRIBUTE = "externalEmbedToken";

    private static final String BEARER = "Bearer ";

    public static final String HEADER = "X-API-Key";

    public static final String EMBED_HEADER = "X-Embed-Token";

    public static final String OCTOP_ASSERTION_HEADER = "X-Octop-Identity";

    private final ApiKeyService apiKeyService;

    private final ExternalRateLimiter rateLimiter;

    private final KnowledgeBaseAccessService accessService;

    private final EmbedTokenService embedTokens;

    private final EmbedTokenLifecycleService tokenLifecycle;

    private final KnowledgeProperties.External properties;

    private final DelegatedIdentityService delegatedIdentityService;

    public ExternalAccessService(ApiKeyService apiKeyService, ExternalRateLimiter rateLimiter, KnowledgeBaseAccessService accessService,
        EmbedTokenService embedTokens, EmbedTokenLifecycleService tokenLifecycle, KnowledgeProperties properties) {
        this(apiKeyService, rateLimiter, accessService, embedTokens, tokenLifecycle, properties, null);
    }

    @Autowired
    public ExternalAccessService(ApiKeyService apiKeyService, ExternalRateLimiter rateLimiter, KnowledgeBaseAccessService accessService,
        EmbedTokenService embedTokens, EmbedTokenLifecycleService tokenLifecycle, KnowledgeProperties properties,
        DelegatedIdentityService delegatedIdentityService) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.accessService = accessService;
        this.embedTokens = embedTokens;
        this.tokenLifecycle = tokenLifecycle;
        this.properties = properties.external();
        this.delegatedIdentityService = delegatedIdentityService;
    }

    /**
     * Resolves the presented credential, enforcing status, expiry and the per-minute quota.
     */
    public ApiKey authenticate(HttpServletRequest request) {
        String octopAssertion = request.getHeader(OCTOP_ASSERTION_HEADER);
        if (delegatedIdentityEnabled() || (octopAssertion != null && !octopAssertion.trim().isEmpty())) {
            String apiKeyHeader = request.getHeader(HEADER);
            if (apiKeyHeader == null || apiKeyHeader.trim().isEmpty()) {
                throw new UnauthorizedException("委托身份模式要求通过 X-API-Key 单独提供 Agent 凭证");
            }
        }
        String secret = extractSecret(request);
        if (secret == null) {
            throw new UnauthorizedException("缺少 API Key：请通过 " + HEADER + " 或 Authorization: Bearer 提供");
        }
        ApiKey key = apiKeyService.authenticate(secret);
        if (key == null) {
            throw new UnauthorizedException("API Key 无效、已吊销或已过期");
        }
        if (octopAssertion != null && !octopAssertion.trim().isEmpty()) {
            if (delegatedIdentityService == null || !delegatedIdentityService.isOctopEnabled()) {
                throw new UnauthorizedException("Octop 身份断言验证未启用");
            }
            if (request.getHeader("Authorization") != null) {
                throw new UnauthorizedException("Octop 委托请求仅接受 X-Octop-Identity，不接受通用 Bearer Token");
            }
            key.setDelegatedPrincipalId(delegatedIdentityService.authenticateOctopAssertion(octopAssertion, key));
        } else if (delegatedIdentityEnabled()) {
            if (key.getPrincipalId() == null || "USER".equalsIgnoreCase(key.getPrincipalType())) {
                throw new UnauthorizedException("API Key 必须归属 APPLICATION 或 AGENT 主体");
            }
            Long employeePrincipalId = delegatedIdentityService.authenticateBearer(request.getHeader("Authorization"));
            key.setDelegatedPrincipalId(employeePrincipalId);
        }
        rateLimiter.check(key);
        return key;
    }

    public void assertScope(ApiKey key, String scope) {
        if (!key.hasScope(scope)) {
            throw new SecurityException("该 API Key 未授权 " + scope + " 权限");
        }
    }

    public List<Long> grantedKnowledgeBases(ApiKey key) {
        List<Long> agentGrants = apiKeyService.grantedKnowledgeBases(key);
        if (key == null || key.getDelegatedPrincipalId() == null) {
            return agentGrants;
        }
        List<Long> employeeGrants = apiKeyService.grantedKnowledgeBases(key.getDelegatedPrincipalId());
        // 交集必须算在副本上：上游返回的可能是不可变集合（retainAll 直接抛异常），更要紧
        // 的是不能就地改别人的返回值——那个集合可能被缓存或共享，改了会波及调用方。
        List<Long> visible = new ArrayList<Long>(agentGrants);
        visible.retainAll(employeeGrants);
        return visible;
    }

    /**
     * Decides which knowledge base the call targets.
     *
     * <p>A principal grant is required for private workspaces and is authoritative for every
     * migrated key. A legacy key without a principal keeps its one-workspace binding; an unbound
     * key without explicit grants continues to use the public-workspace sharing rule.</p>
     */
    public Long resolveKnowledgeBase(ApiKey key, Long requested) {
        Long bound = key.getKnowledgeBaseId();
        List<Long> grants = grantedKnowledgeBases(key);
        Long kb = requested;
        if (kb == null) {
            kb = bound;
            if (kb == null && !grants.isEmpty()) {
                kb = grants.get(0);
            }
        }
        if (kb == null) {
            throw new IllegalArgumentException("缺少 knowledgeBaseId 参数");
        }
        if (key.getDelegatedPrincipalId() != null) {
            if (grants.contains(kb)) {
                return kb;
            }
            throw new SecurityException("Agent 与员工主体均须获准访问此知识库");
        }
        if (grants.contains(kb)) {
            return kb;
        }
        if (bound != null) {
            if (!bound.equals(kb) || key.getPrincipalId() != null) {
                throw new SecurityException("该 API Key 未获准访问此知识库");
            }
            return kb;
        }
        if (key.getPrincipalId() != null && !grants.isEmpty()) {
            throw new SecurityException("该 API Key 所属主体未获准访问此知识库");
        }
        accessService.assertExternalAccess(kb, false);
        return kb;
    }

    private boolean delegatedIdentityEnabled() {
        return delegatedIdentityService != null && delegatedIdentityService.isEnabled();
    }

    /**
     * Authentication for browser-embedded surfaces: an embed token when the page presents one,
     * otherwise the API key itself — and in both cases the origin has to be one the key allows.
     */
    public ApiKey authenticateEmbed(HttpServletRequest request) {
        String token = extractEmbedToken(request);
        if (token != null) {
            ApiKey key = authenticateEmbedToken(token, request);
            if (key == null) {
                throw new UnauthorizedException("嵌入令牌无效、已过期或来源不匹配");
            }
            return key;
        }
        ApiKey key = authenticate(request);
        assertOrigin(key, request);
        return key;
    }

    /**
     * Validates a self-contained embed token, or returns null when it must be refused.
     */
    public ApiKey authenticateEmbedToken(String token, HttpServletRequest request) {
        if (delegatedIdentityEnabled()) {
            throw new UnauthorizedException("委托身份模式不接受独立 Embed Token；请使用 X-API-Key 与员工 OIDC Bearer Token");
        }
        EmbedTokenService.EmbedToken parsed = embedTokens.parse(token);
        if (parsed == null) {
            return null;
        }
        ApiKey key = apiKeyService.get(parsed.getKeyId());
        if (key == null || !key.isUsable()) {
            return null;
        }
        // Published before the revocation check on purpose: a rejected call is exactly the one an
        // operator wants to see in the log, so the audit row must still name the token.
        if (request != null) {
            request.setAttribute(EMBED_TOKEN_ATTRIBUTE, parsed);
        }
        // Revocation is checked after the key: a token whose key is already dead needs no row.
        if (tokenLifecycle.isRevoked(parsed.getJti())) {
            throw new UnauthorizedException("嵌入令牌已被吊销：" + parsed.getDisplayId());
        }
        String origin = originOf(request);
        // A token minted for one site is useless from another, even with the same key.
        if (!parsed.getOrigin().isEmpty() && !origin.isEmpty() && !parsed.getOrigin().equalsIgnoreCase(origin)) {
            return null;
        }
        if (!allowsOrigin(key, origin)) {
            return null;
        }
        rateLimiter.check(key);
        return key;
    }

    /**
     * The embed token this request was authenticated with, or null when a plain API key was used.
     */
    public static EmbedTokenService.EmbedToken embedTokenOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(EMBED_TOKEN_ATTRIBUTE);
        return value instanceof EmbedTokenService.EmbedToken ? (EmbedTokenService.EmbedToken) value : null;
    }

    public static String extractEmbedToken(HttpServletRequest request) {
        String header = request.getHeader(EMBED_HEADER);
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        return request.getParameter("embedToken");
    }

    /**
     * The site a request came from: the Origin header when the browser sends one, the origin
     * part of Referer otherwise. Empty for server-side callers, which are not origin-bound.
     */
    public static String originOf(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.trim().isEmpty() && !"null".equalsIgnoreCase(origin.trim())) {
            return trimTrailingSlash(origin.trim());
        }
        String referer = request.getHeader("Referer");
        if (referer == null || referer.trim().isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(referer.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            int port = uri.getPort();
            boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return scheme + "://" + host + (port > 0 && !defaultPort ? ":" + port : "");
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    /**
     * Whether the credential may be used from this site: its own whitelist wins, then the
     * deployment-wide list. An unrestricted key accepts everything.
     */
    public boolean allowsOrigin(ApiKey key, String origin) {
        if (apiKeyService.allowsOrigin(key, origin)) {
            return true;
        }
        String global = properties.getAllowedOrigins();
        if (global == null || global.trim().isEmpty() || origin == null || origin.isEmpty()) {
            return false;
        }
        for (String allowed : global.split(",")) {
            if (allowed.trim().equalsIgnoreCase(origin.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Origin check for browser callers. Server-side calls carry no Origin at all and are left
     * alone, so existing integrations keep working.
     */
    public void assertOrigin(ApiKey key, HttpServletRequest request) {
        String origin = originOf(request);
        if (origin.isEmpty()) {
            return;
        }
        if (!allowsOrigin(key, origin)) {
            throw new SecurityException("该凭证未授权来源 " + origin);
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public static String extractSecret(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            return authorization.substring(BEARER.length()).trim();
        }
        if (authorization != null && !authorization.trim().isEmpty()) {
            return authorization.trim();
        }
        return request.getParameter("apiKey");
    }
}
