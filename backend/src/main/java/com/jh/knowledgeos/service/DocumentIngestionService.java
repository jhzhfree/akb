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

    public DocumentIngestionService(
            KnowledgeMapper mapper,
            AiClient aiClient,
            KnowledgeExtractionService extractionService,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TransactionRunner transactionRunner,
            IndexOutboxService outboxService) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.extractionService = extractionService;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
    }

    /**
     * Parses an uploaded source outside the DB transaction, persists its authoritative
     * source/document/chunk/evidence state, enqueues indexing, then triggers extraction.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> upload(Long knowledgeBaseId, MultipartFile file, Double authorityScore)
            throws Exception {
        // Parse outside the database transaction: document parsing may be CPU heavy.
        Map<String, Object> parsed = aiClient.parse(file);
        List<Map<String, Object>> parsedChunks =
                parsed.get("chunks") instanceof List
                        ? (List<Map<String, Object>>) parsed.get("chunks")
                        : new ArrayList<Map<String, Object>>();

        IngestionState state = transactionRunner.required(() -> {
            try {
                KnowledgeSource source = createSource(knowledgeBaseId, file, parsed, authorityScore);
                KnowledgeDocument document = createDocument(source, file);
                List<Map<String, Object>> indexChunks = persistChunks(
                        knowledgeBaseId, source, document, parsedChunks);
                mapper.updateDocumentParsed(
                        document.getId(),
                        String.valueOf(parsed.get("text")),
                        indexChunks.size(),
                        "PARSED");
                Map<String, Object> indexEvent = outboxService.enqueue(
                        knowledgeBaseId,
                        "DOCUMENT",
                        "KB:" + knowledgeBaseId + ":DOCUMENT:" + document.getId(),
                        "DOCUMENT_CHUNKS_UPSERT",
                        AiApiPaths.INDEX_CHUNKS,
                        buildIndexReference(knowledgeBaseId, document),
                        "DOCUMENT_CHUNKS:" + document.getId() + ":1");
                return new IngestionState(source, document, indexChunks, indexEvent);
            } catch (Exception ex) {
                throw new IllegalStateException("Persist parsed document failed", ex);
            }
        });
        KnowledgeSource source = state.source;
        KnowledgeDocument document = state.document;
        List<Map<String, Object>> indexChunks = state.indexChunks;

        Map<String, Object> indexResult = state.indexEvent;
        Map<String, Object> knowledgeResult = extractionService.extract(
                buildExtractionPayload(knowledgeBaseId, source, document, parsed, indexChunks));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sourceId", source.getId());
        result.put("documentId", document.getId());
        result.put("filename", file.getOriginalFilename());
        result.put("chunkCount", indexChunks.size());
        result.put("index", indexResult);
        result.put("knowledge", knowledgeResult);
        return result;
    }

    private KnowledgeSource createSource(
            Long knowledgeBaseId,
            MultipartFile file,
            Map<String, Object> parsed,
            Double authorityScore) {
        KnowledgeSource source = new KnowledgeSource();
        source.setSourceCode(idGenerator.next("SRC_"));
        source.setKnowledgeBaseId(knowledgeBaseId);
        source.setName(file.getOriginalFilename());
        source.setSourceType(String.valueOf(parsed.get("extension")));
        source.setSourceUri("upload://" + file.getOriginalFilename());
        source.setAuthorityScore(authorityScore == null ? DEFAULT_AUTHORITY_SCORE : authorityScore);
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
            evidence.put("evidenceCode", idGenerator.next("EVD_"));
            evidence.put("knowledgeBaseId", knowledgeBaseId);
            evidence.put("contentUnitId", contentUnitId);
            evidence.put("sourceId", source.getId());
            evidence.put("quoteText", nullableString(chunk.get("content")));
            evidence.put("locationJson", objectMapper.writeValueAsString(location));
            evidence.put("authorityScore", source.getAuthorityScore());
            evidenceRows.add(evidence);

            chunk.put("content_unit_id", contentUnitId);
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
        return payload;
    }

    private Map<String, Object> buildExtractionPayload(
            Long kb,
            KnowledgeSource source,
            KnowledgeDocument document,
            Map<String, Object> parsed,
            List<Map<String, Object>> chunks) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("sourceId", source.getId());
        payload.put("documentId", document.getId());
        payload.put("text", parsed.get("text"));
        payload.put("chunks", chunks);
        return payload;
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

        private IngestionState(
                KnowledgeSource source,
                KnowledgeDocument document,
                List<Map<String, Object>> indexChunks,
                Map<String, Object> indexEvent) {
            this.source = source;
            this.document = document;
            this.indexChunks = indexChunks;
            this.indexEvent = indexEvent;
        }
    }

}
