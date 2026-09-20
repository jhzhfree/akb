package com.jh.knowledgeos.config;

import com.jh.knowledgeos.service.KnowledgeBaseAccessService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Enforces knowledge-base visibility for every scoped endpoint.
 *
 * <p>The knowledge base id is read from the {@code kb} path variable (or {@code id} for
 * knowledge-base lifecycle calls, or the {@code knowledgeBaseId} form parameter for uploads)
 * so the rule holds even when a caller bypasses the console and calls the API directly.
 * GET means read, every other method means write.</p>
 */
@Component
public class KnowledgeBaseAccessInterceptor implements HandlerInterceptor {
    private final KnowledgeBaseAccessService access;

    public KnowledgeBaseAccessInterceptor(KnowledgeBaseAccessService access) {
        this.access = access;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long kb = resolveKnowledgeBaseId(request);
        if (kb == null) {
            return true;
        }
        String method = request.getMethod();
        boolean write = !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)
                && !"OPTIONS".equalsIgnoreCase(method);
        access.assertAccess(kb, write);
        return true;
    }

    private Long resolveKnowledgeBaseId(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (attribute instanceof Map) {
            Map<?, ?> variables = (Map<?, ?>) attribute;
            Long kb = toLong(variables.get("kb"));
            if (kb != null) return kb;
            if (request.getRequestURI() != null && request.getRequestURI().contains("/bases/")) {
                Long id = toLong(variables.get("id"));
                if (id != null) return id;
            }
        }
        return toLong(request.getParameter("knowledgeBaseId"));
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
