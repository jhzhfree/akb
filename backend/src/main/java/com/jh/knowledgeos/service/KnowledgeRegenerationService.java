package com.jh.knowledgeos.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.jh.knowledgeos.config.BatchIngestionExecutorConfig;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.mapper.PurgeMapper;

/**
 * Re-runs knowledge generation for a whole knowledge base.
 *
 * <p>Extraction normally happens once, as the last stage of ingesting a document. When the
 * extraction logic itself improves — or when a library was imported before structured
 * extraction worked — the cards and claims that already exist are the output of the older
 * logic, and nothing in the product re-derives them. This service walks every ready document
 * of a knowledge base and extracts again from the text already stored for it, so no source
 * file has to be uploaded a second time.</p>
 *
 * <p>It is deliberately a regeneration of <em>knowledge</em>, not a re-import: parsing,
 * chunking and evidence creation are not repeated, because the units they produced are still
 * the current ones.</p>
 */
@Service
public class KnowledgeRegenerationService {

    private final PurgeMapper purgeMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeExtractionService extraction;
    private final TaskCenterService tasks;
    private final ThreadPoolTaskExecutor executor;

    public KnowledgeRegenerationService(PurgeMapper purgeMapper, DocumentMapper documentMapper,
        KnowledgeExtractionService extraction, TaskCenterService tasks,
        @Qualifier(BatchIngestionExecutorConfig.BEAN_NAME) ThreadPoolTaskExecutor executor) {
        this.purgeMapper = purgeMapper;
        this.documentMapper = documentMapper;
        this.extraction = extraction;
        this.tasks = tasks;
        this.executor = executor;
    }

    /**
     * Submit a whole-library regeneration and return immediately with the task to watch.
     *
     * <p>The run calls the model once per document, so on a real library it lasts minutes — no
     * HTTP request should sit behind that, and worse, the operator is left staring at a button
     * with no idea whether it is stuck on document 3 of 200. The work is therefore registered as
     * a task first and executed on the batch pool, and the caller polls the task centre, where
     * every finished document updates the progress and the counters.</p>
     */
    public Map<String, Object> start(Long knowledgeBaseId, boolean purgeUnpublished) {
        int documentCount = documentMapper.listDocuments(knowledgeBaseId, null).size();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("purgeUnpublished", purgeUnpublished);
        payload.put("documentCount", documentCount);
        Map<String, Object> task = tasks.enqueue(knowledgeBaseId, TaskCenterService.TYPE_REGENERATION,
            "重新生成知识", "KNOWLEDGE_BASE", String.valueOf(knowledgeBaseId), TaskCenterService.TRIGGER_MANUAL, payload);
        final String taskCode = String.valueOf(task.get("taskCode"));
        final Long kb = knowledgeBaseId;
        final boolean purge = purgeUnpublished;
        // Regeneration is the same kind of work as a batch import — one AI call per document —
        // so it shares that pool and its concurrency bound rather than adding a second one.
        executor.execute(new Runnable() {
            @Override
            public void run() {
                runTracked(taskCode, kb, purge);
            }
        });
        Map<String, Object> output = new LinkedHashMap<String, Object>(task);
        output.put("documentCount", documentCount);
        return output;
    }

    /**
     * Run one submitted regeneration, recording the outcome on its task row whatever happens.
     */
    private void runTracked(String taskCode, Long knowledgeBaseId, boolean purgeUnpublished) {
        tasks.start(taskCode, "准备重新生成");
        try {
            tasks.succeed(taskCode, regenerate(knowledgeBaseId, purgeUnpublished, taskCode));
        } catch (Exception ex) {
            tasks.fail(taskCode, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Extract again from every document of one knowledge base.
     *
     * <p>One document failing must not abandon the rest: a library is regenerated to fix its
     * knowledge, and a single unusable document would otherwise leave the whole run looking
     * empty. Failures are counted and reported with the document they came from.</p>
     */
    public Map<String, Object> regenerate(Long knowledgeBaseId, boolean purgeUnpublished) {
        return regenerate(knowledgeBaseId, purgeUnpublished, null);
    }

    /**
     * @param taskCode task to report progress on, or {@code null} for a synchronous call that
     *     nobody is watching. Progress is what turns a minutes-long run into something an
     *     operator can see moving instead of a button that may as well be stuck.
     */
    public Map<String, Object> regenerate(Long knowledgeBaseId, boolean purgeUnpublished, String taskCode) {
        List<Map<String, Object>> documents = documentMapper.listDocuments(knowledgeBaseId, null);
        List<Map<String, Object>> details = new ArrayList<Map<String, Object>>();
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        int cardCount = 0;
        int claimCount = 0;

        if (purgeUnpublished) {
            // Only objects that were never published are removed. Anything a reviewer approved
            // or marked effective is human work and is left alone even though it will be
            // extracted again alongside it. Links go first: these tables have no foreign keys,
            // so dropping the parent rows alone would leave edges to codes that no longer exist.
            purgeMapper.purgeUnpublishedCardEvidence(knowledgeBaseId);
            purgeMapper.purgeUnpublishedCardEntities(knowledgeBaseId);
            purgeMapper.purgeUnpublishedClaimEvidence(knowledgeBaseId);
            purgeMapper.purgeUnpublishedClaimEntities(knowledgeBaseId);
            purgeMapper.purgeUnpublishedCards(knowledgeBaseId);
            purgeMapper.purgeUnpublishedClaims(knowledgeBaseId);
        }

        for (Map<String, Object> document : documents) {
            Long documentId = toLong(document.get("id"));
            if (documentId == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("documentId", documentId);
            row.put("title", document.get("title"));
            List<Map<String, Object>> units = documentMapper.listDocumentUnits(documentId);
            if (units.isEmpty()) {
                // A document that failed to parse has no units to extract from; re-running
                // extraction cannot fix that, and re-import is the right action instead.
                skipped++;
                row.put("status", "SKIPPED");
                row.put("message", "没有可抽取的内容单元");
                details.add(row);
                reportProgress(taskCode, documents.size(), processed, failed, skipped, cardCount, claimCount, row.get("title"));
                continue;
            }
            try {
                Map<String, Object> result = extraction.extract(buildPayload(knowledgeBaseId, documentId, units));
                int cards = size(result.get("cards"));
                int claims = size(result.get("claims"));
                cardCount += cards;
                claimCount += claims;
                processed++;
                row.put("status", "SUCCESS");
                row.put("cardCount", cards);
                row.put("claimCount", claims);
            } catch (Exception ex) {
                failed++;
                row.put("status", "FAILED");
                row.put("message", String.valueOf(ex.getMessage()));
            }
            details.add(row);
            reportProgress(taskCode, documents.size(), processed, failed, skipped, cardCount, claimCount, row.get("title"));
        }

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("knowledgeBaseId", knowledgeBaseId);
        output.put("documentCount", documents.size());
        output.put("processed", processed);
        output.put("skipped", skipped);
        output.put("failed", failed);
        output.put("cardCount", cardCount);
        output.put("claimCount", claimCount);
        output.put("documents", details);
        return output;
    }

    /**
     * Write how far a submitted run has got. The stage names the document being worked on, so a
     * run that is slow on one particular document says which, instead of the counter alone.
     */
    private void reportProgress(String taskCode, int documentCount, int processed, int failed, int skipped,
        int cardCount, int claimCount, Object title) {
        if (taskCode == null) {
            return;
        }
        int done = processed + failed + skipped;
        // 99 rather than 100: the row only reaches 100 when the task itself succeeds, so a
        // stalled run is never mistaken for a finished one.
        int progress = documentCount <= 0 ? 100 : Math.min(99, (int) Math.round(done * 100.0D / documentCount));
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("documentCount", documentCount);
        snapshot.put("processed", processed);
        snapshot.put("failed", failed);
        snapshot.put("skipped", skipped);
        snapshot.put("cardCount", cardCount);
        snapshot.put("claimCount", claimCount);
        snapshot.put("currentDocument", title == null ? "" : String.valueOf(title));
        tasks.checkpoint(taskCode, "正在重新生成：" + snapshot.get("currentDocument"), progress, snapshot);
    }

    /**
     * Build the extraction request for one document from the units already stored for it.
     *
     * <p>The extractor works on standard units rather than raw text, and resolves each unit to
     * its evidence code through the matching chunk, so both lists are sent: units carry the
     * text, chunks carry the provenance.</p>
     */
    private Map<String, Object> buildPayload(Long knowledgeBaseId, Long documentId, List<Map<String, Object>> units) {
        List<Map<String, Object>> sentUnits = new ArrayList<Map<String, Object>>(units.size());
        List<Map<String, Object>> sentChunks = new ArrayList<Map<String, Object>>(units.size());
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> unit : units) {
            Object unitId = unit.get("unitId");
            String content = String.valueOf(unit.get("content") == null ? "" : unit.get("content"));
            Map<String, Object> sentUnit = new LinkedHashMap<String, Object>();
            sentUnit.put("unit_id", unitId);
            sentUnit.put("content", content);
            sentUnit.put("heading_path", unit.get("headingPath"));
            sentUnit.put("block_type", unit.get("chunkType"));
            sentUnits.add(sentUnit);
            Map<String, Object> sentChunk = new LinkedHashMap<String, Object>();
            sentChunk.put("unit_id", unitId);
            sentChunk.put("evidence_code", unit.get("evidenceCode"));
            sentChunks.add(sentChunk);
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(content);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("documentId", documentId);
        payload.put("units", sentUnits);
        payload.put("chunks", sentChunks);
        payload.put("text", text.toString());
        return payload;
    }

    private static int size(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
