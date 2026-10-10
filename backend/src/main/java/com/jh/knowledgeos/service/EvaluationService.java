package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.EvaluationCase;
import com.jh.knowledgeos.domain.EvaluationDataset;
import com.jh.knowledgeos.domain.EvaluationResult;
import com.jh.knowledgeos.domain.EvaluationRun;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluation center (design §14): replay a graded question set against the live pipeline.
 *
 * <p>Retrieval quality used to be asserted by hand. Every run here replays the same cases through
 * the same pipeline the console uses, grades each case independently, and stores both the averages
 * and the per-case evidence — an average cannot answer "which question broke", and a run without
 * a baseline cannot answer "is this better".</p>
 */
@Service
public class EvaluationService {

    /**
     * Regression threshold on ranking metrics: below this the change is noise, not a regression.
     */
    private static final double METRIC_EPSILON = 0.01;

    /**
     * Latency regressions are noisy, so only a relative jump counts.
     */
    private static final double LATENCY_REGRESSION_RATIO = 0.25;

    private final KnowledgeMapper mapper;

    private final AiClient aiClient;

    private final IdGenerator idGenerator;

    private final PlatformEventPublisher events;

    private final ObjectMapper objectMapper;

    public EvaluationService(KnowledgeMapper mapper, AiClient aiClient, IdGenerator idGenerator, PlatformEventPublisher events, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.idGenerator = idGenerator;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> listDatasets(Long kb) {
        return mapper.listEvaluationDatasets(kb);
    }

    public Map<String, Object> createDataset(Long kb, Map<String, Object> body) {
        String name = string(body.get("name"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("数据集名称不能为空");
        }
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setDatasetCode(idGenerator.next("EDS_"));
        dataset.setKnowledgeBaseId(kb);
        dataset.setName(name);
        dataset.setDescription(string(body.get("description")));
        dataset.setVersion(defaultString(body.get("version"), "v1"));
        dataset.setStatus("ACTIVE");
        dataset.setCreatedBy(currentUser());
        mapper.insertEvaluationDataset(dataset);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("datasetCode", dataset.getDatasetCode());
        result.put("knowledgeBaseId", kb);
        result.put("name", dataset.getName());
        result.put("caseCount", 0);
        return result;
    }

    /**
     * Updates name / description / status; a dataset is never physically deleted once runs exist.
     */
    public int updateDataset(String datasetCode, Map<String, Object> body) {
        Map<String, Object> existing = mapper.getEvaluationDataset(datasetCode);
        if (existing == null) {
            throw new IllegalArgumentException("评测数据集不存在：" + datasetCode);
        }
        return mapper.updateEvaluationDataset(datasetCode, string(body.get("name")), string(body.get("description")),
            defaultString(body.get("status"), "ACTIVE"));
    }

    public List<Map<String, Object>> listCases(String datasetCode) {
        Map<String, Object> dataset = mapper.getEvaluationDataset(datasetCode);
        if (dataset == null) {
            throw new IllegalArgumentException("评测数据集不存在：" + datasetCode);
        }
        return mapper.listEvaluationCases(longValue(dataset.get("id")));
    }

    /**
     * 检索面板的示例问题：取当前知识库自己的测试语句，并按难度轮转挑选。
     *
     * <p>每个场景的提问方式差别很大——运维是命令与主机号，制度是天数与金额，研发是 issue 与工单号，
     * 用一套通用示例问题演示不出场景差异。同时按 L1/L2/L3 轮转取样，保证示例既覆盖字面检索，
     * 也覆盖口语化改写与多跳问题，而不是清一色最简单的那几条。</p>
     */
    public List<Map<String, Object>> sampleQuestions(Long kb, int limit) {
        int size = Math.min(Math.max(limit, 1), 12);
        List<Map<String, Object>> rows = mapper.listSampleQuestions(kb, 120);
        if (rows.isEmpty()) {
            return new ArrayList<Map<String, Object>>();
        }
        Map<String, List<Map<String, Object>>> byLevel = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Map<String, Object> row : rows) {
            String level = defaultString(row.get("difficulty"), "L1");
            List<Map<String, Object>> group = byLevel.get(level);
            if (group == null) {
                group = new ArrayList<Map<String, Object>>();
                byLevel.put(level, group);
            }
            group.add(row);
        }
        List<Map<String, Object>> picked = new ArrayList<Map<String, Object>>();
        int cursor = 0;
        while (picked.size() < size) {
            boolean progressed = false;
            for (List<Map<String, Object>> group : byLevel.values()) {
                if (cursor >= group.size()) {
                    continue;
                }
                picked.add(group.get(cursor));
                progressed = true;
                if (picked.size() >= size) {
                    break;
                }
            }
            if (!progressed) {
                break;
            }
            cursor++;
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : picked) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("question", string(row.get("question")));
            item.put("difficulty", defaultString(row.get("difficulty"), "L1"));
            item.put("expectedObjectCodes", readCodes(row.get("expectedObjectCodesJson")));
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> createCase(String datasetCode, Map<String, Object> body) {
        Map<String, Object> dataset = mapper.getEvaluationDataset(datasetCode);
        if (dataset == null) {
            throw new IllegalArgumentException("评测数据集不存在：" + datasetCode);
        }
        String question = string(body.get("question"));
        if (question.isEmpty()) {
            throw new IllegalArgumentException("评测问题不能为空");
        }
        List<String> expected = stringList(body.get("expectedObjectCodes"));
        if (expected.isEmpty()) {
            // An unanswerable grade is worse than no grade: a case without expected knowledge would
            // silently score zero forever and pollute every later average.
            throw new IllegalArgumentException("至少需要一个期望命中的知识对象 code");
        }
        EvaluationCase evaluationCase = new EvaluationCase();
        evaluationCase.setCaseCode(idGenerator.next("EC_"));
        evaluationCase.setDatasetId(longValue(dataset.get("id")));
        evaluationCase.setQuestion(question);
        evaluationCase.setExpectedObjectCodesJson(json(expected));
        evaluationCase.setExpectedEvidenceCodesJson(json(stringList(body.get("expectedEvidenceCodes"))));
        evaluationCase.setExpectedAnswer(string(body.get("expectedAnswer")));
        evaluationCase.setTagsJson(json(stringList(body.get("tags"))));
        evaluationCase.setDifficulty(string(body.get("difficulty")));
        evaluationCase.setVersion(defaultString(body.get("version"), "v1"));
        evaluationCase.setStatus("ACTIVE");
        mapper.insertEvaluationCase(evaluationCase);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("caseCode", evaluationCase.getCaseCode());
        result.put("datasetCode", datasetCode);
        result.put("question", question);
        result.put("expectedObjectCodes", expected);
        return result;
    }

    public int deleteCase(Long id) {
        return mapper.deleteEvaluationCase(id);
    }

    public List<Map<String, Object>> listRuns(Long kb, Long datasetId, Integer limit) {
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return mapper.listEvaluationRuns(kb, datasetId, safeLimit);
    }

    public Map<String, Object> runDetail(String runCode) {
        Map<String, Object> run = mapper.getEvaluationRun(runCode);
        if (run == null) {
            throw new IllegalArgumentException("评测运行不存在：" + runCode);
        }
        run.put("results", mapper.listEvaluationResults(longValue(run.get("id"))));
        if (run.get("baselineRunCode") != null) {
            Map<String, Object> baseline = mapper.getEvaluationRun(String.valueOf(run.get("baselineRunCode")));
            if (baseline != null) {
                baseline.remove("results");
                run.put("baseline", baseline);
            }
        }
        return run;
    }

    /**
     * Unanswered questions of one run: the gap list the knowledge owner has to close.
     */
    public Map<String, Object> gaps(String runCode) {
        Map<String, Object> run = mapper.getEvaluationRun(runCode);
        if (run == null) {
            throw new IllegalArgumentException("评测运行不存在：" + runCode);
        }
        List<Map<String, Object>> results = mapper.listEvaluationResults(longValue(run.get("id")));
        List<Map<String, Object>> gaps = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> result : results) {
            if (longValue(result.get("hitAt5")) > 0) {
                continue;
            }
            Map<String, Object> gap = new LinkedHashMap<String, Object>();
            gap.put("caseCode", result.get("caseCode"));
            gap.put("queryId", result.get("queryId"));
            gap.put("expectedCodes", result.get("expectedCodesJson"));
            gap.put("retrievedCodes", result.get("retrievedCodesJson"));
            gaps.add(gap);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runCode", runCode);
        payload.put("knowledgeBaseId", run.get("knowledgeBaseId"));
        payload.put("caseCount", run.get("caseCount"));
        payload.put("gapCount", gaps.size());
        payload.put("gaps", gaps);
        return payload;
    }

    /**
     * Replays every case of a dataset through the live pipeline and grades it.
     *
     * <p>The whole run is synchronous on purpose: the dataset is small, and an evaluation that
     * finishes half-written is worse than one that blocks — the caller needs the verdict, not a
     * job id to poll. A failed case is recorded as a failed case, never as a failed run.</p>
     */
    public Map<String, Object> run(Long kb, Map<String, Object> body) {
        String datasetCode = string(body.get("datasetCode"));
        Map<String, Object> dataset = mapper.getEvaluationDataset(datasetCode);
        if (dataset == null) {
            throw new IllegalArgumentException("评测数据集不存在：" + datasetCode);
        }
        if (!kb.equals(longValue(dataset.get("knowledgeBaseId")))) {
            throw new IllegalArgumentException("评测数据集不属于当前知识库");
        }
        List<Map<String, Object>> cases = mapper.listEvaluationCases(longValue(dataset.get("id")));
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("评测数据集没有可用用例：" + datasetCode);
        }
        boolean full = "FULL".equalsIgnoreCase(defaultString(body.get("mode"), "RETRIEVAL"));
        int topK = body.get("topK") == null ? 8 : Math.max(1, Math.min(intValue(body.get("topK")), 50));
        String baselineRunCode = string(body.get("baselineRunCode"));
        List<String> permissionTags = stringList(body.get("permissionTags"));
        String runCode = idGenerator.next("ER_");
        EvaluationRun run = new EvaluationRun();
        run.setRunCode(runCode);
        run.setDatasetId(longValue(dataset.get("id")));
        run.setKnowledgeBaseId(kb);
        run.setStatus("RUNNING");
        run.setMode(full ? "FULL" : "RETRIEVAL");
        run.setTopK(topK);
        run.setBaselineRunCode(baselineRunCode.isEmpty() ? null : baselineRunCode);
        run.setCaseCount(cases.size());
        mapper.insertEvaluationRun(run);
        try {
            List<EvaluationResult> results = new ArrayList<EvaluationResult>();
            List<Long> latencies = new ArrayList<Long>();
            String[] policyVersion = new String[1];
            for (Map<String, Object> evaluationCase : cases) {
                EvaluationResult result = gradeOne(evaluationCase, kb, topK, full, permissionTags, policyVersion);
                result.setRunId(run.getId());
                mapper.insertEvaluationResult(result);
                results.add(result);
                if (result.getLatencyMs() != null) {
                    latencies.add(Long.valueOf(result.getLatencyMs()));
                }
            }
            run.setStatus("SUCCESS");
            run.setCaseCount(results.size());
            run.setHitAt1Rate(averageHits(results, r -> r.getHitAt1()));
            run.setHitAt3Rate(averageHits(results, r -> r.getHitAt3()));
            run.setHitAt5Rate(averageHits(results, r -> r.getHitAt5()));
            run.setRecallAt5(average(results, r -> r.getRecallAt5()));
            run.setMrr(average(results, r -> r.getReciprocalRank()));
            run.setNdcgAt10(average(results, r -> r.getNdcgAt10()));
            run.setLatencyP50Ms(percentile(latencies, 50));
            run.setLatencyP95Ms(percentile(latencies, 95));
            run.setCitationAccuracy(average(results, r -> r.getCitationAccuracy()));
            run.setFaithfulness(average(results, r -> r.getFaithfulness()));
            run.setPolicyVersion(policyVersion[0]);
            run.setGapsJson(json(gapCodes(results)));
            applyBaseline(run, baselineRunCode);
            mapper.finishEvaluationRun(run);
            announce(kb, datasetCode, run, results);
            return runDetail(runCode);
        } catch (RuntimeException e) {
            mapper.failEvaluationRun(runCode, String.valueOf(e.getMessage()));
            throw e;
        }
    }

    /**
     * Grades one case: retrieval metrics always, citation and faithfulness only in FULL mode.
     */
    private EvaluationResult gradeOne(Map<String, Object> evaluationCase, Long kb, int topK, boolean full, List<String> permissionTags, String[]
        policyVersionHolder) {
        EvaluationResult result = new EvaluationResult();
        result.setCaseId(longValue(evaluationCase.get("id")));
        result.setCaseCode(String.valueOf(evaluationCase.get("caseCode")));
        List<String> expected = readCodes(evaluationCase.get("expectedObjectCodesJson"));
        result.setExpectedCodesJson(json(expected));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("query", evaluationCase.get("question"));
        payload.put("topK", topK);
        payload.put("permissionTags", permissionTags);
        long startedAt = System.currentTimeMillis();
        Map<String, Object> response;
        try {
            // RETRIEVAL mode grades recall latency; pulling the LLM into it would measure the
            // generator instead of the pipeline under test.
            response = aiClient.post(full ? AiApiPaths.QUERY : AiApiPaths.RETRIEVE, payload);
        } catch (RuntimeException e) {
            // One unreachable generation call must not erase the grading of the other cases.
            result.setStatus("FAILED");
            result.setLatencyMs((int) (System.currentTimeMillis() - startedAt));
            result.setErrorMessage(String.valueOf(e.getMessage()));
            result.setHitAt1(0);
            result.setHitAt3(0);
            result.setHitAt5(0);
            result.setReciprocalRank(0.0);
            result.setRecallAt5(0.0);
            result.setNdcgAt10(0.0);
            return result;
        }
        result.setLatencyMs((int) (System.currentTimeMillis() - startedAt));
        result.setQueryId(String.valueOf(response.get("queryId")));
        result.setStatus("SUCCESS");
        // Runs are only comparable under the same policy, so the version travels with the run.
        if (policyVersionHolder[0] == null) {
            policyVersionHolder[0] = firstNonBlank(traceValue(response, "answerPolicyVersion"), traceValue(response, "policyVersion"));
        }
        // Grading matches on every identity a candidate carries: retrieval returns content units,
        // while a case is written in terms of whatever the author can name — unit id, document,
        // evidence code or knowledge object code.
        List<Set<String>> retrieved = candidateAliases(response);
        result.setRetrievedCodesJson(json(retrievedCodes(response)));
        result.setHitAt1(hit(retrieved, expected, 1));
        result.setHitAt3(hit(retrieved, expected, 3));
        result.setHitAt5(hit(retrieved, expected, 5));
        result.setReciprocalRank(reciprocalRank(retrieved, expected));
        result.setRecallAt5(recallAt(retrieved, expected, 5));
        result.setNdcgAt10(ndcgAt(retrieved, expected, 10));
        if (full) {
            double[] citation = citationAccuracy(response, expected);
            if (citation[1] > 0) {
                result.setCitationTotal((int) citation[1]);
                result.setCitationHit((int) citation[0]);
                result.setCitationAccuracy(citation[0] / citation[1]);
            }
            Double faithfulness = faithfulness(response);
            if (faithfulness != null) {
                result.setFaithfulness(faithfulness);
            }
        }
        return result;
    }

    /**
     * Candidate identity is whichever code the pipeline stamped: object code, unit code or id.
     */
    private List<String> retrievedCodes(Map<String, Object> response) {
        List<String> codes = new ArrayList<String>();
        Object raw = response.get("candidates");
        if (!(raw instanceof List)) {
            return codes;
        }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> candidate = (Map<?, ?>) item;
            String code = firstNonBlank(candidate.get("objectCode"), candidate.get("unitCode"), candidate.get("claimCode"), candidate.get("id"));
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * Every identity one candidate can be referred to by (unit id, document, evidence, object).
     */
    private List<Set<String>> candidateAliases(Map<String, Object> response) {
        List<Set<String>> aliases = new ArrayList<Set<String>>();
        Object raw = response.get("candidates");
        if (!(raw instanceof List)) {
            return aliases;
        }
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> candidate = (Map<?, ?>) item;
            Object metadata = candidate.get("metadata");
            Map<?, ?> meta = metadata instanceof Map ? (Map<?, ?>) metadata : null;
            Set<String> identity = new LinkedHashSet<String>();
            addAlias(identity, candidate.get("objectCode"));
            addAlias(identity, candidate.get("unitCode"));
            addAlias(identity, candidate.get("id"));
            if (meta != null) {
                addAlias(identity, meta.get("content_unit_id"));
                addAlias(identity, meta.get("objectCode"));
                addAlias(identity, meta.get("evidence_code"));
                addAlias(identity, meta.get("source_name"));
            }
            aliases.add(identity);
        }
        return aliases;
    }

    private void addAlias(Set<String> identity, Object value) {
        String text = string(value);
        if (!text.isEmpty() && !"null".equals(text)) {
            identity.add(text);
        }
    }

    private int hit(List<Set<String>> retrieved, List<String> expected, int k) {
        Set<String> expectedSet = new LinkedHashSet<String>(expected);
        int limit = Math.min(k, retrieved.size());
        for (int i = 0; i < limit; i++) {
            if (intersects(retrieved.get(i), expectedSet)) {
                return 1;
            }
        }
        return 0;
    }

    private double reciprocalRank(List<Set<String>> retrieved, List<String> expected) {
        Set<String> expectedSet = new LinkedHashSet<String>(expected);
        for (int i = 0; i < retrieved.size(); i++) {
            if (intersects(retrieved.get(i), expectedSet)) {
                return round(1.0 / (i + 1));
            }
        }
        return 0.0;
    }

    private double recallAt(List<Set<String>> retrieved, List<String> expected, int k) {
        if (expected.isEmpty()) {
            return 0.0;
        }
        Set<String> expectedSet = new LinkedHashSet<String>(expected);
        int found = 0;
        int limit = Math.min(k, retrieved.size());
        for (int i = 0; i < limit; i++) {
            if (intersects(retrieved.get(i), expectedSet)) {
                found++;
            }
        }
        return round((double) found / expectedSet.size());
    }

    /**
     * Binary-relevance NDCG: every expected object is equally relevant, rank position is the gain.
     */
    private double ndcgAt(List<Set<String>> retrieved, List<String> expected, int k) {
        if (expected.isEmpty()) {
            return 0.0;
        }
        Set<String> expectedSet = new LinkedHashSet<String>(expected);
        double dcg = 0.0;
        int limit = Math.min(k, retrieved.size());
        for (int i = 0; i < limit; i++) {
            if (intersects(retrieved.get(i), expectedSet)) {
                dcg += 1.0 / Math.log(i + 2);
            }
        }
        double idcg = 0.0;
        int ideal = Math.min(k, expectedSet.size());
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0 / Math.log(i + 2);
        }
        return idcg == 0.0 ? 0.0 : round(dcg / idcg);
    }

    private boolean intersects(Set<String> identity, Set<String> expected) {
        for (String code : expected) {
            if (identity.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {hits, total} over the citations the answer actually shipped.
     */
    private double[] citationAccuracy(Map<String, Object> response, List<String> expected) {
        Set<String> expectedSet = new LinkedHashSet<String>(expected);
        Object raw = response.get("citations");
        if (!(raw instanceof List)) {
            return new double[] { 0.0, 0.0 };
        }
        double hit = 0.0;
        double total = 0.0;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> citation = (Map<?, ?>) item;
            total++;
            String code = firstNonBlank(citation.get("objectCode"), citation.get("objectId"), citation.get("evidenceCode"));
            if (code != null && expectedSet.contains(code)) {
                hit++;
            }
        }
        return new double[] { hit, total };
    }

    /**
     * Faithfulness comes from the claim ledger: the share of shipped claims the evidence supports.
     */
    private Double faithfulness(Map<String, Object> response) {
        Object qualification = response.get("answerQualification");
        if (!(qualification instanceof Map)) {
            return null;
        }
        Object claims = ((Map<?, ?>) qualification).get("claims");
        if (!(claims instanceof List) || ((List<?>) claims).isEmpty()) {
            return null;
        }
        double supported = 0.0;
        double total = 0.0;
        for (Object item : (List<?>) claims) {
            if (!(item instanceof Map)) {
                continue;
            }
            total++;
            Object decision = ((Map<?, ?>) item).get("decision");
            if (decision != null && String.valueOf(decision).toUpperCase().contains("SUPPORT")) {
                supported++;
            }
        }
        return total == 0.0 ? null : Double.valueOf(round(supported / total));
    }

    /**
     * Compares against a baseline run so "better" and "worse" are decided, not eyeballed.
     */
    private void applyBaseline(EvaluationRun run, String baselineRunCode) {
        if (baselineRunCode == null || baselineRunCode.isEmpty()) {
            return;
        }
        Map<String, Object> baseline = mapper.getEvaluationRun(baselineRunCode);
        if (baseline == null || !"SUCCESS".equals(String.valueOf(baseline.get("status")))) {
            return;
        }
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        delta.put("hitAt1Rate", delta(run.getHitAt1Rate(), number(baseline.get("hitAt1Rate"))));
        delta.put("mrr", delta(run.getMrr(), number(baseline.get("mrr"))));
        delta.put("recallAt5", delta(run.getRecallAt5(), number(baseline.get("recallAt5"))));
        delta.put("latencyP50Ms", run.getLatencyP50Ms() == null ? null : run.getLatencyP50Ms() - intValue(baseline.get("latencyP50Ms")));
        boolean regressed = worse(run.getHitAt1Rate(), number(baseline.get("hitAt1Rate"))) || worse(run.getMrr(), number(baseline.get("mrr"))) ||
            worse(run.getRecallAt5(), number(baseline.get("recallAt5")));
        Integer baselineLatency = baseline.get("latencyP50Ms") == null ? null : intValue(baseline.get("latencyP50Ms"));
        if (!regressed && run.getLatencyP50Ms() != null && baselineLatency != null && baselineLatency > 0) {
            regressed = run.getLatencyP50Ms() > baselineLatency * (1 + LATENCY_REGRESSION_RATIO);
        }
        boolean improved = better(run.getHitAt1Rate(), number(baseline.get("hitAt1Rate"))) && better(run.getMrr(), number(baseline.get("mrr")));
        run.setBaselineDeltaJson(json(delta));
        run.setRegressionVerdict(regressed ? "REGRESSED" : improved ? "IMPROVED" : "NEUTRAL");
    }

    private void announce(Long kb, String datasetCode, EvaluationRun run, List<EvaluationResult> results) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("runCode", run.getRunCode());
        detail.put("datasetCode", datasetCode);
        detail.put("mode", run.getMode());
        detail.put("caseCount", run.getCaseCount());
        detail.put("hitAt1Rate", run.getHitAt1Rate());
        detail.put("mrr", run.getMrr());
        detail.put("latencyP50Ms", run.getLatencyP50Ms());
        detail.put("verdict", run.getRegressionVerdict());
        events.emitSystem(PlatformEventTypes.EVALUATION_COMPLETED, kb, "EVALUATION_RUN", run.getRunCode(), "评测完成：Hit@1 " +
            percent(run.getHitAt1Rate()) + "，MRR " + percent(run.getMrr()), detail);
        List<String> missed = gapCodes(results);
        if (!missed.isEmpty()) {
            Map<String, Object> gapDetail = new LinkedHashMap<String, Object>(detail);
            gapDetail.put("missedCases", missed);
            events.emitSystem(PlatformEventTypes.KNOWLEDGE_GAP_DETECTED, kb, "EVALUATION_RUN", run.getRunCode(), "发现知识缺口：" + missed.size() +
                " 个问题未召回期望知识", gapDetail);
        }
    }

    private List<String> gapCodes(List<EvaluationResult> results) {
        List<String> missed = new ArrayList<String>();
        for (EvaluationResult result : results) {
            if (result.getHitAt5() == null || result.getHitAt5() == 0) {
                missed.add(result.getCaseCode());
            }
        }
        return missed;
    }

    /**
     * Reads a scalar from the trace block; absent diagnostics must not fail the run.
     */
    private Object traceValue(Map<String, Object> response, String key) {
        Object trace = response.get("trace");
        return trace instanceof Map ? ((Map<?, ?>) trace).get(key) : null;
    }

    private interface Metric {

        Double get(EvaluationResult result);
    }

    private interface HitMetric {

        Integer get(EvaluationResult result);
    }

    /**
     * Hit@K is a 0/1 flag per case, so its average is the share of cases answered at K.
     */
    private Double averageHits(List<EvaluationResult> results, HitMetric metric) {
        double sum = 0.0;
        int count = 0;
        for (EvaluationResult result : results) {
            Integer value = metric.get(result);
            if (value == null) {
                continue;
            }
            sum += value.intValue();
            count++;
        }
        return count == 0 ? null : Double.valueOf(round(sum / count));
    }

    /**
     * Averages over the values that exist: an ungraded metric must not be counted as zero.
     */
    private Double average(List<EvaluationResult> results, Metric metric) {
        double sum = 0.0;
        int count = 0;
        for (EvaluationResult result : results) {
            Double value = metric.get(result);
            if (value == null) {
                continue;
            }
            sum += value;
            count++;
        }
        return count == 0 ? null : Double.valueOf(round(sum / count));
    }

    /**
     * Nearest-rank percentile over the observed latencies.
     */
    private Integer percentile(List<Long> values, int pct) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * pct / 100.0) - 1;
        return Integer.valueOf(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))).intValue());
    }

    private double delta(Double current, Double baseline) {
        if (current == null || baseline == null) {
            return 0.0;
        }
        return round(current - baseline);
    }

    private boolean worse(Double current, Double baseline) {
        return current != null && baseline != null && (current - baseline) < -METRIC_EPSILON;
    }

    private boolean better(Double current, Double baseline) {
        return current != null && baseline != null && (current - baseline) > METRIC_EPSILON;
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private String percent(Double value) {
        return value == null ? "—" : String.format("%.1f%%", value * 100);
    }

    private Double number(Object value) {
        return value == null ? null : Double.valueOf(((Number) value).doubleValue());
    }

    private List<String> readCodes(Object json) {
        if (json == null) {
            return Collections.emptyList();
        }
        if (json instanceof List) {
            List<String> codes = new ArrayList<String>();
            for (Object item : (List<?>) json) {
                codes.add(String.valueOf(item));
            }
            return codes;
        }
        if (json instanceof String) {
            try {
                Object parsed = objectMapper.readValue(String.valueOf(json), Object.class);
                return parsed instanceof List ? readCodes(parsed) : Collections.emptyList();
            } catch (Exception ignored) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List) {
            List<String> values = new ArrayList<String>();
            for (Object item : (List<?>) value) {
                String text = string(item);
                if (!text.isEmpty()) {
                    values.add(text);
                }
            }
            return values;
        }
        String single = string(value);
        return single.isEmpty() ? Collections.emptyList() : Collections.singletonList(single);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String defaultString(Object value, String fallback) {
        String text = string(value);
        return text.isEmpty() ? fallback : text;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = string(value);
            if (!text.isEmpty() && !"null".equals(text)) {
                return text;
            }
        }
        return null;
    }

    private Long longValue(Object value) {
        return value == null ? null : Long.valueOf(((Number) value).longValue());
    }

    private int intValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String currentUser() {
        try {
            return AuthContext.get() == null ? "system" : String.valueOf(AuthContext.get().getUsername());
        } catch (Exception e) {
            return "system";
        }
    }
}
