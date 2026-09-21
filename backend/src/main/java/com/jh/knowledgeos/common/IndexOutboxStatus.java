package com.jh.knowledgeos.common;

/**
 * Lifecycle states for transactional index-delivery events.
 */
public enum IndexOutboxStatus {

    PENDING,
    PROCESSING,
    RETRY,
    SUCCEEDED,
    DEAD,
    SKIPPED
}
