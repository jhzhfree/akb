package com.jh.knowledgeos.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.ApiErrorCode;
import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies the session token on every API call and exposes the signed-in user.
 *
 * <p>Endpoints that must stay reachable before login (login itself, health checks, and
 * OpenAPI docs) are whitelisted; everything else requires a valid session unless anonymous
 * read-only access is explicitly enabled.</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "Authorization";

    public static final String TOKEN_HEADER_ALIAS = "X-Auth-Token";

    private static final String BEARER = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /**
     * 对外接口服务自带 API Key 鉴权与审计，不再要求控制台会话。
     */
    private static final List<String> PUBLIC_PREFIXES = Arrays.asList("/api/auth/login", "/api/auth/status", "/v3/api-docs", "/swagger-ui", "/error",
        "/actuator/", "/api/v1/external/", "/api/mcp");

    private final AuthService authService;

    private final KnowledgeProperties.Auth properties;

    private final ObjectMapper objectMapper;

    public AuthInterceptor(AuthService authService, KnowledgeProperties properties, ObjectMapper objectMapper) {
        this.authService = authService;
        this.properties = properties.auth();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 跨域预检不带凭证，放行后由 Spring 的 CORS 处理，否则浏览器拿不到真正的 401/403。
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (isPublic(path)) {
            return true;
        }
        String token = extractToken(request);
        User user = token == null ? null : authService.authenticate(token);
        if (user != null) {
            AuthContext.set(user);
            request.setAttribute(AuthContext.REQUEST_ATTRIBUTE, user);
            MDC.put("userId", String.valueOf(user.getId()));
            return true;
        }
        if (properties.isAllowAnonymous()) {
            return true;
        }
        log.info("rejecting unauthenticated request path={}", path);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(ApiErrorCode.UNAUTHORIZED, "未登录或登录已过期")));
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
        MDC.remove("userId");
    }

    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_PREFIXES) {
            if (path != null && path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shared token extraction so controllers can log out or inspect the current session.
     */
    public static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(TOKEN_HEADER);
        if (header != null && header.startsWith(BEARER)) {
            return header.substring(BEARER.length()).trim();
        }
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        String alias = request.getHeader(TOKEN_HEADER_ALIAS);
        return alias == null || alias.trim().isEmpty() ? null : alias.trim();
    }
}
