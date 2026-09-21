package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.common.ScheduleExpression;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Knowledge derivation: producing knowledge that nobody wrote down.
 *
 * <p>A source document states that service A depends on B and that B is deployed in cluster C.
 * Nobody wrote that A therefore runs in C — but it follows, and it is exactly the kind of fact a
 * question asks for. Derivation makes that inference explicit: a rule walks the graph or applies a
 * template, and every result is stored as a <em>candidate</em> that keeps the assertions it came
 * from, the path it walked, the rule and algorithm version that produced it and the confidence it
 * deserves. Candidates are never silently mixed with sourced knowledge, and when a base assertion
 * moves the candidate is marked STALE rather than left quietly wrong.</p>
 */
@Service
public class KnowledgeDerivationService {
    public static final String TYPE_GRAPH_PATH = "GRAPH_PATH";
    public static final String TYPE_TEMPLATE = "TEMPLATE";
    public static final String TYPE_AI = "AI";

    public static final String SCHEDULE_MANUAL = "MANUAL";
    public static final String SCHEDULE_SCHEDULED = "SCHEDULED";

    public static final String DRAFT = "DRAFT";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String STALE = "STALE";

    public static final String ALGORITHM_VERSION = "derive-v1";

    /** How much confidence a two-hop inference keeps relative to its weakest link. */
    private static final double PATH_DECAY = 0.9;
    private static final double TEMPLATE_DECAY = 0.8;
    private static final int MAX_CANDIDATES_PER_RUN = 200;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KnowledgeMapper mapper;
    private final IdGenerator ids;
    private final ObjectMapper objectMapper;
    private final TaskCenterService tasks;
    private final PlatformEventPublisher publisher;
    private final AiClient aiClient;

    public KnowledgeDerivationService(KnowledgeMapper mapper, IdGenerator ids, ObjectMapper objectMapper,
            TaskCenterService tasks, PlatformEventPublisher publisher, AiClient aiClient) {
        this.mapper = mapper;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.tasks = tasks;
        this.publisher = publisher;
        this.aiClient = aiClient;
    }

    // ------------------------------------------------------------------ rules

    public List<Map<String, Object>> rules(Long kb) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> rule : mapper.listDerivationRules(kb)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(rule);
            row.put("scheduleText", describeSchedule(rule));
            rows.add(row);
        }
        return rows;
    }

    public Map<String, Object> createRule(Long kb, Map<String, Object> body) {
        String name = text(body.get("name")).trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("衍生规则名称不能为空");
        }
        String type = derivationType(body.get("derivationType"));
        String scheduleType = scheduleType(body.get("scheduleType"));
        String cron = null;
        String nextFireAt = null;
        if (SCHEDULE_SCHEDULED.equals(scheduleType)) {
            cron = text(body.get("cronExpr")).trim();
            if (cron.isEmpty()) {
                throw new IllegalArgumentException("计划衍生需要填写 cron 表达式");
            }
            nextFireAt = ScheduleExpression.of(cron).nextAfter(LocalDateTime.now()).format(TS);
        }
        String definitionJson = write(readDefinition(body.get("definition"), type));
        String ruleCode = ids.next("DR");
        mapper.insertDerivationRule(ruleCode, kb, name, type, scheduleType, cron, nextFireAt,
                write(body.get("sourceScope")), definitionJson,
                text(body.get("algorithmVersion")).isEmpty() ? ALGORITHM_VERSION
                        : text(body.get("algorithmVersion")),
                currentUser());
        return mapper.getDerivationRule(ruleCode);
    }

    public Map<String, Object> updateRule(String ruleCode, Map<String, Object> body) {
        Map<String, Object> existing = mapper.getDerivationRule(ruleCode);
        if (existing == null) {
            throw new IllegalArgumentException("衍生规则不存在：" + ruleCode);
        }
        Long kb = existing.get("knowledgeBaseId") == null ? null
                : Long.valueOf(text(existing.get("knowledgeBaseId")));
        String type = body.get("derivationType") == null ? text(existing.get("derivationType"))
                : derivationType(body.get("derivationType"));
        String scheduleType = scheduleType(body.get("scheduleType") == null ? existing.get("scheduleType")
                : body.get("scheduleType"));
        String cron = null;
        String nextFireAt = null;
        if (SCHEDULE_SCHEDULED.equals(scheduleType)) {
            cron = body.get("cronExpr") == null ? text(existing.get("cronExpr"))
                    : text(body.get("cronExpr")).trim();
            if (cron.isEmpty()) {
                throw new IllegalArgumentException("计划衍生需要填写 cron 表达式");
            }
            nextFireAt = ScheduleExpression.of(cron).nextAfter(LocalDateTime.now()).format(TS);
        }
        Object definition = body.get("definition") == null ? existing.get("definitionJson")
                : body.get("definition");
        mapper.updateDerivationRule(ruleCode,
                body.get("name") == null ? text(existing.get("name")) : text(body.get("name")).trim(),
                type, scheduleType, cron, nextFireAt, write(readScope(body.get("sourceScope"), existing)),
                write(readDefinition(definition, type)),
                text(body.get("algorithmVersion")).isEmpty() ? ALGORITHM_VERSION
                        : text(body.get("algorithmVersion")),
                body.get("enabled") == null ? toBoolean(existing.get("enabled"))
                        : toBoolean(body.get("enabled")));
        return mapper.getDerivationRule(ruleCode);
    }

    public void deleteRule(String ruleCode) {
        requireRule(ruleCode);
        mapper.deleteDerivationRule(ruleCode);
    }

    public Map<String, Object> rule(String ruleCode) {
        return requireRule(ruleCode);
    }

    public Map<String, Object> schema() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("derivationTypes", Arrays.asList(TYPE_GRAPH_PATH, TYPE_TEMPLATE, TYPE_AI));
        payload.put("schedules", Arrays.asList(SCHEDULE_MANUAL, SCHEDULE_SCHEDULED));
        payload.put("statuses", Arrays.asList(DRAFT, PENDING, APPROVED, REJECTED, STALE));
        payload.put("algorithmVersion", ALGORITHM_VERSION);
        payload.put("cronHint", "分 时 日 月 周，例如 0 3 * * 1 表示每周一 03:00");
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put(TYPE_GRAPH_PATH, graphPathDefinition());
        defaults.put(TYPE_TEMPLATE, templateDefinition());
        defaults.put(TYPE_AI, aiDefinition());
        payload.put("definitionDefaults", defaults);
        return payload;
    }

    // -------------------------------------------------------------------- run

    /**
     * Run one rule. Manual runs and scheduled runs take the same path: the only difference is who
     * asked, which the run record keeps so the console can tell an operator's run from a cron one.
     */
    public Map<String, Object> run(Long kb, String ruleCode, String triggerType) {
        Map<String, Object> rule = requireRule(ruleCode);
        Long scope = rule.get("knowledgeBaseId") == null ? kb
                : Long.valueOf(text(rule.get("knowledgeBaseId")));
        if (scope == null) {
            throw new IllegalArgumentException("衍生规则缺少知识库：无法执行");
        }
        String runCode = ids.next("DRUN");
        mapper.insertDerivationRun(runCode, scope, ruleCode,
                SCHEDULE_SCHEDULED.equalsIgnoreCase(text(triggerType)) ? "SCHEDULED" : "MANUAL");
        Map<String, Object> task = tasks.enqueue(scope, TaskCenterService.TYPE_DERIVATION,
                "知识衍生：" + text(rule.get("name")), "DERIVATION_RULE", ruleCode,
                SCHEDULE_SCHEDULED.equalsIgnoreCase(text(triggerType))
                        ? TaskCenterService.TRIGGER_SCHEDULED : TaskCenterService.TRIGGER_MANUAL,
                payloadOf(ruleCode, triggerType));
        String taskCode = text(task.get("taskCode"));
        tasks.start(taskCode, "SCAN");
        try {
            List<Map<String, Object>> produced = execute(scope, rule);
            int created = persist(scope, rule, produced);
            mapper.updateDerivationRun(runCode, "SUCCEEDED", produced.size(), created, 0, null,
                    LocalDateTime.now().format(TS));
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("runCode", runCode);
            result.put("scanned", produced.size());
            result.put("candidates", created);
            tasks.succeed(taskCode, result);
            mapper.updateDerivationRuleState(ruleCode, "IDLE", LocalDateTime.now().format(TS),
                    nextFireTime(rule), null);
            Map<String, Object> summary = new LinkedHashMap<String, Object>(result);
            summary.put("ruleCode", ruleCode);
            summary.put("triggerType", triggerType);
            publisher.emitSystem(PlatformEventTypes.KNOWLEDGE_CANDIDATE_CREATED, scope,
                    "DERIVATION_RUN", runCode,
                    "知识衍生完成：" + text(rule.get("name")) + "，产出 " + created + " 条候选", summary);
            return summary;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            mapper.updateDerivationRun(runCode, "FAILED", 0, 0, 0, message,
                    LocalDateTime.now().format(TS));
            mapper.updateDerivationRuleState(ruleCode, "FAILED", LocalDateTime.now().format(TS),
                    nextFireTime(rule), message);
            tasks.fail(taskCode, message);
            Map<String, Object> failure = new LinkedHashMap<String, Object>();
            failure.put("runCode", runCode);
            failure.put("ruleCode", ruleCode);
            failure.put("status", "FAILED");
            failure.put("errorMessage", message);
            throw e;
        }
    }

    /** The derivation itself: graph traversal, template rendering or a model call. */
    private List<Map<String, Object>> execute(Long kb, Map<String, Object> rule) {
        String type = derivationType(rule.get("derivationType"));
        Map<String, Object> definition = readDefinition(rule.get("definitionJson"), type);
        double minConfidence = toDouble(definition.get("minConfidence"), 0.5);
        int limit = Math.min(toInt(definition.get("limit"), 100), MAX_CANDIDATES_PER_RUN);
        if (TYPE_GRAPH_PATH.equals(type)) {
            String first = definition.get("firstType") == null ? null : text(definition.get("firstType"));
            String second = definition.get("secondType") == null ? null : text(definition.get("secondType"));
            List<Map<String, Object>> paths = mapper.listGraphPathPairs(kb, emptyToNull(first),
                    emptyToNull(second), minConfidence, limit);
            List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> path : paths) {
                Map<String, Object> candidate = new LinkedHashMap<String, Object>();
                double confidence = Math.min(toDouble(path.get("firstConfidence"), 0.5),
                        toDouble(path.get("secondConfidence"), 0.5)) * PATH_DECAY;
                String template = text(definition.get("template")).isEmpty()
                        ? "{head} 经由 {mid} 与 {tail} 相关" : text(definition.get("template"));
                candidate.put("title", render(template, path));
                candidate.put("content", "由图谱路径推导：" + text(path.get("headName")) + " -"
                        + text(path.get("firstType")) + "-> " + text(path.get("midName")) + " -"
                        + text(path.get("secondType")) + "-> " + text(path.get("tailName")));
                candidate.put("confidence", round(confidence));
                candidate.put("baseAssertionCodes",
                        Arrays.asList(text(path.get("firstRelationCode")), text(path.get("secondRelationCode"))));
                candidate.put("graphPath", Arrays.asList(
                        edge(path, "headCode", "firstType", "midCode"),
                        edge(path, "midCode", "secondType", "tailCode")));
                Map<String, Object> derivedFrom = new LinkedHashMap<String, Object>();
                derivedFrom.put("baseType", "RELATION");
                derivedFrom.put("relations", Arrays.asList(text(path.get("firstRelationCode")),
                        text(path.get("secondRelationCode"))));
                candidate.put("derivedFrom", derivedFrom);
                candidates.add(candidate);
            }
            return candidates;
        }
        if (TYPE_AI.equals(type)) {
            return deriveWithModel(kb, rule, definition, limit);
        }
        List<Map<String, Object>> claims = mapper.listDerivationSourceClaims(kb, minConfidence, limit);
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> claim : claims) {
            Map<String, Object> values = new LinkedHashMap<String, Object>(claim);
            values.put("subject", text(claim.get("subject")));
            values.put("predicate", text(claim.get("predicate")));
            values.put("object", text(claim.get("objectValue")));
            String template = text(definition.get("template")).isEmpty()
                    ? "{subject} 的 {predicate} 为 {object}" : text(definition.get("template"));
            Map<String, Object> candidate = new LinkedHashMap<String, Object>();
            candidate.put("title", render(template, values));
            candidate.put("content", "由断言模板推导：" + text(claim.get("subject")) + " "
                    + text(claim.get("predicate")) + " " + text(claim.get("objectValue")));
            candidate.put("confidence", round(toDouble(claim.get("confidence"), 0.5) * TEMPLATE_DECAY));
            candidate.put("baseAssertionCodes", Collections.singletonList(text(claim.get("claimCode"))));
            Map<String, Object> derivedFrom = new LinkedHashMap<String, Object>();
            derivedFrom.put("baseType", "CLAIM");
            derivedFrom.put("claims", Collections.singletonList(text(claim.get("claimCode"))));
            candidate.put("derivedFrom", derivedFrom);
            candidates.add(candidate);
        }
        return candidates;
    }

    /**
     * Model backed derivation. The model proposes, the platform disposes: whatever comes back is
     * stored as a candidate with its base assertions, never as sourced knowledge.
     */
    private List<Map<String, Object>> deriveWithModel(Long kb, Map<String, Object> rule,
            Map<String, Object> definition, int limit) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("knowledgeBaseId", kb);
        request.put("ruleCode", text(rule.get("ruleCode")));
        request.put("prompt", text(definition.get("prompt")));
        request.put("limit", limit);
        request.put("minConfidence", toDouble(definition.get("minConfidence"), 0.5));
        request.put("claims", mapper.listDerivationSourceClaims(kb,
                toDouble(definition.get("minConfidence"), 0.5), limit));
        Map<String, Object> response = aiClient.post(AiApiPaths.DERIVE, request);
        if (response == null) {
            throw new IllegalStateException("AI 服务未返回衍生结果");
        }
        List<Map<String, Object>> raw = new ArrayList<Map<String, Object>>();
        Object candidates = response.get("candidates");
        if (candidates instanceof List) {
            for (Object item : (List<?>) candidates) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) item;
                        raw.add(new LinkedHashMap<String, Object>(typed));
                }
            }
        }
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : raw) {
            Map<String, Object> candidate = new LinkedHashMap<String, Object>();
            candidate.put("title", text(item.get("title")));
            candidate.put("content", text(item.get("content")));
            candidate.put("confidence", round(toDouble(item.get("confidence"), 0.5)));
            List<String> bases = new ArrayList<String>();
            Object base = item.get("baseAssertionCodes");
            if (base instanceof List) {
                for (Object code : (List<?>) base) {
                    bases.add(text(code));
                }
            }
            candidate.put("baseAssertionCodes", bases);
            Map<String, Object> derivedFrom = new LinkedHashMap<String, Object>();
            derivedFrom.put("baseType", "CLAIM");
            derivedFrom.put("claims", bases);
            derivedFrom.put("model", true);
            candidate.put("derivedFrom", derivedFrom);
            if (!text(item.get("title")).isEmpty()) {
                results.add(candidate);
            }
        }
        if (results.isEmpty()) {
            throw new IllegalStateException("AI 服务未产出任何衍生候选");
        }
        return results;
    }

    private int persist(Long kb, Map<String, Object> rule, List<Map<String, Object>> candidates) {
        String ruleCode = text(rule.get("ruleCode"));
        String type = derivationType(rule.get("derivationType"));
        String version = text(rule.get("algorithmVersion")).isEmpty() ? ALGORITHM_VERSION
                : text(rule.get("algorithmVersion"));
        int created = 0;
        for (Map<String, Object> candidate : candidates) {
            String title = text(candidate.get("title"));
            String content = text(candidate.get("content"));
            if (title.isEmpty()) {
                continue;
            }
            String hash = sha256(ruleCode + "|" + title + "|" + content);
            Map<String, Object> existing = mapper.getDerivedCandidateByHash(ruleCode, hash);
            if (existing != null) {
                mapper.touchDerivedCandidate(ruleCode, hash,
                        toDouble(candidate.get("confidence"), 0.5), DRAFT);
                continue;
            }
            mapper.insertDerivedCandidate(ids.next("DK"), kb, ruleCode, type, version, title, content,
                    toDouble(candidate.get("confidence"), 0.5), write(candidate.get("baseAssertionCodes")),
                    write(candidate.get("graphPath")), write(candidate.get("evidence")),
                    write(candidate.get("derivedFrom")), write(candidate.get("permissionTags")),
                    toDouble(candidate.get("authorityScore"), 0.0), hash, currentUser());
            created++;
        }
        return created;
    }

    // --------------------------------------------------------------- candidates

    public List<Map<String, Object>> candidates(Long kb, String status, String ruleCode, Integer limit) {
        return mapper.listDerivedCandidates(kb, blank(status), blank(ruleCode),
                limit == null || limit <= 0 ? 50 : Math.min(limit, 200));
    }

    /**
     * One candidate with its provenance decoded. Stored JSON columns are returned as JSON too, so
     * a caller that wants to follow the evidence chain has to parse it — better to parse once here
     * and let the console render the base assertions and the graph path directly.
     */
    public Map<String, Object> candidate(String candidateCode) {
        Map<String, Object> candidate = mapper.getDerivedCandidate(candidateCode);
        if (candidate == null) {
            throw new IllegalArgumentException("衍生候选不存在：" + candidateCode);
        }
        Map<String, Object> decoded = new LinkedHashMap<String, Object>(candidate);
        decoded.put("baseAssertionCodes", asList(candidate.get("baseAssertionCodes")));
        decoded.put("graphPath", asObject(candidate.get("graphPath")));
        decoded.put("evidence", asObject(candidate.get("evidenceJson")));
        decoded.put("derivedFrom", asObject(candidate.get("derivedFromJson")));
        return decoded;
    }

    public Map<String, Object> stats(Long kb) {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        int total = 0;
        for (Map<String, Object> row : mapper.derivedCandidateStats(kb)) {
            int count = toInt(row.get("total"), 0);
            total += count;
            stats.put(text(row.get("status")).toLowerCase(), count);
        }
        stats.put("total", total);
        return stats;
    }

    /** Governance of a candidate: it becomes knowledge only when a human says so. */
    public Map<String, Object> review(String candidateCode, String decision, String reason) {
        String status = text(decision).trim().toUpperCase();
        if (!APPROVED.equals(status) && !REJECTED.equals(status)) {
            throw new IllegalArgumentException("衍生候选只能批准或驳回");
        }
        mapper.updateDerivedCandidateStatus(candidateCode, status, text(reason));
        return candidate(candidateCode);
    }

    /**
     * Mark candidates stale when their base assertions changed. Stale is not deletion: the
     * inference is still shown, but visibly out of date until the rule runs again.
     */
    public Map<String, Object> markStale(Long kb, List<String> assertionCodes) {
        if (kb == null || assertionCodes == null || assertionCodes.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("staleCount", 0);
            return empty;
        }
        // Callers may hand over either single codes or a JSON array of them; both mean the same.
        List<String> codes = new ArrayList<String>();
        for (String code : assertionCodes) {
            codes.addAll(asList(code));
        }
        if (codes.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<String, Object>();
            empty.put("staleCount", 0);
            return empty;
        }
        List<Map<String, Object>> affected = mapper.listCandidatesByBaseAssertion(kb, codes);
        String reason = "基础断言已变更：" + (codes.size() > 3
                ? codes.subList(0, 3) + " 等 " + codes.size() + " 项" : String.join("、", codes));
        for (Map<String, Object> candidate : affected) {
            mapper.updateDerivedCandidateStatus(text(candidate.get("candidateCode")), STALE, reason);
        }
        if (!affected.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("candidateCount", affected.size());
            detail.put("assertionCodes", codes.size() > 10 ? codes.subList(0, 10) : codes);
            publisher.emitSystem(PlatformEventTypes.KNOWLEDGE_DERIVED_STALE, kb, "DERIVATION",
                    null, affected.size() + " 条衍生候选因基础断言变更失效", detail);
        }
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        outcome.put("staleCount", affected.size());
        outcome.put("reason", reason);
        return outcome;
    }

    public List<Map<String, Object>> runs(Long kb, String ruleCode, Integer limit) {
        return mapper.listDerivationRuns(kb, blank(ruleCode),
                limit == null || limit <= 0 ? 30 : Math.min(limit, 100));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> requireRule(String ruleCode) {
        Map<String, Object> rule = mapper.getDerivationRule(ruleCode);
        if (rule == null) {
            throw new IllegalArgumentException("衍生规则不存在：" + ruleCode);
        }
        return rule;
    }

    private String nextFireTime(Map<String, Object> rule) {
        if (!SCHEDULE_SCHEDULED.equals(scheduleType(rule.get("scheduleType")))) {
            return null;
        }
        String cron = text(rule.get("cronExpr"));
        if (cron.isEmpty()) {
            return null;
        }
        return ScheduleExpression.of(cron).nextAfter(LocalDateTime.now()).format(TS);
    }

    private String describeSchedule(Map<String, Object> rule) {
        if (!SCHEDULE_SCHEDULED.equals(scheduleType(rule.get("scheduleType")))) {
            return "仅手动";
        }
        String cron = text(rule.get("cronExpr"));
        if (cron.isEmpty()) {
            return "计划未配置";
        }
        try {
            return ScheduleExpression.of(cron).describe();
        } catch (RuntimeException e) {
            return "计划表达式无效：" + cron;
        }
    }

    private Map<String, Object> graphPathDefinition() {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("firstType", "PART_OF");
        definition.put("secondType", "LOCATED_IN");
        definition.put("minConfidence", 0.6);
        definition.put("limit", 100);
        definition.put("template", "{head} 经由 {mid} 部署于 {tail}");
        return definition;
    }

    private Map<String, Object> templateDefinition() {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("minConfidence", 0.7);
        definition.put("limit", 100);
        definition.put("template", "{subject} 的 {predicate} 为 {object}");
        return definition;
    }

    private Map<String, Object> aiDefinition() {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put("minConfidence", 0.7);
        definition.put("limit", 50);
        definition.put("prompt", "从下列断言中归纳尚未直接写明的结论，只输出有把握的候选");
        return definition;
    }

    private Map<String, Object> readDefinition(Object raw, String type) {
        Map<String, Object> definition = asMap(raw);
        if (definition.isEmpty()) {
            if (TYPE_GRAPH_PATH.equals(type)) {
                return graphPathDefinition();
            }
            if (TYPE_AI.equals(type)) {
                return aiDefinition();
            }
            return templateDefinition();
        }
        return definition;
    }

    private Object readScope(Object raw, Map<String, Object> existing) {
        return raw == null ? existing.get("sourceScopeJson") : raw;
    }

    private Map<String, Object> payloadOf(String ruleCode, String triggerType) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("ruleCode", ruleCode);
        payload.put("triggerType", triggerType);
        return payload;
    }

    private Map<String, Object> edge(Map<String, Object> path, String from, String type, String to) {
        Map<String, Object> edge = new LinkedHashMap<String, Object>();
        edge.put("from", text(path.get(from)));
        edge.put("type", text(path.get(type)));
        edge.put("to", text(path.get(to)));
        return edge;
    }

    private String render(String template, Map<String, Object> values) {
        String result = template;
        result = result.replace("{head}", text(values.get("headName")));
        result = result.replace("{mid}", text(values.get("midName")));
        result = result.replace("{tail}", text(values.get("tailName")));
        result = result.replace("{subject}", text(values.get("subject")));
        result = result.replace("{predicate}", text(values.get("predicate")));
        result = result.replace("{object}", text(values.get("object")));
        return result;
    }

    private String derivationType(Object value) {
        String type = text(value).trim().toUpperCase(Locale.ROOT);
        if (TYPE_GRAPH_PATH.equals(type) || TYPE_TEMPLATE.equals(type) || TYPE_AI.equals(type)) {
            return type;
        }
        return TYPE_GRAPH_PATH;
    }

    private String scheduleType(Object value) {
        return SCHEDULE_SCHEDULED.equals(text(value).trim().toUpperCase(Locale.ROOT))
                ? SCHEDULE_SCHEDULED : SCHEDULE_MANUAL;
    }

    /** JSON columns come back as text; console and callers want the structure. */
    private Object asObject(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List || raw instanceof Map) {
            return raw;
        }
        String value = text(raw).trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            return value;
        }
    }

    private List<String> asList(Object raw) {
        Object parsed = asObject(raw);
        List<String> values = new ArrayList<String>();
        if (parsed instanceof List) {
            for (Object item : (List<?>) parsed) {
                String code = text(item).trim();
                if (!code.isEmpty()) {
                    values.add(code);
                }
            }
        } else if (parsed != null) {
            String code = text(parsed).trim();
            if (!code.isEmpty()) {
                values.add(code);
            }
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) raw);
        }
        if (raw == null) {
            return new LinkedHashMap<String, Object>();
        }
        String value = text(raw).trim();
        if (value.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("定义不是合法的 JSON：" + value);
        }
    }

    private String write(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? "{}" : text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String currentUser() {
        User user = AuthContext.get();
        return user == null ? "system" : user.getUsername();
    }

    private String blank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(value.hashCode());
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return true;
        }
        return "1".equals(text(value)) || Boolean.parseBoolean(text(value));
    }
}
