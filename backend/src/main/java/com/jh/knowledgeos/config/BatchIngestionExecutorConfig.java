package com.jh.knowledgeos.config;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.AuthContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executor behind batch ingestion.
 *
 * <p>Single-file ingestion runs on the request thread: it is slow, but the caller waits for one
 * document. A batch of twenty would take twenty times that, far past any HTTP timeout, so the
 * batch coordinates workers here instead. Concurrency is deliberately small — every document
 * still calls the AI service to parse and extract, and hammering it with twenty parallel
 * documents buys latency nobody can observe while risking the shared model endpoint.</p>
 *
 * <p>The class carries the {@code Config} suffix on purpose: a configuration class contributes a
 * bean under its own decapitalised class name, so naming it after the executor would collide with
 * the executor bean it declares.</p>
 */
@Configuration
public class BatchIngestionExecutorConfig {

    public static final String BEAN_NAME = "batchIngestionExecutor";

    @Bean(BEAN_NAME)
    public ThreadPoolTaskExecutor batchIngestionExecutor(@Value("${knowledge.batch.concurrency:3}") int concurrency, @Value("${knowledge.batch.queue-capacity:200}") int queueCapacity) {
        int size = Math.max(1, concurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("batch-ingest-");
        executor.setTaskDecorator(new AuthPropagationDecorator());
        // A saturated queue degrades to "the submitter does it itself" rather than rejecting:
        // the promise of a batch is that every listed file is accounted for, whatever the load.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Carries the signed-in user into the worker thread.
     *
     * <p>{@link AuthContext} is a {@code ThreadLocal} filled by the authentication interceptor, so
     * a worker would otherwise attribute its documents to {@code system} — every manual batch
     * would lose its author, and task ownership would be wrong on every row.</p>
     */
    static final class AuthPropagationDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            final User submitter = AuthContext.get();
            return new Runnable() {

                @Override
                public void run() {
                    try {
                        AuthContext.set(submitter);
                        runnable.run();
                    } finally {
                        AuthContext.clear();
                    }
                }
            };
        }
    }
}
