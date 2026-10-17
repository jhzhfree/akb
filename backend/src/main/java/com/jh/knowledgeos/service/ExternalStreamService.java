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

    /**
     * The pipeline stages a client can show. Stage keys match what the AI service reports, so
     * the widget can render the real progress instead of an invented one; the labels are what
     * a reader sees. Kept here rather than in the AI service because this is the contract the
     * embeddable widget and every other SSE client depend on.
     */
    private static final String[][] PIPELINE_STAGES = {
        { "rewrite", "查询改写" },
        { "route", "意图路由" },
        { "recall", "多通道召回" },
        { "acl_filter", "权限过滤" },
        { "fusion", "结果融合" },
        { "rerank", "重排序" },
        { "context", "上下文装配" },
        { "llm", "答案生成" },
        { "citation", "引用校验" }
    };

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
     * <p>How the answer body travels is the caller's choice. By default it is pushed slice by
     * slice as the model writes it. With {@code wholeAnswer} the slices are dropped entirely and
     * the stream carries retrieval progress only, while the text arrives whole in the {@code done}
     * event — the same finished, format-processed answer the console gets from its blocking query.
     * A client that renders the finished answer that way never shows an unformatted half of it.</p>
     */
    public void streamQuery(ApiKey key, Long knowledgeBaseId, String question, Integer topK, String answerFormat, boolean wholeAnswer,
        Double minScore, SseEmitter emitter) {
        executor.execute(() -> {
            try {
                // The plan is announced up front so a UI can draw the whole pipeline and then
                // fill it in: a reader sees what is about to happen instead of a blank pane.
                emitter.send(SseEmitter.event().name("open").data(openPayload(knowledgeBaseId, question, topK, answerFormat, wholeAnswer)));
                for (String[] stage : PIPELINE_STAGES) {
                    emitter.send(SseEmitter.event().name("stage").data(stagePayload(knowledgeBaseId, stage[0], stage[1], "running", null)));
                }
                try {
                    streamLive(key, knowledgeBaseId, question, topK, answerFormat, wholeAnswer, minScore, emitter);
                } catch (Exception streamingFailed) {
                    // Without this line a fallback is invisible: the client still gets an answer,
                    // and nothing anywhere says the events were replayed instead of streamed.
                    log.warn("live query stream failed; falling back to blocking query: {}", streamingFailed.toString());
                    // A stream that cannot be opened still has to answer. The blocking call runs
                    // the same pipeline, so the fallback loses event freshness — the answer comes
                    // in slices at the end — but never correctness.
                    replay(key, knowledgeBaseId, question, topK, answerFormat, wholeAnswer, minScore, emitter);
                }
                emitter.complete();
            } catch (Exception e) {
                fail(emitter, e);
            }
        });
    }

    /**
     * Streams the live pipeline: every stage is pushed the moment the engine finishes it and every
     * answer chunk the moment the model writes it. The events a client sees are therefore the
     * execution being watched, not a finished answer being replayed.
     */
    private void streamLive(ApiKey key, Long knowledgeBaseId, String question, Integer topK, String answerFormat, boolean wholeAnswer,
        Double minScore, SseEmitter emitter) throws Exception {
        facade.streamQuery(key, knowledgeBaseId, question, topK, null, PlatformEventPublisher.SOURCE_SSE, answerFormat, minScore,
            new KnowledgeQueryService.QueryStreamListener() {
                @Override
                public void stage(Map<String, Object> stage) {
                    send(emitter, "stage", stagePayload(knowledgeBaseId, stage));
                }

                @Override
                public void delta(String text) {
                    // wholeAnswer: the slices are deliberately not pushed. What the model writes as
                    // it goes is unformatted — no "引用原文" section, no citation superscripts — so a
                    // client that renders it shows something the console would never show. The text
                    // reaches such a client once, inside `done`, already format-processed.
                    if (wholeAnswer) {
                        return;
                    }
                    if (text != null && !text.isEmpty()) {
                        send(emitter, deltaEvent(text));
                    }
                }

                @Override
                public void done(Map<String, Object> result) {
                    Map<String, Object> citations = new LinkedHashMap<String, Object>();
                    citations.put("citations", result.get("citations"));
                    send(emitter, "citation", citations);
                    send(emitter, "done", donePayload(knowledgeBaseId, question, result));
                }
            });
    }

    /**
     * What a client needs to explain an answer, not only to show it: confidence, the channels that
     * answered, whether reranking ran and where the conclusion came from are the difference between
     * "here is some text" and "here is an answer you can check". Every field is additive — a client
     * that only reads queryId and answer keeps working.
     */
    private static Map<String, Object> donePayload(Long knowledgeBaseId, String question, Map<String, Object> result) {
        Map<String, Object> done = new LinkedHashMap<String, Object>();
        done.put("queryId", result.get("queryId"));
        done.put("knowledgeBaseId", knowledgeBaseId);
        done.put("question", question);
        done.put("answer", result.get("answer"));
        done.put("answerFormat", result.get("answerFormat"));
        done.put("model", result.get("model"));
        done.put("trace", result.get("trace"));
        done.put("confidence", result.get("confidence"));
        done.put("durationMs", result.get("durationMs"));
        done.put("channels", result.get("channels"));
        done.put("strategies", result.get("strategies"));
        done.put("reranked", result.get("reranked"));
        done.put("questionTypeLabel", result.get("questionTypeLabel"));
        done.put("rewrittenQuery", result.get("rewrittenQuery"));
        done.put("conclusionSource", result.get("conclusionSource"));
        done.put("answerQualification", result.get("answerQualification"));
        done.put("contextChars", result.get("contextChars"));
        return done;
    }

    /**
     * Fallback for a stream that could not be opened: run the blocking call and replay it as
     * events. The stage timings are still the engine's own measurements — what is lost is only
     * the moment at which they arrive.
     */
    private void replay(ApiKey key, Long knowledgeBaseId, String question, Integer topK, String answerFormat, boolean wholeAnswer,
        Double minScore, SseEmitter emitter) throws Exception {
        long started = System.currentTimeMillis();
        Map<String, Object> result = facade.query(key, knowledgeBaseId, question, topK, null, PlatformEventPublisher.SOURCE_SSE, answerFormat, minScore);
        List<Map<String, Object>> stages = pipelineStages(result);
        for (String[] stage : PIPELINE_STAGES) {
            Map<String, Object> reported = findStage(stages, stage[0]);
            // A stage the engine did not report is still shown as done: the pipeline ran it, the
            // engine simply reported no detail for it.
            emitter.send(SseEmitter.event().name("stage").data(stagePayload(knowledgeBaseId, stage[0], stage[1], "done", reported)));
        }
        String answer = result.get("answer") == null ? "" : String.valueOf(result.get("answer"));
        // Same contract as the live path: no slices when the caller asked for the whole answer.
        if (!wholeAnswer) {
            for (String slice : slices(answer)) {
                send(emitter, deltaEvent(slice));
                Thread.sleep(Math.max(properties.getAnswerChunkDelayMillis(), 0));
            }
        }
        Map<String, Object> citations = new LinkedHashMap<String, Object>();
        citations.put("citations", result.get("citations"));
        emitter.send(SseEmitter.event().name("citation").data(citations));
        Map<String, Object> done = donePayload(knowledgeBaseId, question, result);
        done.put("answer", answer);
        done.put("stages", stages.isEmpty() ? fallbackStages(started) : stages);
        done.put("elapsedMs", System.currentTimeMillis() - started);
        done.put("streamed", Boolean.FALSE);
        emitter.send(SseEmitter.event().name("done").data(done));
    }

    private static void send(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 推送失败：" + e.getMessage(), e);
        }
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder builder) {
        try {
            emitter.send(builder);
        } catch (Exception e) {
            throw new IllegalStateException("SSE 推送失败：" + e.getMessage(), e);
        }
    }

    /**
     * Builds a {@code delta} event that survives embedded newlines.
     *
     * <p>The {@code data} field of SSE cannot carry a raw newline, and {@code SseEmitter} writes
     * a String verbatim: one newline ends the field and drops the rest of the chunk onto a line
     * with no {@code data:} prefix — which a spec-following client discards. The answer then
     * reaches the reader as a single run-together line, with Markdown headings and bullets gone.
     * Emitting each line as its own {@code data:} field is what the format asks for, and clients
     * rejoin the fields with {@code \n}, so the text is byte-identical to the original.</p>
     *
     * <p>Package-private so {@code ExternalStreamServiceTest} can assert the wire framing without
     * standing up a container.</p>
     */
    static SseEmitter.SseEventBuilder deltaEvent(String text) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name("delta");
        for (String line : text.split("\r?\n", -1)) {
            builder.data(line);
        }
        return builder;
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

    private Map<String, Object> stagePayload(Long kb, String key, String label, String status, Map<String, Object> reported) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("key", key);
        payload.put("label", label);
        payload.put("status", status);
        payload.put("time", System.currentTimeMillis());
        if (reported != null) {
            payload.put("latencyMs", reported.get("latencyMs"));
            payload.put("inputCount", reported.get("inputCount"));
            payload.put("outputCount", reported.get("outputCount"));
            payload.put("detail", reported.get("detail"));
        }
        return payload;
    }

    /**
     * A stage the engine just reported, forwarded as it is: the measurements are the engine's,
     * and adding the knowledge base and a wall-clock timestamp is all this layer contributes.
     */
    private Map<String, Object> stagePayload(Long kb, Map<String, Object> reported) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>(reported);
        payload.put("knowledgeBaseId", kb);
        payload.put("time", System.currentTimeMillis());
        if (payload.get("status") == null) {
            payload.put("status", "done");
        }
        return payload;
    }

    private Map<String, Object> openPayload(Long kb, String question, Integer topK, String answerFormat, boolean wholeAnswer) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("question", question);
        payload.put("topK", topK);
        // 与 REST 一致：夹取后的值另起一个名字，不覆盖 `topK`（下游另有语义）。
        payload.put("effectiveTopK", ExternalKnowledgeFacade.effectiveTopK(topK));
        payload.put("answerFormat", answerFormat);
        // Echoed back so a client can tell which delivery it actually got: a value that was not
        // recognised falls back to "stream", and the client's own rendering depends on which one.
        payload.put("content", wholeAnswer ? "final" : "stream");
        List<Map<String, Object>> plan = new ArrayList<Map<String, Object>>();
        for (String[] stage : PIPELINE_STAGES) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", stage[0]);
            item.put("label", stage[1]);
            plan.add(item);
        }
        payload.put("stages", plan);
        payload.put("time", System.currentTimeMillis());
        return payload;
    }

    /**
     * Real stage measurements reported by the engine, or an empty list when it reported none.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pipelineStages(Map<String, Object> result) {
        List<Map<String, Object>> stages = new ArrayList<Map<String, Object>>();
        Object trace = result.get("trace");
        if (!(trace instanceof Map)) {
            return stages;
        }
        Map<String, Object> traceMap = (Map<String, Object>) trace;
        Object raw = traceMap.get("stages");
        if (!(raw instanceof List) && traceMap.get("trace") instanceof Map) {
            // Older responses nest the AI payload one level deeper.
            raw = ((Map<String, Object>) traceMap.get("trace")).get("stages");
        }
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item instanceof Map) {
                    stages.add((Map<String, Object>) item);
                }
            }
        }
        return stages;
    }

    private Map<String, Object> findStage(List<Map<String, Object>> stages, String key) {
        for (Map<String, Object> stage : stages) {
            Object stageKey = stage.get("key");
            if (stageKey != null && key.equalsIgnoreCase(String.valueOf(stageKey))) {
                return stage;
            }
        }
        return null;
    }

    private List<Map<String, Object>> fallbackStages(long started) {
        List<Map<String, Object>> stages = new ArrayList<Map<String, Object>>();
        long elapsed = Math.max(0L, System.currentTimeMillis() - started);
        for (String[] stage : PIPELINE_STAGES) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", stage[0]);
            item.put("label", stage[1]);
            item.put("status", "done");
            item.put("latencyMs", elapsed);
            stages.add(item);
        }
        return stages;
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
