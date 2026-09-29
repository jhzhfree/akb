package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.IngestionRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-Sent Events channel of the external interface-service layer.
 *
 * <p>Two flavours are offered: {@code streamQuery} replays one answer as it is produced so a
 * UI can render progressively, and {@code streamEvents} pushes workspace activity (ingestion
 * progress, knowledge growth) to subscribers until the connection budget is spent.</p>
 */
@Service
public class ExternalStreamService {

    private static final Logger log = LoggerFactory.getLogger(ExternalStreamService.class);

    private static final String[] ANSWER_STAGES = { "REWRITE", "RECALL", "FUSION", "RERANK", "ANSWER" };

    private final ExternalKnowledgeFacade facade;

    private final IngestionObservabilityService ingestionService;

    private final KnowledgeProperties.External properties;

    private final Executor executor;

    public ExternalStreamService(ExternalKnowledgeFacade facade, IngestionObservabilityService ingestionService, KnowledgeProperties properties,
        @Qualifier("externalStreamExecutor") Executor executor) {
        this.facade = facade;
        this.ingestionService = ingestionService;
        this.properties = properties.external();
        this.executor = executor;
    }

    /**
     * Streams one answer.
     *
     * <p>The pipeline itself still returns the finished answer, so the text is replayed in
     * slices; the event contract (stage / delta / citation / done) is what clients depend on,
     * and switching to token-level streaming later needs no client change.</p>
     */
    public void streamQuery(ApiKey key, Long knowledgeBaseId, String question, Integer topK, SseEmitter emitter) {
        executor.execute(() -> {
            try {
                for (String stage : ANSWER_STAGES) {
                    emitter.send(SseEmitter.event().name("stage").data(stagePayload(knowledgeBaseId, stage)));
                }
                Map<String, Object> result = facade.query(knowledgeBaseId, question, topK, null);
                String answer = result.get("answer") == null ? "" : String.valueOf(result.get("answer"));
                for (String slice : slices(answer)) {
                    emitter.send(SseEmitter.event().name("delta").data(slice));
                    Thread.sleep(Math.max(properties.getAnswerChunkDelayMillis(), 0));
                }
                Map<String, Object> citations = new LinkedHashMap<String, Object>();
                citations.put("citations", result.get("citations"));
                emitter.send(SseEmitter.event().name("citation").data(citations));
                Map<String, Object> done = new LinkedHashMap<String, Object>();
                done.put("queryId", result.get("queryId"));
                done.put("knowledgeBaseId", knowledgeBaseId);
                done.put("question", question);
                done.put("answer", answer);
                done.put("model", result.get("model"));
                done.put("trace", result.get("trace"));
                emitter.send(SseEmitter.event().name("done").data(done));
                emitter.complete();
            } catch (Exception e) {
                fail(emitter, e);
            }
        });
    }

    /**
     * Pushes workspace activity until the caller disconnects or the connection budget expires.
     *
     * <p>Polling, not a message broker: it keeps the deployment footprint unchanged and is
     * enough for progress and notification latencies measured in seconds.</p>
     */
    public void streamEvents(ApiKey key, Long knowledgeBaseId, SseEmitter emitter) {
        final AtomicBoolean running = new AtomicBoolean(true);
        emitter.onCompletion(() -> running.set(false));
        emitter.onTimeout(() -> running.set(false));
        emitter.onError(e -> running.set(false));
        executor.execute(() -> {
            long deadline = System.currentTimeMillis() + Math.max(properties.getSseMaxMinutes(), 1) * 60_000L;
            long lastHeartbeat = System.currentTimeMillis();
            String lastRunSignature = "";
            String lastKnowledgeSignature = "";
            try {
                emitter.send(SseEmitter.event().name("open").data(eventPayload(knowledgeBaseId, "订阅已建立")));
                while (running.get() && System.currentTimeMillis() < deadline) {
                    String runSignature = ingestionSignature(knowledgeBaseId);
                    if (!runSignature.isEmpty() && !runSignature.equals(lastRunSignature)) {
                        lastRunSignature = runSignature;
                        emitter.send(SseEmitter.event().name("ingestion").data(eventPayload(knowledgeBaseId, runSignature)));
                    }
                    String knowledgeSignature = knowledgeSignature(knowledgeBaseId);
                    if (!knowledgeSignature.isEmpty() && !knowledgeSignature.equals(lastKnowledgeSignature)) {
                        lastKnowledgeSignature = knowledgeSignature;
                        emitter.send(SseEmitter.event().name("knowledge").data(eventPayload(knowledgeBaseId, knowledgeSignature)));
                    }
                    if (System.currentTimeMillis() - lastHeartbeat > Math.max(properties.getSseHeartbeatSeconds(), 3) * 1000L) {
                        lastHeartbeat = System.currentTimeMillis();
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    }
                    Thread.sleep(3000L);
                }
                emitter.complete();
            } catch (Exception e) {
                if (running.get()) {
                    fail(emitter, e);
                }
            }
        });
    }

    private String ingestionSignature(Long kb) {
        List<IngestionRun> runs = ingestionService.list(kb, 5);
        if (runs == null || runs.isEmpty()) {
            return "";
        }
        IngestionRun latest = runs.get(0);
        return "run=" + latest.getRunCode() + " status=" + latest.getStatus() + " stage=" + latest.getCurrentStage() + " source=" + latest.getSourceName();
    }

    private String knowledgeSignature(Long kb) {
        Object overview = facade.overview(kb, null);
        if (!(overview instanceof Map)) {
            return "";
        }
        Map<String, Object> values = (Map<String, Object>) overview;
        return "cards=" + values.get("cardCount") + " claims=" + values.get("claimCount") + " chunks=" + values.get("chunkCount") + " pending=" +
            values.get("pendingReviewCount");
    }

    private Map<String, Object> stagePayload(Long kb, String stage) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("stage", stage);
        payload.put("time", System.currentTimeMillis());
        return payload;
    }

    private Map<String, Object> eventPayload(Long kb, String message) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("message", message);
        payload.put("time", System.currentTimeMillis());
        return payload;
    }

    private List<String> slices(String answer) {
        List<String> slices = new ArrayList<String>();
        if (answer.isEmpty()) {
            slices.add("");
            return slices;
        }
        int size = Math.max(properties.getAnswerChunkChars(), 8);
        for (int index = 0; index < answer.length(); index += size) {
            slices.add(answer.substring(index, Math.min(index + size, answer.length())));
        }
        return slices;
    }

    private void fail(SseEmitter emitter, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
        } catch (Exception ignored) {
            log.debug("sse error event could not be delivered: {}", ignored.getMessage());
        }
        emitter.completeWithError(e);
    }
}
