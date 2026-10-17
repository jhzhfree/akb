package com.jh.knowledgeos.service;
import com.jh.knowledgeos.common.AnswerFormat;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.domain.KnowledgeSource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
@Service
/** Thin compatibility facade delegating knowledge operations to focused application services. */
public class KnowledgeService {
    private final IngestionOrchestrator ingestionService;
    private final KnowledgeGenerationOrchestrator extractionService;
    private final KnowledgeQueryService queryService;
    private final KnowledgeGovernanceService governanceService;
    private final KnowledgeReadService readService;
    private final SourcePreviewService previewService;
    public KnowledgeService(IngestionOrchestrator ingestionService, KnowledgeGenerationOrchestrator extractionService, KnowledgeQueryService
        queryService, KnowledgeGovernanceService governanceService, KnowledgeReadService readService, SourcePreviewService previewService) {
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

    /**
     * Query delivered as events; see {@link KnowledgeQueryService#streamQuery}.
     */
    public void streamQuery(Map<String, Object> request, KnowledgeQueryService.QueryStreamListener listener) throws Exception {
        queryService.streamQuery(request, listener);
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
    public Map<String, Object> resolveConflict(Long id, String status, String resolution) {
        return governanceService.resolveConflict(id, status, resolution);
    }
    public Map<String, Object> conflictTargets(Long id) {
        return governanceService.conflictTargets(id);
    }
    public Map<String, Object> adjudicateConflict(Long id, String decision, String comment, String reviewer) {
        return governanceService.adjudicateConflict(id, decision, comment, reviewer);
    }
    public Map<String, Object> graph(Long kb, String code, java.util.List<String> permissionTags, Integer depth, Integer limit) {
        return readService.graph(kb, code, permissionTags, depth, limit);
    }
    public Map<String, Object> graphOverview(Long kb, Integer limit) {
        return readService.graphOverview(kb, limit);
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
    public List<Map<String, Object>> documents(Long kb, java.util.List<String> permissionTags) {
        return readService.documents(kb, permissionTags);
    }
    public Map<String, Object> retryDocument(Long documentId) throws Exception {
        return ingestionService.retry(documentId);
    }
    public List<Map<String, Object>> documentChunks(Long documentId) {
        return readService.documentChunks(documentId);
    }
    public Map<String, Object> lineage(Long kb, String objectType, String objectCode) {
        return readService.lineage(kb, objectType, objectCode);
    }
    public Map<String, Object> retrieve(Map<String, Object> request) throws Exception {
        Map<String, Object> response = queryService.query(request);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("queryId", response.get("queryId"));
        result.put("rewrittenQueries", response.get("rewrittenQueries"));
        result.put("router", response.get("router"));
        result.put("recall", response.get("recall"));
        result.put("candidates", response.get("candidates"));
        result.put("reranker", response.get("reranker"));
        result.put("fusion", response.get("fusion"));
        result.put("trace", response.get("trace"));
        return result;
    }
    public Map<String, Object> answer(Map<String, Object> request) throws Exception {
        Map<String, Object> response = queryService.query(request);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("queryId", response.get("queryId"));
        // 实际生效的交付格式要回传：界面上选的是用户点的，这里回的是服务端认下来的那个，
        // 传了认不出来的取值时二者不一致，前端据此如实显示。
        result.put("answerFormat", AnswerFormat.normalize(response.get("answerFormat") == null ? null
            : String.valueOf(response.get("answerFormat"))));
        result.put("answer", response.get("answer"));
        result.put("citations", response.get("citations"));
        result.put("confidence", response.get("confidence"));
        result.put("model", response.get("model"));
        result.put("trace", response.get("trace"));
        return result;
    }
    public Map<String, Object> sourcePreview(Long kb, Long sourceId, Integer limit, java.util.List<String> permissionTags) {
        return previewService.preview(kb, sourceId, limit, permissionTags);
    }
    public com.jh.knowledgeos.domain.SourceFile sourceFile(Long kb, Long sourceId, java.util.List<String> permissionTags) {
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
