package com.jh.knowledgeos.exception;

/**
 * Raised when an external call presents a missing, unknown, revoked or expired API key.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
