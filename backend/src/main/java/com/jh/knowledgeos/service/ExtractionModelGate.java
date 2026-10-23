package com.jh.knowledgeos.service;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounds model requests from upload, regeneration and direct extraction within one backend instance. */
@Component
public class ExtractionModelGate {
    private final Semaphore slots;
    private final int timeoutSeconds;

    public ExtractionModelGate(@Value("${knowledge.extraction.concurrency:1}") int concurrency,
        @Value("${knowledge.extraction.queue-timeout-seconds:300}") int timeoutSeconds) {
        this.slots = new Semaphore(Math.max(1, concurrency), true);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /** Database work never holds a model slot. Interrupted and timed-out waits fail explicitly. */
    public Map<String, Object> extract(AiClient client, Map<String, Object> request) {
        long started = System.nanoTime();
        try {
            if (!slots.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Extraction model queue timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Extraction model queue interrupted", ex);
        }
        try {
            long queuedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Map<String, Object> result = client.post(AiApiPaths.EXTRACT, request);
            if (result == null) { throw new IllegalStateException("Extraction model returned no response"); }
            result.put("extractionQueueMs", queuedMs);
            return result;
        } finally { slots.release(); }
    }
}
