package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token-based operational authorization filter for high-risk index administration endpoints.
 */
@Component
public class OpsRbacFilter extends OncePerRequestFilter {

    private final KnowledgeProperties properties;

    public OpsRbacFilter(KnowledgeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/knowledge/index/") || !properties.isOpsRbacEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("X-Ops-Token");
        int actual = roleLevel(token);
        int required = requiredLevel(request);
        if (actual < required) {
            response.setStatus(actual == 0 ? 401 : 403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"code\":\"KB-403-001\",\"message\":\"insufficient index operations role\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private int requiredLevel(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equals(method)) {
            return 1;
        }
        if (uri.contains("/replay") || uri.contains("/skip")) {
            return 2;
        }
        return 3;
    }

    private int roleLevel(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        if (token.equals(properties.getOpsAdminToken())) {
            return 3;
        }
        if (token.equals(properties.getOpsOperatorToken())) {
            return 2;
        }
        if (token.equals(properties.getOpsViewerToken())) {
            return 1;
        }
        return 0;
    }
}
