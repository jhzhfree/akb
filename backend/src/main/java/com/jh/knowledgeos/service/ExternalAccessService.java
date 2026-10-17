package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.UnauthorizedException;
import org.springframework.stereotype.Service;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;

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

    private final ApiKeyService apiKeyService;

    private final ExternalRateLimiter rateLimiter;

    private final KnowledgeBaseAccessService accessService;

    private final EmbedTokenService embedTokens;

    private final EmbedTokenLifecycleService tokenLifecycle;

    private final KnowledgeProperties.External properties;

    public ExternalAccessService(ApiKeyService apiKeyService, ExternalRateLimiter rateLimiter, KnowledgeBaseAccessService accessService,
        EmbedTokenService embedTokens, EmbedTokenLifecycleService tokenLifecycle, KnowledgeProperties properties) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.accessService = accessService;
        this.embedTokens = embedTokens;
        this.tokenLifecycle = tokenLifecycle;
        this.properties = properties.external();
    }

    /**
     * Resolves the presented credential, enforcing status, expiry and the per-minute quota.
     */
    public ApiKey authenticate(HttpServletRequest request) {
        String secret = extractSecret(request);
        if (secret == null) {
            throw new UnauthorizedException("缺少 API Key：请通过 " + HEADER + " 或 Authorization: Bearer 提供");
        }
        ApiKey key = apiKeyService.authenticate(secret);
        if (key == null) {
            throw new UnauthorizedException("API Key 无效、已吊销或已过期");
        }
        rateLimiter.check(key);
        return key;
    }

    public void assertScope(ApiKey key, String scope) {
        if (!key.hasScope(scope)) {
            throw new SecurityException("该 API Key 未授权 " + scope + " 权限");
        }
    }

    /**
     * Decides which knowledge base the call targets.
     *
     * <p>A key bound to one workspace can never reach another one, even if the caller passes a
     * different id. An unbound key must additionally satisfy the anonymous sharing rule, so it
     * only ever sees public workspaces.</p>
     */
    public Long resolveKnowledgeBase(ApiKey key, Long requested) {
        Long bound = key.getKnowledgeBaseId();
        Long kb = bound != null ? bound : requested;
        if (kb == null) {
            throw new IllegalArgumentException("缺少 knowledgeBaseId 参数");
        }
        if (bound != null && requested != null && !bound.equals(requested)) {
            throw new SecurityException("该 API Key 已绑定知识库，不能访问其他知识库");
        }
        if (bound == null) {
            accessService.assertExternalAccess(kb, false);
        }
        return kb;
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
