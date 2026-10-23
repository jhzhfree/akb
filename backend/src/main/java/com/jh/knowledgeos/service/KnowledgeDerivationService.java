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

    /**
     * How much confidence a two-hop inference keeps relative to its weakest link.
     */
    private static final double PATH_DECAY = 0.9;

    private static final double TEMPLATE_DECAY = 0.8;

    private static final int MAX_CANDIDATES_PER_RUN = 200;

    /**
     * Relations read for one multi-hop walk. Large enough for a knowledge base of any practical
     * size, small enough that the walk stays a memory operation instead of a full table scan.
     */
    private static final int RELATION_SCAN_LIMIT = 5000;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KnowledgeMapper mapper;

    private final IdGenerator ids;

    private final ObjectMapper objectMapper;

    private final TaskCenterService tasks;

    private final PlatformEventPublisher publisher;

    private final AiClient aiClient;

    public KnowledgeDerivationService(KnowledgeMapper mapper, IdGenerator ids, ObjectMapper objectMapper, TaskCenterService tasks,
        PlatformEventPublisher publisher, AiClient aiClient) {
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
        Map<String, Object> preset = presetOf(body.get("presetCode"));
        String name = text(body.get("name")).trim();
        if (name.isEmpty() && preset != null) {
            name = text(preset.get("name"));
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("衍生规则名称不能为空");
        }
        String type = body.get("derivationType") == null && preset != null ? derivationType(preset.get("derivationType")) :
            derivationType(body.get("derivationType"));
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
        String definitionJson = write(definitionForWrite(kb, type, body.get("definition"), preset == null ? null : preset.get("definition")));
        String ruleCode = ids.next("DR");
        mapper.insertDerivationRule(ruleCode, kb, preset == null ? null : text(preset.get("presetCode")), name, type, scheduleType, cron, nextFireAt,
            write(body.get("sourceScope")), definitionJson, text(body.get("algorithmVersion")).isEmpty() ? ALGORITHM_VERSION :
            text(body.get("algorithmVersion")), currentUser());
        return mapper.getDerivationRule(ruleCode);
    }

    public Map<String, Object> updateRule(String ruleCode, Map<String, Object> body) {
        Map<String, Object> existing = mapper.getDerivationRule(ruleCode);
        if (existing == null) {
            throw new IllegalArgumentException("衍生规则不存在：" + ruleCode);
        }
        Long kb = existing.get("knowledgeBaseId") == null ? null : Long.valueOf(text(existing.get("knowledgeBaseId")));
        String presetCode = text(existing.get("presetCode"));
        // A preset's derivation type is what its definition is shaped around; changing one
        // without the other leaves a rule that cannot run. New type, new rule.
        if (!presetCode.isEmpty() && body.get("derivationType") != null &&
            !derivationType(body.get("derivationType")).equals(derivationType(existing.get("derivationType")))) {
            throw new IllegalArgumentException("预设规则的推导方式不可修改，请新建一条规则");
        }
        String type = body.get("derivationType") == null ? text(existing.get("derivationType")) : derivationType(body.get("derivationType"));
        String scheduleType = scheduleType(body.get("scheduleType") == null ? existing.get("scheduleType") : body.get("scheduleType"));
        String cron = null;
        String nextFireAt = null;
        if (SCHEDULE_SCHEDULED.equals(scheduleType)) {
            cron = body.get("cronExpr") == null ? text(existing.get("cronExpr")) : text(body.get("cronExpr")).trim();
            if (cron.isEmpty()) {
                throw new IllegalArgumentException("计划衍生需要填写 cron 表达式");
            }
            nextFireAt = ScheduleExpression.of(cron).nextAfter(LocalDateTime.now()).format(TS);
        }
        if (body.get("definition") != null) {
            // Editing one field must not silently reset the others: start from what the rule
            // already holds, overlay the submitted field, then check the whole thing.
            Map<String, Object> merged = asMap(existing.get("definitionJson"));
            merged.putAll(asMap(body.get("definition")));
            mapper.updateDerivationRule(ruleCode, body.get("name") == null ? text(existing.get("name")) : text(body.get("name")).trim(), type,
                scheduleType, cron, nextFireAt, write(readScope(body.get("sourceScope"), existing)), write(definitionForWrite(kb, type, merged,
                null)), text(body.get("algorithmVersion")).isEmpty() ? ALGORITHM_VERSION : text(body.get("algorithmVersion")), body.get("enabled") ==
                null ? toBoolean(existing.get("enabled")) : toBoolean(body.get("enabled")));
            return mapper.getDerivationRule(ruleCode);
        }
        mapper.updateDerivationRule(ruleCode, body.get("name") == null ? text(existing.get("name")) : text(body.get("name")).trim(), type,
            scheduleType, cron, nextFireAt, write(readScope(body.get("sourceScope"), existing)), write(existing.get("definitionJson")),
            text(body.get("algorithmVersion")).isEmpty() ? ALGORITHM_VERSION : text(body.get("algorithmVersion")), body.get("enabled") == null ?
            toBoolean(existing.get("enabled")) : toBoolean(body.get("enabled")));
        return mapper.getDerivationRule(ruleCode);
    }

    // ---------------------------------------------------------------- presets
    /**
     * The catalog with the knowledge base's own state attached: which presets are already
     * installed, and under which rule code, so "enable" is never a second copy.
     */
    public List<Map<String, Object>> presets(Long kb) {
        Map<String, String> installed = new LinkedHashMap<String, String>();
        if (kb != null) {
            for (Map<String, Object> rule : mapper.listDerivationRules(kb)) {
                String presetCode = text(rule.get("presetCode"));
                if (!presetCode.isEmpty()) {
                    installed.put(presetCode, text(rule.get("ruleCode")));
                }
            }
        }
        List<Map<String, Object>> presets = DerivationRuleCatalog.presets();
        for (Map<String, Object> preset : presets) {
            String presetCode = text(preset.get("presetCode"));
            preset.put("installed", installed.containsKey(presetCode));
            preset.put("ruleCode", installed.get(presetCode));
            preset.put("bounds", DerivationRuleCatalog.bounds(text(preset.get("derivationType")), relationTypes(kb)));
        }
        return presets;
    }

    /**
     * Install the presets a knowledge base does not have yet.
     *
     * <p>Idempotent by preset code: a second call adds nothing, so it is safe to run when a
     * knowledge base is created and again whenever an operator asks for the common rules.</p>
     */
    public Map<String, Object> seed(Long kb, List<String> presetCodes) {
        if (kb == null) {
            throw new IllegalArgumentException("缺少知识库：无法启用常用衍生规则");
        }
        List<Map<String, Object>> installed = new ArrayList<Map<String, Object>>();
        List<String> skipped = new ArrayList<String>();
        List<String> existing = mapper.listDerivationRulePresetCodes(kb) == null ? new ArrayList<String>() : mapper.listDerivationRulePresetCodes(kb);
        List<String> wanted = new ArrayList<String>();
        if (presetCodes != null) {
            for (String code : presetCodes) {
                String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
                if (!normalized.isEmpty() && !wanted.contains(normalized)) {
                    wanted.add(normalized);
                }
            }
        }
        List<Map<String, Object>> catalog = wanted.isEmpty() ? DerivationRuleCatalog.presets() : new ArrayList<Map<String, Object>>();
        for (String code : wanted) {
            Map<String, Object> preset = DerivationRuleCatalog.preset(code);
            if (preset == null) {
                throw new IllegalArgumentException("预设衍生规则不存在：" + code);
            }
            catalog.add(preset);
        }
        for (Map<String, Object> preset : catalog) {
            String presetCode = text(preset.get("presetCode"));
            if (existing.contains(presetCode)) {
                skipped.add(presetCode);
                continue;
            }
            installed.add(createFromPreset(kb, presetCode, null));
        }
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        outcome.put("created", installed.size());
        outcome.put("skipped", skipped);
        outcome.put("rules", installed);
        return outcome;
    }

    /**
     * One preset as a real rule: definition validated, manual trigger, enabled and runnable.
     */
    public Map<String, Object> createFromPreset(Long kb, String presetCode, Map<String, Object> overrides) {
        Map<String, Object> preset = presetOf(presetCode);
        if (preset == null) {
            throw new IllegalArgumentException("预设衍生规则不存在：" + presetCode);
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (overrides != null) {
            body.putAll(overrides);
        }
        body.put("presetCode", text(preset.get("presetCode")));
        if (text(body.get("name")).trim().isEmpty()) {
            body.put("name", text(preset.get("name")));
        }
        return createRule(kb, body);
    }

    /**
     * Put a preset rule back the way it shipped. Only the definition, the name and the type come
     * from the preset; the schedule and the switch stay as the operator left them, because
     * "restore the defaults" should not also reschedule or re-enable a rule on its own.
     */
    public Map<String, Object> resetRule(String ruleCode) {
        Map<String, Object> existing = requireRule(ruleCode);
        String presetCode = text(existing.get("presetCode"));
        if (presetCode.isEmpty()) {
            throw new IllegalArgumentException("自定义规则没有默认定义可恢复");
        }
        Map<String, Object> preset = DerivationRuleCatalog.preset(presetCode);
        if (preset == null) {
            throw new IllegalArgumentException("预设衍生规则已不存在：" + presetCode);
        }
        Long kb = existing.get("knowledgeBaseId") == null ? null : Long.valueOf(text(existing.get("knowledgeBaseId")));
        String type = derivationType(preset.get("derivationType"));
        mapper.resetDerivationRuleDefinition(ruleCode, text(preset.get("name")), type, write(definitionForWrite(kb, type, preset.get("definition"), null)));
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
        return schema(null);
    }

    /**
     * Everything the console needs to offer derivation without guessing: the types, the presets
     * that exist, and for every type the fields with the range each may be edited within.
     */
    public Map<String, Object> schema(Long kb) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("derivationTypes", Arrays.asList(TYPE_GRAPH_PATH, TYPE_TEMPLATE, TYPE_AI));
        payload.put("schedules", Arrays.asList(SCHEDULE_MANUAL, SCHEDULE_SCHEDULED));
        payload.put("statuses", Arrays.asList(DRAFT, PENDING, APPROVED, REJECTED, STALE));
        payload.put("algorithmVersion", ALGORITHM_VERSION);
        payload.put("cronHint", "分 时 日 月 周，例如 0 3 * * 1 表示每周一 03:00");
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put(TYPE_GRAPH_PATH, DerivationRuleCatalog.defaults(TYPE_GRAPH_PATH));
        defaults.put(TYPE_TEMPLATE, DerivationRuleCatalog.defaults(TYPE_TEMPLATE));
        defaults.put(TYPE_AI, DerivationRuleCatalog.defaults(TYPE_AI));
        payload.put("definitionDefaults", defaults);
        List<String> relationTypes = relationTypes(kb);
        Map<String, Object> bounds = new LinkedHashMap<String, Object>();
        bounds.put(TYPE_GRAPH_PATH, DerivationRuleCatalog.bounds(TYPE_GRAPH_PATH, relationTypes));
        bounds.put(TYPE_TEMPLATE, DerivationRuleCatalog.bounds(TYPE_TEMPLATE, relationTypes));
        bounds.put(TYPE_AI, DerivationRuleCatalog.bounds(TYPE_AI, relationTypes));
        payload.put("definitionBounds", bounds);
        payload.put("relationTypes", relationTypes);
        payload.put("editPolicy", DerivationRuleCatalog.editPolicy());
        payload.put("presets", presets(kb));
        return payload;
    }

    // -------------------------------------------------------------------- run
    /**
     * Run one rule. Manual runs and scheduled runs take the same path: the only difference is who
     * asked, which the run record keeps so the console can tell an operator's run from a cron one.
     */
    public Map<String, Object> run(Long kb, String ruleCode, String triggerType) {
        Map<String, Object> rule = requireRule(ruleCode);
        Long scope = rule.get("knowledgeBaseId") == null ? kb : Long.valueOf(text(rule.get("knowledgeBaseId")));
        if (scope == null) {
            throw new IllegalArgumentException("衍生规则缺少知识库：无法执行");
        }
        String runCode = ids.next("DRUN");
        mapper.insertDerivationRun(runCode, scope, ruleCode, SCHEDULE_SCHEDULED.equalsIgnoreCase(text(triggerType)) ? "SCHEDULED" : "MANUAL");
        Map<String, Object> task = tasks.enqueue(scope, TaskCenterService.TYPE_DERIVATION, "知识衍生：" + text(rule.get("name")), "DERIVATION_RULE",
            ruleCode, SCHEDULE_SCHEDULED.equalsIgnoreCase(text(triggerType)) ? TaskCenterService.TRIGGER_SCHEDULED : TaskCenterService.TRIGGER_MANUAL,
            payloadOf(ruleCode, triggerType));
        String taskCode = text(task.get("taskCode"));
        tasks.start(taskCode, "SCAN");
        try {
            List<Map<String, Object>> produced = execute(scope, rule);
            int created = persist(scope, rule, produced);
            mapper.updateDerivationRun(runCode, "SUCCEEDED", produced.size(), created, 0, null, LocalDateTime.now().format(TS));
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("runCode", runCode);
            result.put("scanned", produced.size());
            result.put("candidates", created);
            // 零产出必须自带解释：这正是要被追问的情形，干巴巴一个 scanned=0 等于让人去猜。
            if (produced.isEmpty()) {
                result.put("diagnosis", explainEmpty(scope, rule, triggerType));
            }
            tasks.succeed(taskCode, result);
            mapper.updateDerivationRuleState(ruleCode, "IDLE", LocalDateTime.now().format(TS), nextFireTime(rule), null);
            Map<String, Object> summary = new LinkedHashMap<String, Object>(result);
            summary.put("ruleCode", ruleCode);
            summary.put("triggerType", triggerType);
            publisher.emitSystem(PlatformEventTypes.KNOWLEDGE_CANDIDATE_CREATED, scope, "DERIVATION_RUN", runCode, "知识衍生完成：" + text(rule.get("name"))
                + "，产出 " + created + " 条候选", summary);
            return summary;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            mapper.updateDerivationRun(runCode, "FAILED", 0, 0, 0, message, LocalDateTime.now().format(TS));
            mapper.updateDerivationRuleState(ruleCode, "FAILED", LocalDateTime.now().format(TS), nextFireTime(rule), message);
            tasks.fail(taskCode, message);
            Map<String, Object> failure = new LinkedHashMap<String, Object>();
            failure.put("runCode", runCode);
            failure.put("ruleCode", ruleCode);
            failure.put("status", "FAILED");
            failure.put("errorMessage", message);
            throw e;
        }
    }

    /**
     * 零产出时说明为什么。
     *
     * <p>衍生建立在图谱之上，"产出 0"至少有三种完全不同的成因，处置方式也不一样：
     * 库里没有关系（先做实体与关系抽取）、关系接不成两跳（图的形状不支持这类推导）、
     * 关系质量达不到规则阈值（调低阈值或先提升关系质量）。把这三种混成一句"没有
     * 产出"，排查就只能一次一次改参数试。</p>
     */
    private Map<String, Object> explainEmpty(Long kb, Map<String, Object> rule, String triggerType) {
        Map<String, Object> definition = readDefinition(rule.get("definitionJson"), derivationType(rule.get("derivationType")));
        double minConfidence = toDouble(definition.get("minConfidence"), 0.5);
        Map<String, Object> diagnosis = new LinkedHashMap<String, Object>();
        diagnosis.put("minConfidence", round(minConfidence));
        Map<String, Object> stats = mapper.diagnoseGraph(kb, minConfidence);
        if (stats != null) {
            diagnosis.putAll(stats);
        }
        int relations = toInt(stats == null ? null : stats.get("relations"), 0);
        int twoHop = toInt(stats == null ? null : stats.get("twoHopPaths"), 0);
        int above = toInt(stats == null ? null : stats.get("twoHopAboveThreshold"), 0);
        int relationsAbove = toInt(stats == null ? null : stats.get("relationsAboveThreshold"), 0);
        double maxConfidence = toDouble(stats == null ? null : stats.get("maxConfidence"), 0.0D);
        String reason;
        if (relations == 0) {
            reason = "库里没有实体关系：衍生是在已有关系上做推导，得先有实体与关系";
        } else if (twoHop == 0) {
            reason = "关系首尾接不上，形不成 A→B→C 的两跳路径：单条关系不足以推导";
        } else if (relationsAbove == 0) {
            reason = "关系置信度最高 " + round(maxConfidence) + "，没有任何一条达到规则要求的 "
                + round(minConfidence) + "：先提升关系质量，或调低本规则的阈值";
        } else if (above == 0) {
            // 两跳要求两条关系都达标。单条最高值达标不代表能配成对——这正是最容易看错的
            // 地方：看到"最高 0.62 ≥ 阈值 0.6"就以为该有产出，其实达标的那几条接不上。
            reason = "有 " + relationsAbove + " 条关系达到阈值 " + round(minConfidence) + "，但库里的 "
                + twoHop + " 条两跳路径没有一条是两段都达标的：达标关系没能落在同一条路径上";
        } else {
            reason = "存在满足条件的两跳路径 " + above + " 条，零产出要看规则的类型与模板配置";
        }
        diagnosis.put("reason", reason);
        return diagnosis;
    }

    /**
     * The derivation itself: graph traversal, template rendering or a model call.
     */
    private List<Map<String, Object>> execute(Long kb, Map<String, Object> rule) {
        String type = derivationType(rule.get("derivationType"));
        Map<String, Object> definition = readDefinition(rule.get("definitionJson"), type);
        double minConfidence = toDouble(definition.get("minConfidence"), 0.5);
        int limit = Math.min(toInt(definition.get("limit"), 100), MAX_CANDIDATES_PER_RUN);
        if (TYPE_GRAPH_PATH.equals(type)) {
            // A path longer than two hops is how an impact chain is expressed: a vulnerability
            // reaches a customer only through component, product and project.
            List<DerivationRuleCatalog.PathHop> hops = DerivationRuleCatalog.parsePathTypes(
                text(definition.get(DerivationRuleCatalog.FIELD_PATH_TYPES)));
            if (!hops.isEmpty()) {
                return deriveFromPath(kb, hops, definition, minConfidence, limit);
            }
            String first = definition.get("firstType") == null ? null : text(definition.get("firstType"));
            String second = definition.get("secondType") == null ? null : text(definition.get("secondType"));
            List<Map<String, Object>> paths = mapper.listGraphPathPairs(kb, emptyToNull(first), emptyToNull(second), minConfidence, limit);
            List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> path : paths) {
                Map<String, Object> candidate = new LinkedHashMap<String, Object>();
                double confidence = Math.min(toDouble(path.get("firstConfidence"), 0.5), toDouble(path.get("secondConfidence"), 0.5)) * PATH_DECAY;
                String template = text(definition.get("template")).isEmpty() ? "{head} 经由 {mid} 与 {tail} 相关" : text(definition.get("template"));
                candidate.put("title", render(template, path));
                candidate.put("content", "由图谱路径推导：" + text(path.get("headName")) + " -" + text(path.get("firstType")) + "-> " +
                    text(path.get("midName")) + " -" + text(path.get("secondType")) + "-> " + text(path.get("tailName")));
                candidate.put("confidence", round(confidence));
                candidate.put("baseAssertionCodes", Arrays.asList(text(path.get("firstRelationCode")), text(path.get("secondRelationCode"))));
                candidate.put("graphPath", Arrays.asList(edge(path, "headCode", "firstType", "midCode"), edge(path, "midCode", "secondType", "tailCode")));
                Map<String, Object> derivedFrom = new LinkedHashMap<String, Object>();
                derivedFrom.put("baseType", "RELATION");
                derivedFrom.put("relations", Arrays.asList(text(path.get("firstRelationCode")), text(path.get("secondRelationCode"))));
                candidate.put("derivedFrom", derivedFrom);
                candidates.add(candidate);
            }
            return candidates;
        }
        if (TYPE_AI.equals(type)) {
            return deriveWithModel(kb, rule, definition, limit);
        }
        return deriveFromTemplateClaims(kb, definition, minConfidence, limit);
    }

    /**
     * Walk a multi-hop path over the authoritative graph and propose one candidate per path.
     *
     * <p>Two hops answer "where does this end up"; four hops answer "who does this vulnerability
     * reach". The walk reads the active relations once and expands in memory, because issuing a
     * query per hop per branch turns a knowledge base of any size into a table scan per run.</p>
     */
    private List<Map<String, Object>> deriveFromPath(Long kb, List<DerivationRuleCatalog.PathHop> hops, Map<String, Object> definition,
        double minConfidence, int limit) {
        List<Map<String, Object>> relations = mapper.listActiveRelations(kb, minConfidence, RELATION_SCAN_LIMIT);
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<String, List<Map<String, Object>>>();
        Map<String, String> nameByCode = new LinkedHashMap<String, String>();
        for (Map<String, Object> relation : relations) {
            List<Map<String, Object>> bucket = byType.get(text(relation.get("relationType")).toUpperCase(Locale.ROOT));
            if (bucket == null) {
                bucket = new ArrayList<Map<String, Object>>();
                byType.put(text(relation.get("relationType")).toUpperCase(Locale.ROOT), bucket);
            }
            bucket.add(relation);
            nameByCode.put(text(relation.get("sourceCode")), text(relation.get("sourceName")));
            nameByCode.put(text(relation.get("targetCode")), text(relation.get("targetName")));
        }
        List<Walk> frontier = new ArrayList<Walk>();
        DerivationRuleCatalog.PathHop first = hops.get(0);
        for (Map<String, Object> relation : bucket(byType, first.type())) {
            String from = first.reverse() ? text(relation.get("targetCode")) : text(relation.get("sourceCode"));
            String to = first.reverse() ? text(relation.get("sourceCode")) : text(relation.get("targetCode"));
            Walk walk = new Walk();
            walk.nodes.add(from);
            walk.nodes.add(to);
            walk.relations.add(text(relation.get("relationCode")));
            walk.types.add(text(relation.get("relationType")));
            walk.confidence = toDouble(relation.get("confidence"), 0.5);
            frontier.add(walk);
        }
        int budget = Math.max(limit * 20, 200);
        for (int hop = 1; hop < hops.size(); hop++) {
            DerivationRuleCatalog.PathHop spec = hops.get(hop);
            List<Map<String, Object>> bucket = bucket(byType, spec.type());
            List<Walk> next = new ArrayList<Walk>();
            for (Walk walk : frontier) {
                String current = walk.nodes.get(walk.nodes.size() - 1);
                for (Map<String, Object> relation : bucket) {
                    String to = spec.reverse() ? text(relation.get("sourceCode")) : text(relation.get("targetCode"));
                    String from = spec.reverse() ? text(relation.get("targetCode")) : text(relation.get("sourceCode"));
                    // Revisiting a node would report a cycle as a conclusion.
                    if (!from.equals(current) || walk.nodes.contains(to)) {
                        continue;
                    }
                    next.add(walk.extend(to, text(relation.get("relationCode")), text(relation.get("relationType")),
                        toDouble(relation.get("confidence"), 0.5)));
                    if (next.size() >= budget) {
                        break;
                    }
                }
                if (next.size() >= budget) {
                    break;
                }
            }
            frontier = next;
        }
        String template = text(definition.get("template")).isEmpty() ? "{head} 经 {mid} 与 {tail} 相关" : text(definition.get("template"));
        double decay = Math.pow(PATH_DECAY, Math.max(0, hops.size() - 1));
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        for (Walk walk : frontier) {
            String head = walk.nodes.get(0);
            String tail = walk.nodes.get(walk.nodes.size() - 1);
            if (head.isEmpty() || head.equals(tail)) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            values.put("headName", display(head, nameByCode));
            values.put("tailName", display(tail, nameByCode));
            List<String> mids = new ArrayList<String>();
            for (int i = 1; i < walk.nodes.size() - 1; i++) {
                mids.add(display(walk.nodes.get(i), nameByCode));
            }
            values.put("midName", String.join(" → ", mids));
            Map<String, Object> candidate = new LinkedHashMap<String, Object>();
            candidate.put("title", render(template, values));
            StringBuilder content = new StringBuilder("由图谱多跳路径推导：");
            for (int i = 0; i < walk.types.size(); i++) {
                if (i > 0) {
                    content.append(" → ");
                }
                content.append(display(walk.nodes.get(i), nameByCode)).append(" -").append(walk.types.get(i)).append("-> ")
                    .append(display(walk.nodes.get(i + 1), nameByCode));
            }
            candidate.put("content", content.toString());
            candidate.put("confidence", round(walk.confidence * decay));
            candidate.put("baseAssertionCodes", new ArrayList<String>(walk.relations));
            List<Map<String, String>> graphPath = new ArrayList<Map<String, String>>();
            for (int i = 0; i < walk.relations.size(); i++) {
                Map<String, String> edge = new LinkedHashMap<String, String>();
                edge.put("sourceCode", walk.nodes.get(i));
                edge.put("relationType", walk.types.get(i));
                edge.put("targetCode", walk.nodes.get(i + 1));
                edge.put("relationCode", walk.relations.get(i));
                graphPath.add(edge);
            }
            candidate.put("graphPath", graphPath);
            Map<String, Object> derivedFrom = new LinkedHashMap<String, Object>();
            derivedFrom.put("baseType", "RELATION");
            derivedFrom.put("relations", new ArrayList<String>(walk.relations));
            derivedFrom.put("hops", hops.size());
            candidate.put("derivedFrom", derivedFrom);
            candidates.add(candidate);
            if (candidates.size() >= limit) {
                break;
            }
        }
        return candidates;
    }

    private static List<Map<String, Object>> bucket(Map<String, List<Map<String, Object>>> byType, String type) {
        List<Map<String, Object>> bucket = byType.get(type);
        return bucket == null ? Collections.<Map<String, Object>>emptyList() : bucket;
    }

    private static String display(String code, Map<String, String> nameByCode) {
        String name = nameByCode.get(code);
        return name == null || name.isEmpty() ? code : name;
    }

    /** One walked path: the nodes it visited, the relations it travelled and its weakest link. */
    private static final class Walk {

        private final List<String> nodes = new ArrayList<String>();

        private final List<String> relations = new ArrayList<String>();

        private final List<String> types = new ArrayList<String>();

        private double confidence = 1.0D;

        private Walk extend(String node, String relationCode, String relationType, double confidence) {
            Walk next = new Walk();
            next.nodes.addAll(this.nodes);
            next.relations.addAll(this.relations);
            next.types.addAll(this.types);
            next.nodes.add(node);
            next.relations.add(relationCode);
            next.types.add(relationType);
            next.confidence = Math.min(this.confidence, confidence);
            return next;
        }
    }

    private List<Map<String, Object>> deriveFromTemplateClaims(Long kb, Map<String, Object> definition, double minConfidence, int limit) {
        List<Map<String, Object>> claims = mapper.listDerivationSourceClaims(kb, minConfidence, limit);
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> claim : claims) {
            Map<String, Object> values = new LinkedHashMap<String, Object>(claim);
            values.put("subject", text(claim.get("subject")));
            values.put("predicate", text(claim.get("predicate")));
            values.put("object", text(claim.get("objectValue")));
            String template = text(definition.get("template")).isEmpty() ? "{subject} 的 {predicate} 为 {object}" : text(definition.get("template"));
            Map<String, Object> candidate = new LinkedHashMap<String, Object>();
            candidate.put("title", render(template, values));
            candidate.put("content", "由断言模板推导：" + text(claim.get("subject")) + " " + text(claim.get("predicate")) + " " + text(claim.get("objectValue")));
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
    private List<Map<String, Object>> deriveWithModel(Long kb, Map<String, Object> rule, Map<String, Object> definition, int limit) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("knowledgeBaseId", kb);
        request.put("ruleCode", text(rule.get("ruleCode")));
        request.put("prompt", text(definition.get("prompt")));
        request.put("limit", limit);
        request.put("minConfidence", toDouble(definition.get("minConfidence"), 0.5));
        request.put("claims", mapper.listDerivationSourceClaims(kb, toDouble(definition.get("minConfidence"), 0.5), limit));
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
        String version = text(rule.get("algorithmVersion")).isEmpty() ? ALGORITHM_VERSION : text(rule.get("algorithmVersion"));
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
                mapper.touchDerivedCandidate(ruleCode, hash, toDouble(candidate.get("confidence"), 0.5), DRAFT);
                continue;
            }
            mapper.insertDerivedCandidate(ids.next("DK"), kb, ruleCode, type, version, title, content, toDouble(candidate.get("confidence"), 0.5),
                write(candidate.get("baseAssertionCodes")), write(candidate.get("graphPath")), write(candidate.get("evidence")),
                write(candidate.get("derivedFrom")), write(candidate.get("permissionTags")), toDouble(candidate.get("authorityScore"), 0.0), hash,
                currentUser());
            created++;
        }
        return created;
    }

    // --------------------------------------------------------------- candidates
    public List<Map<String, Object>> candidates(Long kb, String status, String ruleCode, Integer limit) {
        return mapper.listDerivedCandidates(kb, blank(status), blank(ruleCode), limit == null || limit <= 0 ? 50 : Math.min(limit, 200));
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

    /**
     * Governance of a candidate: it becomes knowledge only when a human says so.
     */
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
        String reason = "基础断言已变更：" + (codes.size() > 3 ? codes.subList(0, 3) + " 等 " + codes.size() + " 项" : String.join("、", codes));
        for (Map<String, Object> candidate : affected) {
            mapper.updateDerivedCandidateStatus(text(candidate.get("candidateCode")), STALE, reason);
        }
        if (!affected.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("candidateCount", affected.size());
            detail.put("assertionCodes", codes.size() > 10 ? codes.subList(0, 10) : codes);
            publisher.emitSystem(PlatformEventTypes.KNOWLEDGE_DERIVED_STALE, kb, "DERIVATION", null, affected.size() + " 条衍生候选因基础断言变更失效", detail);
        }
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        outcome.put("staleCount", affected.size());
        outcome.put("reason", reason);
        return outcome;
    }

    public List<Map<String, Object>> runs(Long kb, String ruleCode, Integer limit) {
        return mapper.listDerivationRuns(kb, blank(ruleCode), limit == null || limit <= 0 ? 30 : Math.min(limit, 100));
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

    /**
     * Reading a stored definition for execution: whatever is there is used as is, and an absent
     * one falls back to the type's defaults. Validating here would turn historical definitions
     * into runtime failures; validation belongs to the write path.
     */
    private Map<String, Object> readDefinition(Object raw, String type) {
        Map<String, Object> definition = asMap(raw);
        if (definition.isEmpty()) {
            return DerivationRuleCatalog.defaults(type);
        }
        return definition;
    }

    /**
     * A definition on its way into the database: filled from the preset or the type defaults,
     * overlaid with what was submitted, then checked against the bounds of its type.
     */
    private Map<String, Object> definitionForWrite(Long kb, String type, Object raw, Object presetDefinition) {
        Map<String, Object> base = new LinkedHashMap<String, Object>();
        if (presetDefinition != null) {
            base.putAll(asMap(presetDefinition));
        } else {
            base.putAll(DerivationRuleCatalog.defaults(type));
        }
        base.putAll(asMap(raw));
        return DerivationRuleCatalog.validate(type, base, relationTypes(kb));
    }

    private Map<String, Object> presetOf(Object presetCode) {
        String code = text(presetCode).trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            return null;
        }
        Map<String, Object> preset = DerivationRuleCatalog.preset(code);
        if (preset == null) {
            throw new IllegalArgumentException("预设衍生规则不存在：" + code);
        }
        return preset;
    }

    /**
     * The relation vocabulary a rule may walk: the platform defaults plus whatever this
     * knowledge base declared for itself.
     */
    public List<String> relationTypes(Long kb) {
        List<String> types = new ArrayList<String>();
        for (Map<String, Object> row : mapper.listSchemaTypes(kb == null ? Long.valueOf(0) : kb, KnowledgeSchemaService.KIND_RELATION)) {
            String code = text(row.get("typeCode"));
            if (!code.isEmpty() && !types.contains(code)) {
                types.add(code);
            }
        }
        return types;
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
        return SCHEDULE_SCHEDULED.equals(text(value).trim().toUpperCase(Locale.ROOT)) ? SCHEDULE_SCHEDULED : SCHEDULE_MANUAL;
    }

    /**
     * JSON columns come back as text; console and callers want the structure.
     */
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
