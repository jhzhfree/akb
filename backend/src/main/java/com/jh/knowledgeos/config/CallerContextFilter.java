package com.jh.knowledgeos.config;

import com.jh.knowledgeos.service.CallerContext;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Owns cleanup on success, authentication failure and tool exceptions. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public final class CallerContextFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/api/mcp".equals(path) || path.startsWith("/api/v1/external/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        try (CallerContext.Scope ignored = CallerContext.beginRequest()) { chain.doFilter(request, response); }
    }
}
