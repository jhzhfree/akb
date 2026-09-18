package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.domain.KnowledgeSource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Compatibility facade for the V1.1 controller surface.
 *
 * Business responsibilities live in focused application services. Keeping this
 * facade preserves the public controller contract while avoiding a monolithic
 * service that mixes ingestion, governance, retrieval and query tracing.
 */
@Service
public class KnowledgeService {
    private final DocumentIngestionService ingestionService;
    private final KnowledgeExtractionService extractionService;
    private final KnowledgeQueryService queryService;
    private final KnowledgeGovernanceService governanceService;
    private final KnowledgeReadService readService;
    private final SourcePreviewService previewService;

    public KnowledgeService(
            DocumentIngestionService ingestionService,
            KnowledgeExtractionService extractionService,
            KnowledgeQueryService queryService,
            KnowledgeGovernanceService governanceService,
            KnowledgeReadService readService,
            SourcePreviewService previewService) {
        this.ingestionService = ingestionService;
        this.extractionService = extractionService;
        this.queryService = queryService;
        this.governanceService = governanceService;
        this.readService = readService;
        this.previewService = previewService;
    }

    public Map<String, Object> upload(Long kb, MultipartFile file, Double authority, String permissionTags) throws Exception {
        return ingestionService.upload(kb, file, authority, permissionTags);
    }

    public Map<String, Object> extract(Map<String, Object> request) {
        return extractionService.extract(request);
    }

    public Map<String, Object> query(Map<String, Object> request) throws Exception {
        return queryService.query(request);
    }

    public Map<String, Object> adjudicate(Map<String, Object> request) {
        return governanceService.adjudicate(request);
    }

    public Map<String, Object> intelligentAdjudicate(Long kb) throws Exception {
        return governanceService.intelligentAdjudicate(kb);
    }

    public List<Map<String, Object>> assessments(Long kb, Integer limit) {
        return governanceService.assessments(kb, limit);
    }

    public List<Map<String, Object>> pendingReviews(Long kb) {
        return governanceService.pendingReviews(kb);
    }

    public List<Map<String, Object>> conflicts(Long kb, String status) {
        return governanceService.conflicts(kb, status);
    }

    public Map<String, Object> resolveConflict(Long id, String status) {
        return governanceService.resolveConflict(id, status);
    }

    public Map<String, Object> graph(Long kb, String code, java.util.List<String> permissionTags,
            Integer depth, Integer limit) {
        return readService.graph(kb, code, permissionTags, depth, limit);
    }

    public Map<String, Object> knowledgeDetail(Long kb, String type, String code, java.util.List<String> permissionTags) {
        return readService.knowledgeDetail(kb, type, code, permissionTags);
    }

    public Map<String, Object> capabilities() {
        return readService.capabilities();
    }

    public List<KnowledgeCard> cards(Long kb, java.util.List<String> permissionTags) {
        return readService.cards(kb, permissionTags);
    }

    public List<Claim> claims(Long kb, java.util.List<String> permissionTags) {
        return readService.claims(kb, permissionTags);
    }

    public List<KnowledgeSource> sources(Long kb, java.util.List<String> permissionTags) {
        return readService.sources(kb, permissionTags);
    }

    public List<Map<String, Object>> documents(Long kb) {
        return readService.documents(kb);
    }

    public Map<String, Object> sourcePreview(Long kb, Long sourceId, Integer limit,
            java.util.List<String> permissionTags) {
        return previewService.preview(kb, sourceId, limit, permissionTags);
    }

    public com.jh.knowledgeos.domain.SourceFile sourceFile(Long kb, Long sourceId,
            java.util.List<String> permissionTags) {
        return previewService.file(kb, sourceId, permissionTags);
    }

    public Map<String, Object> overview(Long kb, java.util.List<String> permissionTags) {
        return readService.overview(kb, permissionTags);
    }

    public Map<String, Object> answerQualification(String queryId) {
        return readService.answerQualification(queryId);
    }

    public Map<String, Object> answerQualificationSummary(Long kb, Integer hours) {
        return readService.answerQualificationSummary(kb, hours);
    }

    public Map<String, Object> contentUnitDetail(Long kb, Long contentUnitId, java.util.List<String> permissionTags) {
        return readService.contentUnitDetail(kb, contentUnitId, permissionTags);
    }

    public Map<String, Object> evidenceDetail(Long kb, String evidenceCode, java.util.List<String> permissionTags) {
        return readService.evidenceDetail(kb, evidenceCode, permissionTags);
    }

    public Map<String, Object> trace(String queryId) {
        return readService.trace(queryId);
    }

    public List<Map<String, Object>> queryHistory(Long kb, Integer limit) {
        return readService.queryHistory(kb, limit);
    }
}
