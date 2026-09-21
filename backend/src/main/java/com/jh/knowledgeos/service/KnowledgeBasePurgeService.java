package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clears every knowledge object of one knowledge base while keeping the workspace itself.
 *
 * <p>Re-ingesting a corpus is the normal way to recover from a bad import or a wrong model
 * configuration, and without a purge the operator has to wipe the database by hand. Deletion runs
 * in one transaction and is scoped to a single knowledge base, so a purge can neither leave a
 * half-cleared base behind nor touch the data of another workspace.</p>
 *
 * <p>Derived state outside MySQL — uploaded source bytes and the retrieval views — is cleaned up
 * after the transaction commits. Losing those best-effort steps only leaves stale index entries
 * that the next rebuild repairs, so they must never roll back an already consistent purge.</p>
 */
@Service
public class KnowledgeBasePurgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBasePurgeService.class);

    /**
     * Tables that carry {@code knowledge_base_id}, ordered so derived state is removed before the
     * objects it describes. The schema has no foreign keys, so the order documents intent rather
     * than satisfying a constraint.
     */
    private static final List<String> PURGE_TABLES = Collections.unmodifiableList(Arrays.asList("t_index_outbox", "t_index_outbox_archive", "t_index_schema_release", "t_index_schema_snapshot_audit", "t_index_acl_manifest", "t_index_graph_manifest", "t_index_graph_facet_manifest", "t_index_graph_evidence_manifest", "t_adjudication_assessment", "t_answer_evidence_assessment", "t_answer_qualification", "t_query_trace", "t_acl_access_audit", "t_acl_change", "t_acl_rebuild_job", "t_ingestion_run", "t_knowledge_version", "t_knowledge_conflict", "t_entity_acl_scope", "t_relation_acl_scope", "t_entity_facet", "t_relation_facet", "t_exact_evidence_binding", "t_entity_alias", "t_relation", "t_entity", "t_evidence", "t_claim", "t_knowledge_card", "t_source"));

    private final KnowledgeMapper mapper;

    private final SourceFileStorageService sourceFileStorage;

    private final AiClient aiClient;

    private final PlatformEventPublisher publisher;

    public KnowledgeBasePurgeService(KnowledgeMapper mapper, SourceFileStorageService sourceFileStorage, AiClient aiClient, PlatformEventPublisher publisher) {
        this.mapper = mapper;
        this.sourceFileStorage = sourceFileStorage;
        this.aiClient = aiClient;
        this.publisher = publisher;
    }

    /**
     * Cardinality of what a purge would remove, shown to the operator before confirmation.
     *
     * @return counts per object type; {@code total} is zero when the base is already empty
     */
    public Map<String, Object> preview(Long kb) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("dryRun", Boolean.TRUE);
        result.put("counts", counts(mapper.countPurgeable(kb)));
        return result;
    }

    /**
     * Delete all knowledge of one base inside a single transaction.
     *
     * @return removed cardinality plus the stored source-file paths, which the caller passes to
     *         {@link #cleanup(Long, List, boolean)} after the commit
     */
    @Transactional
    public Map<String, Object> purgeKnowledge(Long kb) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("deleted", counts(mapper.countPurgeable(kb)));
        // Paths must be read before t_source is deleted, otherwise the uploads they point at
        // become unreachable garbage on disk.
        result.put("sourceStoragePaths", new ArrayList<String>(mapper.listSourceStoragePaths(kb)));
        mapper.purgeIndexDeliveryAttempts(kb);
        mapper.purgeArchivedIndexDeliveryAttempts(kb);
        mapper.purgeAnswerClaimAssessments(kb);
        mapper.purgeIngestionEvents(kb);
        mapper.purgeEntityFacetEvidence(kb);
        mapper.purgeRelationFacetEvidence(kb);
        mapper.purgeKnowledgeEvidence(kb);
        mapper.purgeClaimEvidence(kb);
        mapper.purgeCardEntityLinks(kb);
        mapper.purgeClaimEntityLinks(kb);
        mapper.purgeReviews(kb);
        mapper.purgeContentUnits(kb);
        mapper.purgeDocuments(kb);
        mapper.purgeSourceVersions(kb);
        for (String table : PURGE_TABLES) {
            mapper.purgeKnowledgeBaseTable(table, kb);
        }
        // Purging a base is the most destructive action an operator can take, so it has to be
        // announced: subscribers holding materialized views or caches need to drop them.
        Map<String, Object> detail = new LinkedHashMap<String, Object>(result);
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_PURGED, kb, PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE", String.valueOf(kb), "清空知识库：" + kb, detail);
        return result;
    }

    /**
     * Remove what a purge leaves outside MySQL: the stored uploads and the retrieval views.
     *
     * <p>Both steps are best effort by design. The database is already committed when they run, so
     * an unavailable AI service or a locked file is reported instead of failing the purge.</p>
     */
    public Map<String, Object> cleanup(Long kb, List<String> storagePaths, boolean purgeRetrievalViews) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("removedSourceFiles", deleteSourceFiles(storagePaths));
        if (purgeRetrievalViews) {
            result.put("retrievalViews", purgeRetrievalViews(kb));
        }
        return result;
    }

    private int deleteSourceFiles(List<String> storagePaths) {
        if (storagePaths == null || storagePaths.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String storagePath : storagePaths) {
            Path file = sourceFileStorage.resolve(storagePath);
            if (file == null) {
                continue;
            }
            try {
                if (Files.deleteIfExists(file)) {
                    removed++;
                }
                pruneEmptyDirectories(file.getParent());
            } catch (Exception ex) {
                log.warn("purge could not remove source file path={} reason={}", storagePath, ex.getMessage());
            }
        }
        return removed;
    }

    /**
     * Remove the per-source and per-base folders once the last upload below them is gone.
     */
    private void pruneEmptyDirectories(Path directory) {
        Path current = directory;
        for (int level = 0; level < 2 && current != null; level++) {
            Path parent = current;
            try {
                if (Files.isDirectory(parent)) {
                    Files.deleteIfExists(parent);
                }
            } catch (Exception ex) {
                return;
            }
            current = parent.getParent();
        }
    }

    private Map<String, Object> purgeRetrievalViews(Long kb) {
        try {
            Map<String, Object> response = aiClient.delete(AiApiPaths.INDEX_PURGE_PREFIX + kb);
            return response == null ? Collections.singletonMap("error", (Object) "empty response") : response;
        } catch (Exception ex) {
            log.warn("purge could not clear retrieval views kb={} reason={}", kb, ex.getMessage());
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("purged", Boolean.FALSE);
            error.put("error", String.valueOf(ex.getMessage()));
            return error;
        }
    }

    /**
     * Normalize the count projection: MySQL returns {@code BigDecimal} for scalar sub-queries.
     */
    private Map<String, Object> counts(Map<String, Object> row) {
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        if (row != null) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = entry.getValue();
                counts.put(entry.getKey(), value instanceof Number ? ((Number) value).longValue() : value);
            }
        }
        long total = 0;
        for (Object value : counts.values()) {
            if (value instanceof Number) {
                total += ((Number) value).longValue();
            }
        }
        counts.put("total", total);
        return counts;
    }
}
