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

    public KnowledgeService(
            DocumentIngestionService ingestionService,
            KnowledgeExtractionService extractionService,
            KnowledgeQueryService queryService,
            KnowledgeGovernanceService governanceService,
            KnowledgeReadService readService) {
        this.ingestionService = ingestionService;
        this.extractionService = extractionService;
        this.queryService = queryService;
        this.governanceService = governanceService;
        this.readService = readService;
    }

    public Map<String, Object> upload(Long kb, MultipartFile file, Double authority) throws Exception {
        return ingestionService.upload(kb, file, authority);
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

    public Map<String, Object> graph(Long kb, String code) {
        return readService.graph(kb, code);
    }

    public Map<String, Object> capabilities() {
        return readService.capabilities();
    }

    public List<KnowledgeCard> cards(Long kb) {
        return readService.cards(kb);
    }

    public List<Claim> claims(Long kb) {
        return readService.claims(kb);
    }

    public List<KnowledgeSource> sources(Long kb) {
        return readService.sources(kb);
    }

    public List<Map<String, Object>> documents(Long kb) {
        return readService.documents(kb);
    }

    public Map<String, Object> trace(String queryId) {
        return readService.trace(queryId);
    }
}
