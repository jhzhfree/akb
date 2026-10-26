package com.jh.knowledgeos.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in knowledge-base scenarios: what a library of a certain kind needs by default.
 *
 * <p>Retrieval used to be one deployment-wide policy, so an operations runbook and a policy
 * handbook in the same deployment were cut, enriched and answered the same way. The differences
 * are not cosmetic. A runbook is asked "how do I fix error 5003" and lives or dies on an exact
 * term plus the verbatim steps; a handbook is asked "does clause 7 override clause 4" and needs
 * versioned assertions and a quotable paragraph; a company directory is asked "who owns this"
 * and is mostly a graph walk. One strategy cannot serve all three, and asking an operator to
 * tune five channels before the first upload produces libraries nobody configures.</p>
 *
 * <p>Each scenario therefore ships the three decisions that differ, with the numbers a library
 * of that kind actually wants:</p>
 * <ul>
 *   <li>{@code generation} — how a document is cut into retrieval units and what is extracted
 *       from it (claims, entity resolution, conflict detection);</li>
 *   <li>{@code derivation} — whether a graph is built and which derivation rules are seeded;</li>
 *   <li>{@code query} — the default recall channels, their fusion weights, reranking, and the
 *       answer format a consumer of this library should get unless it asks otherwise.</li>
 * </ul>
 *
 * <p>The defaults below were measured, not guessed: each scenario was loaded with its own
 * corpus and graded (see {@code scripts/check-kb-scenario-templates.py}), and the numbers are
 * the ones that won on that corpus. Editing a library's profile overrides the template; a
 * library that never edits one keeps tracking its template, so a better default reaches it
 * later without a migration.</p>
 */
public final class KbScenarioTemplates {

    /** Generic library: no assumption about content is safer than a wrong one. */
    public static final String GENERAL = "GENERAL";

    /** Operations: runbooks, incidents, alarms, change records. */
    public static final String OPS = "OPS";

    /** Policies and clauses: regulations, internal rules, contracts. */
    public static final String POLICY = "POLICY";

    /** Company business and people: org structure, projects, ownership. */
    public static final String BUSINESS = "BUSINESS";

    /** Vulnerabilities: CVE records, affected versions, fixes. */
    public static final String VULN = "VULN";

    public static final String DEFAULT_SCENARIO = GENERAL;

    /** Recall channels a scenario may put in its default strategy, in the order they are tried. */
    public static final List<String> CHANNELS = Collections.unmodifiableList(Arrays.asList("bm25", "vector", "card", "claim", "graph"));

    /** Answer formats a library may default to; identical to the answer-time contract. */
    public static final List<String> ANSWER_FORMATS = Collections.unmodifiableList(Arrays.asList("default", "markdown", "text", "html"));

    private static final List<String> SCENARIO_CODES = Collections.unmodifiableList(Arrays.asList(GENERAL, OPS, POLICY, BUSINESS, VULN));

    private static final Map<String, Map<String, Object>> TEMPLATES = build();

    private KbScenarioTemplates() {
    }

    /**
     * The catalog shown when a library is created: what each scenario is for and what it does,
     * so choosing one is an informed choice and not a guess at a code word.
     */
    public static List<Map<String, Object>> catalog() {
        // 出厂默认从文件读；读不到就用内置常量，行为和加文件之前完全一致。
        List<Map<String, Object>> fromFile = DefaultCatalogLoader.scenarioTemplates();
        if (fromFile != null) {
            return fromFile;
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (String code : SCENARIO_CODES) {
            Map<String, Object> template = TEMPLATES.get(code);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("code", code);
            item.put("label", template.get("label"));
            item.put("summary", template.get("summary"));
            item.put("suitedFor", template.get("suitedFor"));
            item.put("docTypes", template.get("docTypes"));
            item.put("default", copy(template.get("default")));
            items.add(item);
        }
        return items;
    }

    /** True when the console may store the scenario under this code. */
    public static boolean known(String scenario) {
        return SCENARIO_CODES.contains(normalizeCode(scenario));
    }

    /** Unknown or blank scenario codes fall back to the generic template rather than failing. */
    public static String normalizeCode(String scenario) {
        String code = scenario == null ? "" : scenario.trim().toUpperCase(Locale.ROOT);
        return code.isEmpty() ? DEFAULT_SCENARIO : code;
    }

    /**
     * The default profile of a scenario: what a library of that kind runs until someone tunes it.
     */
    public static Map<String, Object> defaults(String scenario) {
        Map<String, Object> template = TEMPLATES.get(normalizeCode(scenario));
        return copy(template.get("default"));
    }

    /** Short human label of a scenario, used in lists and audit details. */
    public static String label(String scenario) {
        Map<String, Object> template = TEMPLATES.get(normalizeCode(scenario));
        return String.valueOf(template.get("label"));
    }

    /**
     * Everything the console needs to render the editor: the scenarios, the vocabulary and the
     * bounds of every number, so the limit a user sees is the limit the server enforces.
     */
    public static Map<String, Object> schema(List<Map<String, Object>> derivationPresets, List<String> relationTypes) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("scenarios", catalog());
        schema.put("channels", channels());
        schema.put("answerFormats", answerFormats());
        schema.put("chunk", chunkSchema());
        schema.put("derivation", derivationSchema(derivationPresets, relationTypes));
        schema.put("ranges", ranges());
        return schema;
    }

    private static List<Map<String, Object>> channels() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(vocabulary("bm25", "BM25 全文检索", "精确术语、错误码与编号命中，Elasticsearch 优先、本地词法回退。"));
        items.add(vocabulary("vector", "向量语义检索", "语义相似召回，Milvus 优先、本地向量回退。"));
        items.add(vocabulary("card", "知识卡片", "结构化知识卡片，适合 SOP、定义与操作步骤。"));
        items.add(vocabulary("claim", "断言/主张", "版本化断言，适合条件、适用范围与版本差异问题。"));
        items.add(vocabulary("graph", "知识图谱遍历", "图谱邻域与多跳遍历，适合关系、归属与影响面问题。"));
        return items;
    }

    private static List<Map<String, Object>> answerFormats() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(vocabulary("default", "模型原文", "按模型自然输出，不做格式包装。"));
        items.add(vocabulary("markdown", "Markdown（附引用章节）", "结构化 Markdown，附引用原文章节，便于核对出处。"));
        items.add(vocabulary("text", "纯文本", "不含证据与标记的纯文本，适合下游系统消费。"));
        items.add(vocabulary("html", "HTML（含引用原文）", "带引用原文的 HTML，适合直接嵌入页面。"));
        return items;
    }

    private static Map<String, Object> chunkSchema() {
        Map<String, Object> chunk = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> strategies = new ArrayList<Map<String, Object>>();
        strategies.add(vocabulary("SEMANTIC", "语义分段（默认）", "按语义块累积到窗口上限，表格与代码独立成块，遇到新章节即切分"));
        strategies.add(vocabulary("FIXED", "固定窗口滑窗", "按窗口在整篇正文上滑动并带真实重叠，适合无结构长文本"));
        strategies.add(vocabulary("SENTENCE", "句子聚合", "以句子为最小单位聚合到窗口，切分点始终落在句末"));
        strategies.add(vocabulary("STRUCTURE", "章节结构", "每个标题路径生成一块，仅在超长时硬切，适合手册与规程"));
        strategies.add(vocabulary("PARENT_CHILD", "父子分块", "先按章节生成父块，再生成窗口子块，检索命中子块、回答回看父块"));
        chunk.put("strategies", strategies);
        List<Map<String, Object>> measures = new ArrayList<Map<String, Object>>();
        measures.add(vocabulary("CHAR", "字符数", "按字符数计量窗口。"));
        measures.add(vocabulary("TOKEN", "估算 Token", "按估算 Token 计量窗口。"));
        chunk.put("measures", measures);
        return chunk;
    }

    private static Map<String, Object> derivationSchema(List<Map<String, Object>> derivationPresets, List<String> relationTypes) {
        Map<String, Object> derivation = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> presets = new ArrayList<Map<String, Object>>();
        if (derivationPresets != null) {
            for (Map<String, Object> preset : derivationPresets) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("code", preset.get("presetCode"));
                item.put("label", preset.get("name"));
                item.put("description", preset.get("description"));
                item.put("scene", preset.get("scene"));
                presets.add(item);
            }
        }
        derivation.put("presets", presets);
        derivation.put("relationTypes", relationTypes == null ? new ArrayList<String>() : relationTypes);
        return derivation;
    }

    private static Map<String, Object> ranges() {
        Map<String, Object> ranges = new LinkedHashMap<String, Object>();
        ranges.put("chunkSize", range(200, 4000));
        ranges.put("chunkOverlap", range(0, 1000));
        ranges.put("minChunkSize", range(0, 1000));
        ranges.put("weight", range(0.0D, 5.0D));
        ranges.put("recallMultiplier", range(1, 10));
        ranges.put("minRecallCandidates", range(1, 200));
        ranges.put("rerankTopN", range(0, 200));
        ranges.put("rrfK", range(1, 1000));
        ranges.put("minChunkEvidence", range(0, 20));
        ranges.put("contextMaxChars", range(500, 60000));
        return ranges;
    }

    private static Map<String, Object> range(double min, double max) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("min", min);
        item.put("max", max);
        return item;
    }

    private static Map<String, Object> vocabulary(String name, String label, String description) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("label", label);
        item.put("description", description);
        return item;
    }

    // ------------------------------------------------------------------ templates

    private static Map<String, Map<String, Object>> build() {
        Map<String, Map<String, Object>> templates = new LinkedHashMap<String, Map<String, Object>>();
        templates.put(GENERAL, template(GENERAL, "综合知识库", "内容杂、问法杂时的稳妥选择：各通道均衡参与，不偏向任何一种问法。",
            "资料类型不固定、既有说明也有流程的通用库",
            Arrays.asList("说明文档", "会议纪要", "零散资料"),
            profile(chunk("SEMANTIC", 900, 120, 120, "CHAR", true, true, false), extract(true, true, true),
                derivation(true, Arrays.asList("DEPLOY_CHAIN", "IMPACT_CHAIN", "OWNER_CHAIN", "CAUSE_CHAIN", "CLAIM_SENTENCE")),
                query(Arrays.asList("card", "vector", "bm25", "claim"), weights(1.15D, 1.15D, 1.10D, 1.10D, 1.05D),
                    3, 20, true, 0, 60, true, true, "default", 3, 8000))));
        templates.put(OPS, template(OPS, "运维知识库", "故障与操作问法为主：错误码靠精确命中，处置步骤要能原样引用，父子分块让步骤可单独命中。",
            "故障处置手册、巡检规程、变更与告警记录、SOP",
            Arrays.asList("故障处置手册", "巡检规程", "变更记录", "告警处置 SOP"),
            profile(chunk("STRUCTURE", 700, 90, 100, "CHAR", true, true, true), extract(true, true, true),
                derivation(true, Arrays.asList("DEPLOY_CHAIN", "IMPACT_CHAIN", "OWNER_CHAIN", "CAUSE_CHAIN", "CLAIM_SENTENCE")),
                query(Arrays.asList("bm25", "card", "vector"), weights(1.25D, 1.05D, 1.15D, 1.00D, 1.05D),
                    4, 24, true, 0, 40, true, true, "default", 4, 8000))));
        templates.put(POLICY, template(POLICY, "制度条款知识库", "条款与条件问法为主：断言优先、原文必须可引用，图谱参与少，答案默认附引用章节便于核对。",
            "规章制度、管理办法、合同条款、合规要求",
            Arrays.asList("规章制度", "管理办法", "合同条款", "合规要求"),
            // 制度以“条”为单位，条文通常一两百字。1100 的块会把好几条塞进一张卡片：检索命中
            // 整个块、引用定位不到具体条款，标题和断言还会从句子中间被切开——实测制度库里
            // 卡片标题出现「展AI开发工作」「等工作」，断言出现「和市场响 / 速度」这类残句，
            // 而同为 900 块的综合库标题是完整的。收到 600 让卡片回到条款级，重叠按比例降到
            // 80，下限 100 与运维场景对齐。
            profile(chunk("STRUCTURE", 600, 80, 100, "CHAR", true, true, true), extract(true, false, true),
                derivation(false, Arrays.asList("CLAIM_SENTENCE", "AI_CONCLUSION")),
                // 制度问法常常要跨好几条才答得全（“什么情况下可以调休”），召回倍数提到 4。
                query(Arrays.asList("claim", "card", "bm25"), weights(1.15D, 1.00D, 1.20D, 1.30D, 0.90D),
                    4, 20, true, 0, 60, true, true, "markdown", 5, 10000))));
        templates.put(BUSINESS, template(BUSINESS, "公司业务（人员关系）知识库", "关系与归属问法为主：图谱优先、实体消歧必开，短片段足以回答“谁负责什么”。",
            "组织架构、岗位与人员、项目与客户、产品归属",
            Arrays.asList("组织架构", "岗位说明", "项目档案", "客户与产品资料"),
            profile(chunk("SEMANTIC", 600, 100, 100, "CHAR", true, true, false), extract(true, true, false),
                derivation(true, Arrays.asList("DEPLOY_CHAIN", "OWNER_CHAIN", "CONTAINS_CHAIN", "RESOURCE_CHAIN", "CLAIM_SENTENCE",
                    "AI_CONCLUSION")),
                /**
                 * 图谱优先，但必须留一条文本通道：关系问法往往带专名（人名、项目名），
                 * 图谱只给实体路径、不给可引用的原文，bm25 缺席时证据门禁会拦掉整条答案。
                 * 图谱权重也从 1.4 降到 1.2：路径是补充证据，不该压过能被引用的片段。
                 */
                query(Arrays.asList("card", "vector", "bm25", "graph"), weights(1.15D, 1.15D, 1.20D, 1.00D, 1.20D),
                    4, 24, true, 0, 30, true, true, "default", 3, 8000))));
        templates.put(VULN, template(VULN, "漏洞库知识库", "编号与影响面问法并重：CVE 编号精确命中，影响版本与修复状态靠断言，图谱回答“影响到谁”。",
            "漏洞通报、CVE 明细、受影响版本与修复方案",
            Arrays.asList("漏洞通报", "CVE 明细", "组件清单", "修复方案"),
            profile(chunk("STRUCTURE", 900, 120, 120, "CHAR", true, true, false), extract(true, true, true),
                derivation(true, Arrays.asList("IMPACT_SCOPE", "IMPACT_CHAIN", "DEPLOY_CHAIN", "CAUSE_CHAIN", "CLAIM_SENTENCE")),
                query(Arrays.asList("bm25", "claim", "vector"), weights(1.30D, 1.15D, 1.00D, 1.25D, 1.10D),
                    4, 24, true, 0, 50, true, true, "markdown", 3, 9000))));
        // A template that builds no graph must not keep a graph channel switched on: an empty
        // channel spends recall budget on a walk that can never return anything.
        for (Map<String, Object> template : templates.values()) {
            Map<String, Object> defaults = section(template.get("default"));
            Map<String, Object> derivation = section(defaults.get("derivation"));
            if (!Boolean.TRUE.equals(derivation.get("graphEnabled"))) {
                Map<String, Object> query = section(defaults.get("query"));
                Map<String, Object> channels = section(query.get("channels"));
                Map<String, Object> graph = section(channels.get("graph"));
                graph.put("enabled", Boolean.FALSE);
            }
        }
        return templates;
    }

    private static Map<String, Object> template(String code, String label, String summary, String suitedFor, List<String> docTypes,
        Map<String, Object> defaults) {
        Map<String, Object> template = new LinkedHashMap<String, Object>();
        template.put("code", code);
        template.put("label", label);
        template.put("summary", summary);
        template.put("suitedFor", suitedFor);
        template.put("docTypes", docTypes);
        template.put("default", defaults);
        return template;
    }

    private static Map<String, Object> profile(Map<String, Object> chunk, Map<String, Object> extract, Map<String, Object> derivation,
        Map<String, Object> query) {
        Map<String, Object> generation = new LinkedHashMap<String, Object>();
        generation.put("chunk", chunk);
        generation.put("extract", extract);
        Map<String, Object> profile = new LinkedHashMap<String, Object>();
        profile.put("generation", generation);
        profile.put("derivation", derivation);
        profile.put("query", query);
        return profile;
    }

    private static Map<String, Object> chunk(String strategy, int chunkSize, int chunkOverlap, int minChunkSize, String measure,
        boolean keepStructure, boolean respectSentence, boolean parentChild) {
        Map<String, Object> chunk = new LinkedHashMap<String, Object>();
        chunk.put("strategy", strategy);
        chunk.put("chunkSize", chunkSize);
        chunk.put("chunkOverlap", chunkOverlap);
        chunk.put("minChunkSize", minChunkSize);
        chunk.put("measure", measure);
        chunk.put("keepStructure", keepStructure);
        chunk.put("respectSentence", respectSentence);
        chunk.put("parentChild", parentChild);
        return chunk;
    }

    private static Map<String, Object> extract(boolean claims, boolean entityResolution, boolean conflictDetection) {
        Map<String, Object> extract = new LinkedHashMap<String, Object>();
        extract.put("claims", claims);
        extract.put("entityResolution", entityResolution);
        extract.put("conflictDetection", conflictDetection);
        return extract;
    }

    private static Map<String, Object> derivation(boolean graphEnabled, List<String> presets) {
        Map<String, Object> derivation = new LinkedHashMap<String, Object>();
        derivation.put("graphEnabled", graphEnabled);
        derivation.put("presets", new ArrayList<String>(presets));
        return derivation;
    }

    private static Map<String, Object> query(List<String> defaultStrategies, Map<String, Object> channels, int recallMultiplier,
        int minRecallCandidates, boolean rerankEnabled, int rerankTopN, int rrfK, boolean qualitySignalsEnabled, boolean chunkBoostEnabled,
        String answerFormat, int minChunkEvidence, int contextMaxChars) {
        Map<String, Object> query = new LinkedHashMap<String, Object>();
        query.put("defaultStrategies", new ArrayList<String>(defaultStrategies));
        query.put("channels", channels);
        query.put("recallMultiplier", recallMultiplier);
        query.put("minRecallCandidates", minRecallCandidates);
        query.put("rerankEnabled", rerankEnabled);
        query.put("rerankTopN", rerankTopN);
        query.put("rrfK", rrfK);
        query.put("qualitySignalsEnabled", qualitySignalsEnabled);
        query.put("chunkBoostEnabled", chunkBoostEnabled);
        query.put("answerFormat", answerFormat);
        query.put("minChunkEvidence", minChunkEvidence);
        query.put("contextMaxChars", contextMaxChars);
        return query;
    }

    /** Channel weights in the fixed order {@link #CHANNELS}: bm25, vector, card, claim, graph. */
    private static Map<String, Object> weights(double bm25, double vector, double card, double claim, double graph) {
        Map<String, Object> channels = new LinkedHashMap<String, Object>();
        for (String name : CHANNELS) {
            double weight = "bm25".equals(name) ? bm25 : "vector".equals(name) ? vector : "card".equals(name) ? card
                : "claim".equals(name) ? claim : graph;
            Map<String, Object> channel = new LinkedHashMap<String, Object>();
            // Every channel starts enabled: a scenario expresses "less of this" through a lower
            // weight, not by switching it off, so a query the template did not foresee still
            // finds something. The exception is the graph, which a template turns off when the
            // library has no graph at all.
            channel.put("enabled", true);
            channel.put("weight", weight);
            channels.put(name, channel);
        }
        return channels;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Object source) {
        return source instanceof Map ? (Map<String, Object>) source : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Object source) {
        if (!(source instanceof Map)) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> typed = (Map<String, Object>) source;
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : typed.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                target.put(entry.getKey(), copy(value));
            } else if (value instanceof List) {
                target.put(entry.getKey(), new ArrayList<Object>((List<Object>) value));
            } else {
                target.put(entry.getKey(), value);
            }
        }
        return target;
    }
}
