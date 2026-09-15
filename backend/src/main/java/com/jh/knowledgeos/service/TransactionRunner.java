package com.jh.knowledgeos.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Small transaction-template wrapper used to keep external AI/index calls outside database transactions.
 */
@Component
public class TransactionRunner {
    private final TransactionTemplate transactionTemplate;

    public TransactionRunner(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public <T> T required(final Supplier<T> supplier) {
        return transactionTemplate.execute(status -> supplier.get());
    }

    public void required(final Runnable runnable) {
        transactionTemplate.executeWithoutResult(status -> runnable.run());
    }
}
