package com.jh.knowledgeos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution resources for the platform event bus.
 *
 * <p>Fan-out must never run on a business thread: a slow or stalled SSE client would otherwise
 * throttle ingestion and query traffic. Two threads are enough because publishing is a memory
 * hand-off followed by a socket write.</p>
 */
@Configuration
public class PlatformEventConfiguration {

    @Bean("platformEventFanoutExecutor")
    public Executor platformEventFanoutExecutor() {
        AtomicLong counter = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("kos-event-fanout-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, factory);
    }
}
