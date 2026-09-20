package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.UnauthorizedException;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;

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
    private static final String BEARER = "Bearer ";
    public static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ExternalRateLimiter rateLimiter;
    private final KnowledgeBaseAccessService accessService;

    public ExternalAccessService(
            ApiKeyService apiKeyService,
            ExternalRateLimiter rateLimiter,
            KnowledgeBaseAccessService accessService) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.accessService = accessService;
    }

    /** Resolves the presented credential, enforcing status, expiry and the per-minute quota. */
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

    public static String extractSecret(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && !header.trim().isEmpty()) return header.trim();
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            return authorization.substring(BEARER.length()).trim();
        }
        if (authorization != null && !authorization.trim().isEmpty()) return authorization.trim();
        return request.getParameter("apiKey");
    }
}
