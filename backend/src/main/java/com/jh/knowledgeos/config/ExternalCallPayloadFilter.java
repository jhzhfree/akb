package com.jh.knowledgeos.config;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a readable copy of what the caller sent, so it can be audited after the handler consumed it.
 *
 * <p>A request body can be read once: by the time the controller audits the call, the stream is
 * exhausted — and "what exactly did they send" is precisely what a call log has to answer,
 * especially for the failures, where the wrong parameter is the whole explanation. Wrapping the
 * request lets the audit read back the bytes the handler already parsed.</p>
 *
 * <p>Only the external surface is wrapped; every other controller keeps its ordinary request and
 * nobody pays for a buffer nobody reads. Headers are deliberately never captured: that is where
 * {@code X-API-Key} and {@code Authorization} travel, and an audit table holding usable credentials
 * would be a worse defect than an incomplete one.</p>
 */
@Component
public class ExternalCallPayloadFilter extends OncePerRequestFilter {

    public static final String PAYLOAD_ATTRIBUTE = ExternalCallPayloadFilter.class.getName() + ".payload";

    /**
     * Audited surface; everything else is untouched.
     */
    public static final String PATH_PREFIX = "/api/v1/external/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        request.setAttribute(PAYLOAD_ATTRIBUTE, wrapped);
        chain.doFilter(wrapped, response);
    }

    /**
     * Query string and body of the request in flight, keyed as {@code query} and {@code body}.
     *
     * <p>Read from the controller, which has already consumed the body, so the cache is complete at
     * that point. Null when there is nothing captured — a {@code GET} without parameters, or a call
     * audited outside this filter.</p>
     */
    public static Map<String, String> payload(HttpServletRequest request) {
        Object cached = request == null ? null : request.getAttribute(PAYLOAD_ATTRIBUTE);
        if (!(cached instanceof ContentCachingRequestWrapper)) {
            return null;
        }
        ContentCachingRequestWrapper wrapper = (ContentCachingRequestWrapper) cached;
        Map<String, String> parts = new LinkedHashMap<String, String>();
        String query = trimmed(wrapper.getQueryString());
        if (query != null) {
            parts.put("query", query);
        }
        String body = trimmed(readBody(wrapper));
        if (body != null) {
            parts.put("body", body);
        }
        return parts.isEmpty() ? null : parts;
    }

    private static String readBody(ContentCachingRequestWrapper wrapper) {
        byte[] content = wrapper.getContentAsByteArray();
        return content == null || content.length == 0 ? null : new String(content, StandardCharsets.UTF_8);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
