package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.IngestionRun;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists lifecycle events so ingestion is observable and auditable from the UI.
 *
 * <p>Writing an event observes the pipeline; it must never decide its outcome. MySQL in strict
 * mode does not trim an oversized value, it raises {@code Data truncation} — so an unexpectedly
 * large detail used to travel back up the call stack and mark a document whose chunks, cards and
 * claims were already safely stored as FAILED. Every field therefore degrades instead of failing:
 * messages are clipped to their column, and a detail too large to keep is replaced by a bounded
 * record that still says what happened and how much was dropped.</p>
 */
@Service
public class IngestionObservabilityService {

    /**
     * Column widths of t_ingestion_event.message and t_ingestion_run.error_message.
     */
    private static final int MESSAGE_LIMIT = 1000;

    private static final int ERROR_LIMIT = 2000;

    private final KnowledgeMapper mapper;

    private final IdGenerator ids;

    private final ObjectMapper json;

    private final int detailLimit;

    public IngestionObservabilityService(KnowledgeMapper mapper, IdGenerator ids, ObjectMapper json,
        @Value("${knowledge.ingestion.event-detail-chars:12000}") int detailChars) {
        this.mapper = mapper;
        this.ids = ids;
        this.json = json;
        // detail_json is TEXT; 12k characters stays far inside its byte budget even in Chinese.
        this.detailLimit = Math.max(500, detailChars);
    }

    public IngestionRun start(Long kb, String sourceName) {
        IngestionRun run = new IngestionRun();
        run.setRunCode(ids.next("ING_"));
        run.setKnowledgeBaseId(kb);
        run.setSourceName(sourceName);
        run.setStatus("RUNNING");
        run.setCurrentStage("SOURCE_RECEIVED");
        run.setChunkCount(0);
        run.setCardCount(0);
        run.setClaimCount(0);
        mapper.insertIngestionRun(run);
        event(run.getId(), "SOURCE_RECEIVED", "SOURCE", "SUCCESS", "已接收原始资料", null);
        return run;
    }

    public void event(Long runId, String type, String stage, String status, String message, Object detail) {
        mapper.insertIngestionEvent(runId, type, stage, status, clip(message, MESSAGE_LIMIT), serialize(detail));
        mapper.updateIngestionRunStage(runId, stage);
    }

    public void checkpoint(Long runId, int chunks, int cards, int claims) {
        mapper.checkpointIngestionRun(runId, chunks, cards, claims);
    }

    public void completeOutcome(Long runId, int chunks, int cards, int claims, String outcome) {
        String state = "EXTRACTED".equals(outcome) ? "SUCCEEDED" : "PARTIAL";
        mapper.completeIngestionRun(runId, state, "COMPLETED", chunks, cards, claims, null);
        event(runId, "INGESTION_COMPLETED", "COMPLETED", state, "知识摄入完成：" + outcome, null);
    }

    public void complete(Long runId, int chunks, int cards, int claims) {
        mapper.completeIngestionRun(runId, "SUCCEEDED", "COMPLETED", chunks, cards, claims, null);
        event(runId, "INGESTION_COMPLETED", "COMPLETED", "SUCCESS", "知识摄入流程完成", null);
    }

    public void fail(Long runId, String stage, Exception error) {
        // A wrapped failure often carries no message of its own; the type then has to stand in,
        // otherwise the run would fail with no explanation of which statement broke.
        String message = error.getMessage() == null || error.getMessage().trim().isEmpty() ? error.getClass().getSimpleName() : error.getMessage();
        IngestionRun previous = mapper.getIngestionRun(runId);
        mapper.completeIngestionRun(runId, "FAILED", stage, previous == null ? 0 : previous.getChunkCount(),
            previous == null ? 0 : previous.getCardCount(), previous == null ? 0 : previous.getClaimCount(), clip(message, ERROR_LIMIT));
        event(runId, "INGESTION_FAILED", stage, "FAILED", message, null);
    }

    public List<IngestionRun> list(Long kb, Integer limit) {
        return mapper.listIngestionRuns(kb, limit == null ? 20 : Math.min(limit, 100));
    }

    public Map<String, Object> detail(Long runId) {
        IngestionRun run = mapper.getIngestionRun(runId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", run.getId());
        result.put("runCode", run.getRunCode());
        result.put("knowledgeBaseId", run.getKnowledgeBaseId());
        result.put("sourceName", run.getSourceName());
        result.put("status", run.getStatus());
        result.put("currentStage", run.getCurrentStage());
        result.put("chunkCount", run.getChunkCount());
        result.put("cardCount", run.getCardCount());
        result.put("claimCount", run.getClaimCount());
        result.put("errorMessage", run.getErrorMessage());
        result.put("startedTime", run.getStartedTime());
        result.put("finishedTime", run.getFinishedTime());
        result.put("events", mapper.listIngestionEvents(runId));
        return result;
    }

    private String serialize(Object detail) {
        if (detail == null) {
            return null;
        }
        String text;
        try {
            text = json.writeValueAsString(detail);
        } catch (Exception ignored) {
            return "{\"serializationError\":true}";
        }
        return text.length() <= detailLimit ? text : clipped(text);
    }

    /**
     * Stand-in for a detail too large to store.
     *
     * <p>The preview keeps the beginning — usually the object identity and counts — so the event
     * still answers "what was this about", while saying plainly that the rest is elsewhere.</p>
     */
    private String clipped(String text) {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("truncated", true);
        record.put("originalChars", text.length());
        record.put("retainedChars", detailLimit);
        record.put("preview", clip(text, detailLimit));
        record.put("note", "详情超出事件可存储上限，已截断；完整结果请在对应的业务对象上查看");
        try {
            return json.writeValueAsString(record);
        } catch (Exception ignored) {
            return "{\"truncated\":true,\"originalChars\":" + text.length() + "}";
        }
    }

    /**
     * Clips to a column width.
     *
     * <p>The surrogate check is not cosmetic: cutting a Chinese string is fine, but splitting an
     * emoji or another supplementary character leaves MySQL rejecting the value outright.</p>
     */
    private String clip(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        int cut = limit;
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut);
    }
}
