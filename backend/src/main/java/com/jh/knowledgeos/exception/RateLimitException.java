package com.jh.knowledgeos.exception;

/**
 * Raised when an external caller exceeds the quota granted to its API key.
 */
public class RateLimitException extends RuntimeException {

    private final int limitPerMinute;

    public RateLimitException(int limitPerMinute) {
        super("调用频率超过配额：" + limitPerMinute + " 次/分钟");
        this.limitPerMinute = limitPerMinute;
    }

    public int getLimitPerMinute() {
        return limitPerMinute;
    }
}
