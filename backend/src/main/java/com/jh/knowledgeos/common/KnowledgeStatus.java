package com.jh.knowledgeos.common;

/**
 * Canonical lifecycle states for governed knowledge objects.
 */
public enum KnowledgeStatus {
    DRAFT,
    UNCERTAIN,
    PENDING_REVIEW,
    APPROVED,
    EFFECTIVE,
    CONFLICTED,
    REJECTED,
    SUPERSEDED,
    EXPIRED,
    INVALID;

    public static boolean isRetrievable(String status) {
        return APPROVED.name().equals(status) || EFFECTIVE.name().equals(status);
    }
}
