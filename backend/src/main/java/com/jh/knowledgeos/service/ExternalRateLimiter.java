package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.RateLimitException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key sliding-window quota for the external interface-service layer.
 *
 * <p>Counters live in memory: the console runs as one logical service per deployment and a
 * restart only resets the current minute. A shared Redis counter would be the next step if
 * the platform is ever scaled out horizontally.</p>
 */
@Component
public class ExternalRateLimiter {
    private static final long WINDOW_MILLIS = 60_000L;
    private final Map<Long, Deque<Long>> windows = new ConcurrentHashMap<Long, Deque<Long>>();

    public void check(ApiKey key) {
        int limit = key.getRateLimitPerMinute() == null ? 60 : Math.max(key.getRateLimitPerMinute(), 0);
        if (limit <= 0) return;
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(key.getId(), id -> new ArrayDeque<Long>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                throw new RateLimitException(limit);
            }
            window.addLast(now);
        }
        if (windows.size() > 2000) {
            evictIdleWindows(now);
        }
    }

    /** 长期不调用的历史窗口会持续占用内存，阈值触发时统一清理空窗口。 */
    private void evictIdleWindows(long now) {
        Iterator<Map.Entry<Long, Deque<Long>>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Deque<Long> window = iterator.next().getValue();
            synchronized (window) {
                if (window.isEmpty() || now - window.peekLast() > WINDOW_MILLIS) {
                    iterator.remove();
                }
            }
        }
    }
}
