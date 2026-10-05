package com.jh.knowledgeos.config;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.AuthContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the signed-in user into a worker thread.
 *
 * <p>{@link AuthContext} is a {@code ThreadLocal} filled by the authentication interceptor, so a
 * worker would otherwise attribute its work to {@code system} — a manual batch would lose its
 * author, and task ownership would be wrong on every row it writes.</p>
 */
public final class AuthPropagationDecorator implements TaskDecorator {

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
