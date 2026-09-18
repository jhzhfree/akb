package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.KnowledgeDocument;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service that parses uploads outside transactions and persists authoritative document state in short transactions.
 */
@Service
public class DocumentIngestionService {
    private static final double DEFAULT_AUTHORITY_SCORE = 0.80D;
    private static final int DB_BATCH_SIZE = 100;

    private final KnowledgeMapper mapper;
    private final AiClient aiClient;
    private final KnowledgeExtractionService extractionService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final TransactionRunner transactionRunner;
    private final IndexOutboxService outboxService;
    private final IngestionObservabilityService observabilityService;
    private final SourceFileStorageService sourceFileStorage;

    public DocumentIngestionService(
            KnowledgeMapper mapper,
            AiClient aiClient,
            KnowledgeExtractionService extractionService,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TransactionRunner transactionRunner,
            IndexOutboxService outboxService,
            IngestionObservabilityService observabilityService,
            SourceFileStorageService sourceFileStorage) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.extractionService = extractionService;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
        this.observabilityService = observabilityService;
        this.sourceFileStorage = sourceFileStorage;
    }

    /**
     * Parses an uploaded source outside the DB transaction, persists its authoritative
     * source/document/chunk/evidence state, enqueues indexing, then triggers extraction.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> upload(Long knowledgeBaseId, MultipartFile file, Double authorityScore, String permissionTags)
            throws Exception {
        com.jh.knowledgeos.domain.IngestionRun run =
                observabilityService.start(knowledgeBaseId, file.getOriginalFilename());
        try {
            observabilityService.event(run.getId(), "PARSE_STARTED", "PARSE", "RUNNING",
                    "开始解析文档", null);
            Map<String, Object> parsed = aiClient.parse(file);
            List<Map<String, Object>> parsedChunks =
                    parsed.get("chunks") instanceof List
                            ? (List<Map<String, Object>>) parsed.get("chunks")
                            : new ArrayList<Map<String, Object>>();
            // The parser returns one canonical model for every source format; chunks are the
            // normalized units of that model, already free of layout-only content.
            Map<String, Object> standard = parsed.get("standard") instanceof Map
                    ? (Map<String, Object>) parsed.get("standard")
                    : new LinkedHashMap<String, Object>();
            observabilityService.event(run.getId(), "PARSE_COMPLETED", "PARSE", "SUCCESS",
                    "文档解析完成，共 " + parsedChunks.size() + " 个标准单元", null);

            observabilityService.event(run.getId(), "PERSIST_STARTED", "PERSIST", "RUNNING",
                    "写入权威知识数据", null);
            IngestionState state = transactionRunner.required(() -> {
                try {
                    KnowledgeSource source = createSource(knowledgeBaseId, file, parsed, authorityScore, permissionTags);
                    KnowledgeDocument document = createDocument(source, file);
                    List<Map<String, Object>> indexChunks = persistChunks(
                            knowledgeBaseId, source, document, parsedChunks);
                    mapper.updateDocumentParsed(
                            document.getId(), String.valueOf(parsed.get("text")), indexChunks.size(), "PARSED");
                    Map<String, Object> indexEvent = outboxService.enqueue(
                            knowledgeBaseId, "DOCUMENT",
                            "KB:" + knowledgeBaseId + ":DOCUMENT:" + document.getId(),
                            "DOCUMENT_CHUNKS_UPSERT", AiApiPaths.INDEX_CHUNKS,
                            buildIndexReference(knowledgeBaseId, document),
                            "DOCUMENT_CHUNKS:" + document.getId() + ":1");
                    // The normalized standard document is stored as its own retrieval view: it is
                    // the material card generation reads from, so it is persisted in the same
                    // transaction that makes the document durable.
                    Map<String, Object> standardEvent = outboxService.enqueue(
                            knowledgeBaseId, "DOCUMENT",
                            "KB:" + knowledgeBaseId + ":STANDARD:" + document.getId(),
                            "DOCUMENT_STANDARD_UPSERT", AiApiPaths.INDEX_STANDARD_DOCUMENT,
                            buildStandardPayload(knowledgeBaseId, source, document, standard, parsedChunks),
                            "DOCUMENT_STANDARD:" + document.getId() + ":1");
                    return new IngestionState(source, document, indexChunks, indexEvent, standardEvent);
                } catch (Exception ex) {
                    throw new IllegalStateException("Persist parsed document failed", ex);
                }
            });
            observabilityService.event(run.getId(), "PERSIST_COMPLETED", "PERSIST", "SUCCESS",
                    "Source / Document / ContentUnit / Evidence 已保存", null);
            observabilityService.event(run.getId(), "INDEX_EVENT_CREATED", "INDEX", "SUCCESS",
                    "已创建 Transactional Outbox 索引事件", state.indexEvent);
            observabilityService.event(run.getId(), "STANDARD_EVENT_CREATED", "STANDARD", "SUCCESS",
                    "标准化数据已入队写入 Elasticsearch", state.standardEvent);
            observabilityService.event(run.getId(), "EXTRACTION_STARTED", "EXTRACT", "RUNNING",
                    "开始抽取 Knowledge Card / Claim / Entity / Relation", null);

            Map<String, Object> knowledgeResult = extractionService.extract(
                    buildExtractionPayload(knowledgeBaseId, state.source, state.document, parsed,
                            state.indexChunks, parsedChunks));
            int cardCount = resultCount(knowledgeResult, "cards");
            int claimCount = resultCount(knowledgeResult, "claims");
            observabilityService.event(run.getId(), "EXTRACTION_COMPLETED", "EXTRACT", "SUCCESS",
                    "候选知识抽取完成", knowledgeResult);
            observabilityService.complete(run.getId(), state.indexChunks.size(), cardCount, claimCount);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("runId", run.getId());
            result.put("runCode", run.getRunCode());
            result.put("sourceId", state.source.getId());
            result.put("documentId", state.document.getId());
            result.put("filename", file.getOriginalFilename());
            result.put("chunkCount", state.indexChunks.size());
            result.put("index", state.indexEvent);
            result.put("standardIndex", state.standardEvent);
            result.put("standard", standardSummary(standard));
            result.put("knowledge", knowledgeResult);
            return result;
        } catch (Exception ex) {
            observabilityService.fail(run.getId(), "FAILED", ex);
            throw ex;
        }
    }

    private int resultCount(Map<String, Object> result, String key) {
        Object value = result.get(key);
        if (value instanceof List) return ((List<?>) value).size();
        if (value instanceof Number) return ((Number) value).intValue();
        Object count = result.get(key + "Count");
        return count instanceof Number ? ((Number) count).intValue() : 0;
    }

    private KnowledgeSource createSource(
            Long knowledgeBaseId,
            MultipartFile file,
            Map<String, Object> parsed,
            Double authorityScore,
            String permissionTags) throws Exception {
        KnowledgeSource source = new KnowledgeSource();
        source.setSourceCode(idGenerator.next("SRC_"));
        source.setKnowledgeBaseId(knowledgeBaseId);
        source.setName(file.getOriginalFilename());
        source.setSourceType(String.valueOf(parsed.get("extension")));
        source.setSourceUri("upload://" + file.getOriginalFilename());
        // The original bytes are kept so the console can preview what was imported; a storage
        // problem only costs preview, so it never aborts an otherwise valid ingestion.
        source.setStoragePath(sourceFileStorage.store(
                knowledgeBaseId, source.getSourceCode(), file.getOriginalFilename(), file.getBytes()));
        source.setAuthorityScore(authorityScore == null ? DEFAULT_AUTHORITY_SCORE : authorityScore);
        source.setPermissionTags(normalizePermissionTags(permissionTags));
        source.setStatus("ACTIVE");
        mapper.insertSource(source);
        mapper.insertSourceVersion(
                source.getId(),
                1,
                String.valueOf(parsed.get("sha256")),
                source.getSourceUri());
        return source;
    }

    private KnowledgeDocument createDocument(KnowledgeSource source, MultipartFile file) {
        Long sourceVersionId = mapper.findSourceVersionId(source.getId(), 1);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setSourceVersionId(sourceVersionId);
        document.setDocumentCode(idGenerator.next("DOC_"));
        document.setTitle(file.getOriginalFilename());
        document.setMimeType(file.getContentType());
        document.setParseStatus("PARSING");
        mapper.insertDocument(document);
        return document;
    }

    private List<Map<String, Object>> persistChunks(
            Long knowledgeBaseId,
            KnowledgeSource source,
            KnowledgeDocument document,
            List<Map<String, Object>> parsedChunks) throws Exception {
        List<Map<String, Object>> indexChunks = new ArrayList<Map<String, Object>>(parsedChunks.size());

        for (int start = 0; start < parsedChunks.size(); start += DB_BATCH_SIZE) {
            int end = Math.min(start + DB_BATCH_SIZE, parsedChunks.size());
            List<Map<String, Object>> batch = parsedChunks.subList(start, end);
            persistChunkBatch(knowledgeBaseId, source, document, batch, indexChunks);
        }
        return indexChunks;
    }

    private void persistChunkBatch(
            Long knowledgeBaseId,
            KnowledgeSource source,
            KnowledgeDocument document,
            List<Map<String, Object>> batch,
            List<Map<String, Object>> output) throws Exception {
        List<Map<String, Object>> contentRows = new ArrayList<Map<String, Object>>(batch.size());
        List<String> unitCodes = new ArrayList<String>(batch.size());
        List<Map<String, Object>> copiedChunks = new ArrayList<Map<String, Object>>(batch.size());

        for (Map<String, Object> parsedChunk : batch) {
            Map<String, Object> chunk = new LinkedHashMap<String, Object>(parsedChunk);
            String unitCode = idGenerator.next("CU_");
            Integer pageNo = toInteger(chunk.get("page_no"));
            String headingPath = nullableString(chunk.get("heading_path"));
            String content = nullableString(chunk.get("content"));

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("documentId", document.getId());
            row.put("unitCode", unitCode);
            row.put("headingPath", headingPath);
            row.put("pageNo", pageNo);
            row.put("content", content);
            row.put("contentHash", nullableString(chunk.get("content_hash")));
            contentRows.add(row);
            unitCodes.add(unitCode);

            chunk.put("_unit_code", unitCode);
            copiedChunks.add(chunk);
        }

        mapper.batchInsertContentUnits(contentRows);
        List<Map<String, Object>> ids = mapper.findContentUnitIdsByCodes(unitCodes);
        Map<String, Long> idByCode = new LinkedHashMap<String, Long>();
        for (Map<String, Object> row : ids) {
            idByCode.put(
                    String.valueOf(row.get("unitCode")),
                    Long.valueOf(String.valueOf(row.get("id"))));
        }

        List<Map<String, Object>> evidenceRows = new ArrayList<Map<String, Object>>(copiedChunks.size());
        for (Map<String, Object> chunk : copiedChunks) {
            String unitCode = String.valueOf(chunk.remove("_unit_code"));
            Long contentUnitId = idByCode.get(unitCode);
            if (contentUnitId == null) {
                throw new IllegalStateException("ContentUnit ID not found for unitCode=" + unitCode);
            }

            Integer pageNo = toInteger(chunk.get("page_no"));
            String headingPath = nullableString(chunk.get("heading_path"));
            Map<String, Object> location = new LinkedHashMap<String, Object>();
            location.put("pageNo", pageNo);
            location.put("headingPath", headingPath);

            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            String evidenceCode = idGenerator.next("EVD_");
            evidence.put("evidenceCode", evidenceCode);
            evidence.put("knowledgeBaseId", knowledgeBaseId);
            evidence.put("contentUnitId", contentUnitId);
            evidence.put("sourceId", source.getId());
            evidence.put("quoteText", nullableString(chunk.get("content")));
            evidence.put("locationJson", objectMapper.writeValueAsString(location));
            evidence.put("authorityScore", source.getAuthorityScore());
            evidenceRows.add(evidence);

            chunk.put("content_unit_id", contentUnitId);
            chunk.put("evidence_code", evidenceCode);
            chunk.put("evidence_hash", nullableString(chunk.get("content_hash")));
            chunk.put("source_version", 1);
            output.add(chunk);
        }
        mapper.batchInsertEvidence(evidenceRows);
    }

    private Map<String, Object> buildIndexReference(Long kb, KnowledgeDocument document) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("documentId", document.getId());
        return payload;
    }

    private Map<String, Object> buildIndexPayload(
            Long kb,
            KnowledgeSource source,
            KnowledgeDocument document,
            List<Map<String, Object>> chunks) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("documentId", document.getId());
        payload.put("sourceName", source.getName());
        payload.put("sourceUri", source.getSourceUri());
        payload.put("chunks", chunks);
        payload.put("permissionTags", source.getPermissionTags());
        return payload;
    }

    /**
     * Payload for the standard-document store. Only document-level metadata is shipped
     * alongside the units: blocks are an intermediate artefact and would bloat the
     * outbox row without adding retrieval value.
     */
    private Map<String, Object> buildStandardPayload(
            Long kb,
            KnowledgeSource source,
            KnowledgeDocument document,
            Map<String, Object> standard,
            List<Map<String, Object>> units) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("documentId", document.getId());
        payload.put("sourceId", source.getId());
        payload.put("sourceName", source.getName());
        payload.put("sourceUri", source.getSourceUri());
        payload.put("units", units);
        payload.put("permissionTags", source.getPermissionTags());
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        for (String key : new String[] {"doc_id", "title", "source_format", "language", "sha256", "page_count"}) {
            metadata.put(key, standard.get(key));
        }
        metadata.put("stats", standard.get("stats"));
        payload.put("standard", metadata);
        return payload;
    }

    private Map<String, Object> buildExtractionPayload(
            Long kb,
            KnowledgeSource source,
            KnowledgeDocument document,
            Map<String, Object> parsed,
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> standardUnits) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("sourceId", source.getId());
        payload.put("documentId", document.getId());
        payload.put("text", parsed.get("text"));
        payload.put("chunks", chunks);
        // Extraction reads normalized units first; chunks only carry provenance.
        payload.put("units", standardUnits);
        payload.put("permissionTags", source.getPermissionTags());
        return payload;
    }

    /** Compact view of the canonical parse result surfaced to the ingestion UI. */
    private Map<String, Object> standardSummary(Map<String, Object> standard) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("docId", standard.get("doc_id"));
        summary.put("title", standard.get("title"));
        summary.put("sourceFormat", standard.get("source_format"));
        summary.put("language", standard.get("language"));
        summary.put("pageCount", standard.get("page_count"));
        summary.put("stats", standard.get("stats"));
        return summary;
    }

    private String normalizePermissionTags(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "[]";
        try {
            if (raw.trim().startsWith("[")) {
                List<?> values = objectMapper.readValue(raw, List.class);
                return objectMapper.writeValueAsString(values);
            }
            List<String> values = new ArrayList<String>();
            for (String value : raw.split(",")) {
                if (!value.trim().isEmpty()) values.add(value.trim());
            }
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid permissionTags", ex);
        }
    }

    private Integer toInteger(Object value) {
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    private String nullableString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
    private static final class IngestionState {
        private final KnowledgeSource source;
        private final KnowledgeDocument document;
        private final List<Map<String, Object>> indexChunks;
        private final Map<String, Object> indexEvent;
        private final Map<String, Object> standardEvent;

        private IngestionState(
                KnowledgeSource source,
                KnowledgeDocument document,
                List<Map<String, Object>> indexChunks,
                Map<String, Object> indexEvent,
                Map<String, Object> standardEvent) {
            this.source = source;
            this.document = document;
            this.indexChunks = indexChunks;
            this.indexEvent = indexEvent;
            this.standardEvent = standardEvent;
        }
    }

}
