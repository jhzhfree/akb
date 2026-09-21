package com.jh.knowledgeos.service;

import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Small transaction-template wrapper used to keep external AI/index calls outside database transactions.
 */
@Component
public class TransactionRunner {
    /**
     * How many times a transaction is allowed to lose a deadlock before the failure surfaces.
     * Bounded because a batch that really cannot make progress must say so, not spin forever.
     */
    public static final int DEADLOCK_ATTEMPTS = 4;

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

    /**
     * Runs an idempotent write transaction, retrying it when the database reports that it lost a
     * deadlock.
     *
     * <p>A deadlock is not a bug in the failing statement — it is the database declaring that two
     * correct transactions asked for the same rows in incompatible order, and its verdict already
     * rolled all of them back. So the loser's work is entirely undone and redoing it is the
     * resolution MySQL itself asks for ("try restarting transaction"). Under batch ingestion this
     * is routine: several documents merge entities against the same knowledge base at once.</p>
     *
     * <p>Only idempotent work belongs here. Every retry must start from the state the rollback
     * left behind, which means no external calls may sit inside the lambda: a retried AI request
     * is spent money and a spent side effect, whatever the database did.</p>
     */
    public <T> T retryingOnDeadlock(final Supplier<T> supplier) {
        return retryingOnDeadlock(supplier, DEADLOCK_ATTEMPTS);
    }

    public void retryingOnDeadlock(final Runnable runnable) {
        retryingOnDeadlock(() -> {
            runnable.run();
            return null;
        }, DEADLOCK_ATTEMPTS);
    }

    public <T> T retryingOnDeadlock(final Supplier<T> supplier, int attempts) {
        int total = Math.max(1, attempts);
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> supplier.get());
            } catch (RuntimeException ex) {
                if (attempt >= total || !isDeadlock(ex)) {
                    throw ex;
                }
                backOff(attempt);
            }
        }
    }

    /**
     * Recognises the deadlock verdict however far down the stack it hides: MyBatis wraps the JDBC
     * exception, Spring wraps that again in its own data-access hierarchy.
     */
    static boolean isDeadlock(Throwable error) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 20; depth++) {
            if (cursor instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (cursor instanceof SQLException) {
                SQLException sql = (SQLException) cursor;
                // 1213 is MySQL's deadlock error; 40001 is the SQL standard class for it.
                if (sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState())) {
                    return true;
                }
            }
            String message = cursor.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("deadlock")) {
                return true;
            }
            Throwable cause = cursor.getCause();
            cursor = cause == cursor ? null : cause;
        }
        return false;
    }

    /**
     * Waits before trying again, with jitter.
     *
     * <p>The wait is short because nothing is broken — the conflicting transaction is about to
     * commit and release its locks. Jitter is what makes it stick: without it, the workers that
     * collided retry in lockstep and simply re-enact the same collision.</p>
     */
    private void backOff(int attempt) {
        try {
            Thread.sleep(20L * attempt + ThreadLocalRandom.current().nextLong(40L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待重试死锁事务时被中断", ex);
        }
    }
}
