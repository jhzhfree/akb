package com.jh.knowledgeos.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Preserve the exact signed bytes while keeping the controller's input readable. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class WorkloadRequestFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-AKB-Workload-Assertion";
    public static final String BODY = WorkloadRequestFilter.class.getName() + ".body";
    private static final int MAX_BODY = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return request.getHeader(HEADER) == null || !(path.equals("/api/mcp") || path.startsWith("/api/v1/external/"));
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int read;
        java.io.InputStream source = request.getInputStream();
        while (buffer.size() <= MAX_BODY && (read = source.read(block, 0, Math.min(block.length, MAX_BODY + 1 - buffer.size()))) != -1) buffer.write(block, 0, read);
        byte[] body = buffer.toByteArray();
        if (body.length > MAX_BODY) {
            response.setStatus(413); response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"REQUEST_TOO_LARGE\"}"); return;
        }
        request.setAttribute(BODY, body);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream stream = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return stream.read(); }
                    @Override public boolean isFinished() { return stream.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Blocking JSON input"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
}
