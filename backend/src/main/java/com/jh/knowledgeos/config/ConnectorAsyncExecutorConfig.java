package com.jh.knowledgeos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executor for the work a connector sync leaves behind.
 *
 * <p>Refreshing the catalogue is a few remote pages and is fast enough for a request. Re-deriving
 * knowledge is not: every new or changed item calls the AI service to parse and extract, so a
 * project with twenty changed tickets is twenty model calls — minutes, not seconds. Doing that on
 * the request thread turned a working button into a gateway timeout, so the catalogue is returned
 * as soon as it is read and the heavier half continues here.</p>
 *
 * <p>It is deliberately separate from the batch ingestion pool: a sync should never starve a file
 * batch, nor be starved by one.</p>
 */
@Configuration
public class ConnectorAsyncExecutorConfig {

    public static final String BEAN_NAME = "connectorAsyncExecutor";

    @Bean(BEAN_NAME)
    public ThreadPoolTaskExecutor connectorAsyncExecutor(@Value("${knowledge.connector.async-concurrency:2}") int concurrency,
        @Value("${knowledge.connector.async-queue-capacity:100}") int queueCapacity) {
        int size = Math.max(1, concurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("connector-sync-");
        executor.setTaskDecorator(new AuthPropagationDecorator());
        // Falling back to the caller means the request thread pays the cost instead of dropping the
        // work: a sync that silently ingested nothing is worse than one that took a while.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
