package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.User;

/**
 * Request-scoped holder for the signed-in user, populated by the authentication
 * interceptor and cleared when the request completes.
 */
public final class AuthContext {

    public static final String REQUEST_ATTRIBUTE = "knowledgeos.currentUser";

    private static final ThreadLocal<User> CURRENT = new ThreadLocal<User>();

    private AuthContext() {
    }

    public static void set(User user) {
        CURRENT.set(user);
    }

    public static User get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
