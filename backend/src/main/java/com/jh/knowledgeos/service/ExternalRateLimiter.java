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

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<String, Deque<Long>>();

    public void check(ApiKey key) {
        int limit = key.getRateLimitPerMinute() == null ? 60 : Math.max(key.getRateLimitPerMinute(), 0);
        if (limit <= 0) {
            return;
        }
        consume("key:" + key.getId(), limit);
    }

    @org.springframework.beans.factory.annotation.Value("${knowledge.external.agent-rate-limit-per-minute:30}")
    private int agentLimit = 30;

    /** Verified identity only. Rotation of a service Key does not reset this budget. */
    public void checkAgent(ApiKey key) {
        if (key.getWorkloadIdentity() == null) throw new IllegalArgumentException("Verified Agent required");
        if (agentLimit > 0) consume("agent:" + key.getWorkloadPolicyId() + ":" + key.getWorkloadIdentity().getAgentPrincipalId(), agentLimit);
    }

    private void consume(String bucket, int limit) {
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(bucket, id -> new ArrayDeque<Long>());
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

    /**
     * 长期不调用的历史窗口会持续占用内存，阈值触发时统一清理空窗口。
     */
    private void evictIdleWindows(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> iterator = windows.entrySet().iterator();
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
