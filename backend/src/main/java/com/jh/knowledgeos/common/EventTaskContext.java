package com.jh.knowledgeos.common;

/**
 * The asynchronous-task identity that every emitted event has to carry (design §17.2).
 *
 * <p>An event that only says "something changed" cannot be joined back to the run that changed it:
 * operators cannot tell whether an event is the first attempt or the third retry, and a subscriber
 * cannot deduplicate replays. This context is optional on the publisher API but mandatory in spirit
 * for ingestion, extraction and indexing, which are the paths that actually retry.</p>
 */
public final class EventTaskContext {
    private final String taskId;
    private final String objectId;
    private final String objectVersion;
    private final String idempotencyKey;
    private final int retryCount;

    private EventTaskContext(Builder builder) {
        this.taskId = builder.taskId;
        this.objectId = builder.objectId;
        this.objectVersion = builder.objectVersion;
        this.idempotencyKey = builder.idempotencyKey;
        this.retryCount = builder.retryCount;
    }

    public static Builder task(String taskId) {
        return new Builder(taskId);
    }

    public String getTaskId() { return taskId; }
    public String getObjectId() { return objectId; }
    public String getObjectVersion() { return objectVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getRetryCount() { return retryCount; }

    public static final class Builder {
        private final String taskId;
        private String objectId;
        private String objectVersion;
        private String idempotencyKey;
        private int retryCount;

        private Builder(String taskId) {
            this.taskId = taskId;
        }

        public Builder object(String objectId, String objectVersion) {
            this.objectId = objectId;
            this.objectVersion = objectVersion;
            return this;
        }

        public Builder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        public Builder retryCount(int count) {
            this.retryCount = count;
            return this;
        }

        public EventTaskContext build() {
            return new EventTaskContext(this);
        }
    }
}
