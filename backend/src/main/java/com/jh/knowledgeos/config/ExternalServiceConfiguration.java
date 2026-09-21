package com.jh.knowledgeos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Execution resources for the external interface-service layer.
 *
 * <p>SSE subscriptions are long-lived but idle most of the time, so they get a small dedicated
 * pool instead of competing with request threads.</p>
 */
@Configuration
public class ExternalServiceConfiguration {

    @Bean("externalStreamExecutor")
    public Executor externalStreamExecutor() {
        AtomicLong counter = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("kos-external-stream-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(8, factory);
    }
}
