package com.jh.knowledgeos.config;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.service.ApiKeyService;
import com.jh.knowledgeos.service.EmbedTokenService;
import com.jh.knowledgeos.service.ExternalAccessService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Cross-origin rules for the external API, and in particular for the embeddable widget.
 *
 * <p>A widget is loaded by somebody else's page, so the browser must be told which origins may
 * read the response. Replying with {@code *} would hand every site the answer stream of any
 * leaked credential, so the header echoes the caller's origin only after the credential it
 * carries has been checked against its own whitelist. Pre-flight requests carry no credentials
 * and are answered optimistically; the real request that follows is where the decision is made.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class EmbedCorsFilter extends OncePerRequestFilter {

    private static final String EXTERNAL_PREFIX = "/api/v1/external/";

    private final ExternalAccessService accessService;

    private final ApiKeyService apiKeyService;

    private final EmbedTokenService embedTokens;

    public EmbedCorsFilter(ExternalAccessService accessService, ApiKeyService apiKeyService, EmbedTokenService embedTokens) {
        this.accessService = accessService;
        this.apiKeyService = apiKeyService;
        this.embedTokens = embedTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(EXTERNAL_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String origin = ExternalAccessService.originOf(request);
        if (origin.isEmpty()) {
            // Server-side caller: no browser is involved, so no CORS header is needed.
            chain.doFilter(request, response);
            return;
        }
        if (isPreflight(request)) {
            writeCors(request, response, origin);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        if (!originAllowed(request, origin)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"status\":403,\"message\":\"当前站点来源未被该凭证授权\"}");
            return;
        }
        writeCors(request, response, origin);
        chain.doFilter(request, response);
    }

    private void writeCors(HttpServletRequest request, HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Vary", "Origin");
        response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization,X-API-Key,X-Embed-Token,X-Trace-Id");
        response.setHeader("Access-Control-Expose-Headers", "X-Trace-Id");
        response.setHeader("Access-Control-Max-Age", "600");
    }

    /**
     * Resolves the credential without consuming quota: the request itself will be authenticated
     * again by the controller, and a pre-flight must not be counted as a call.
     */
    private boolean originAllowed(HttpServletRequest request, String origin) {
        String token = ExternalAccessService.extractEmbedToken(request);
        if (token != null) {
            EmbedTokenService.EmbedToken parsed = embedTokens.parse(token);
            if (parsed == null) {
                return false;
            }
            ApiKey key = apiKeyService.get(parsed.getKeyId());
            if (key == null || !key.isUsable()) {
                return false;
            }
            if (!parsed.getOrigin().isEmpty() && !parsed.getOrigin().equalsIgnoreCase(origin)) {
                return false;
            }
            return accessService.allowsOrigin(key, origin);
        }
        String secret = ExternalAccessService.extractSecret(request);
        if (secret == null) {
            // No credential yet: let the controller answer 401 rather than 403 here.
            return true;
        }
        ApiKey key = apiKeyService.authenticate(secret);
        return key != null && accessService.allowsOrigin(key, origin);
    }

    private static boolean isPreflight(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) && request.getHeader("Access-Control-Request-Method") != null;
    }
}
