package com.jh.knowledgeos.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The derivation rules almost every knowledge base wants, and how far each one may be edited.
 *
 * <p>A console that answers "what should I derive?" with an empty form is not usable: the same
 * handful of inferences — where something ends up deployed, what a change ripples into, who
 * owns a component, what a chain of causes leads to — are the ones people actually ask for.
 * Those ship as presets with definitions that work against the default relation vocabulary, so
 * a knowledge base can run them the moment it has a graph, and then bend them: swap the two
 * relation types, raise the confidence floor, reword the sentence.</p>
 *
 * <p>Bending is bounded on purpose. A rule definition is executed against every relation and
 * claim in a knowledge base, so an unknown field, a confidence floor of 0 or a template that
 * interpolates a variable nobody fills in does not produce a slightly worse answer — it
 * produces silent nonsense. Every field therefore carries the range it may be edited within,
 * and {@link #validate} refuses anything outside it with the reason attached. Fields the
 * definition cannot carry are rejected rather than ignored: a typo that is quietly dropped
 * looks like a rule that works.</p>
 */
/** Catalog of built-in derivation rule definitions and defaults. */
public final class DerivationRuleCatalog {

    public static final String FIELD_FIRST_TYPE = "firstType";

    public static final String FIELD_SECOND_TYPE = "secondType";

    /**
     * Multi-hop path: {@code DEPENDS_ON>DEPLOYED_IN}, or {@code AFFECTS:REV>DEPENDS_ON:REV} when
     * the edge has to be walked against its direction. Two hops answer "where does this end up";
     * an impact chain such as "which customers does a CVE reach" needs four.
     */
    public static final String FIELD_PATH_TYPES = "pathTypes";

    public static final String PATH_REVERSE_SUFFIX = ":REV";

    public static final int PATH_TYPES_MIN = 4;

    public static final int PATH_TYPES_MAX = 200;

    public static final int PATH_HOPS_MIN = 2;

    public static final int PATH_HOPS_MAX = 5;

    public static final String FIELD_MIN_CONFIDENCE = "minConfidence";

    public static final String FIELD_LIMIT = "limit";

    public static final String FIELD_TEMPLATE = "template";

    public static final String FIELD_PROMPT = "prompt";

    /**
     * Below this a derivation runs on assertions nobody would stand behind; at 0.95 almost
     * nothing survives, and a rule that never fires is as useless as one that fires wrongly.
     */
    public static final double MIN_CONFIDENCE_MIN = 0.3;

    public static final double MIN_CONFIDENCE_MAX = 0.95;

    public static final int LIMIT_MIN = 1;

    public static final int LIMIT_MAX = 200;

    /**
     * A model-backed run costs one call per rule and is reviewed by a human, so it is capped
     * lower than a graph walk over the same knowledge base.
     */
    public static final int AI_LIMIT_MAX = 100;

    public static final int TEMPLATE_MIN = 4;

    public static final int TEMPLATE_MAX = 200;

    public static final int PROMPT_MIN = 6;

    public static final int PROMPT_MAX = 1000;

    /** Placeholders the graph renderer actually fills in. */
    public static final List<String> PATH_PLACEHOLDERS = Collections.unmodifiableList(Arrays.asList("{head}", "{mid}", "{tail}"));

    /** Placeholders the template renderer actually fills in. */
    public static final List<String> CLAIM_PLACEHOLDERS = Collections.unmodifiableList(Arrays.asList("{subject}", "{predicate}", "{object}"));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*\\}");

    private static final List<Map<String, Object>> PRESETS = buildPresets();

    private DerivationRuleCatalog() {
    }

    /**
     * The catalog: what the rule is for, and the definition it starts from.
     */
    public static List<Map<String, Object>> presets() {
        List<Map<String, Object>> copies = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> preset : PRESETS) {
            copies.add(copy(preset));
        }
        return copies;
    }

    public static Map<String, Object> preset(String presetCode) {
        String wanted = presetCode == null ? "" : presetCode.trim().toUpperCase(Locale.ROOT);
        for (Map<String, Object> preset : PRESETS) {
            if (wanted.equals(text(preset.get("presetCode")))) {
                return copy(preset);
            }
        }
        return null;
    }

    /**
     * Default definition for a derivation type; used when a rule is created without one.
     */
    public static Map<String, Object> defaults(String derivationType) {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        for (Object field : bounds(derivationType, Collections.<String>emptyList()).values()) {
            if (field instanceof Map) {
                Map<?, ?> typed = (Map<?, ?>) field;
                defaults.put(text(typed.get("field")), typed.get("default"));
            }
        }
        return defaults;
    }

    /**
     * How far each field may be edited: kind of value, range, default and what it means.
     *
     * <p>The console renders this as the form, so the limit a user sees is the limit the server
     * enforces — one definition of "how much may I change" instead of two that drift apart.</p>
     */
    public static Map<String, Object> bounds(String derivationType, List<String> relationTypes) {
        Map<String, Object> bounds = new LinkedHashMap<String, Object>();
        if (KnowledgeDerivationService.TYPE_GRAPH_PATH.equals(derivationType)) {
            bounds.put(FIELD_FIRST_TYPE, enumField(FIELD_FIRST_TYPE, "第一跳关系", relationTypes, "PART_OF", "规则先走这条关系：起点 → 中间节点"));
            bounds.put(FIELD_SECOND_TYPE, enumField(FIELD_SECOND_TYPE, "第二跳关系", relationTypes, "LOCATED_IN", "再走这条关系：中间节点 → 终点"));
            bounds.put(FIELD_PATH_TYPES, stringField(FIELD_PATH_TYPES, "多跳路径", PATH_TYPES_MIN, PATH_TYPES_MAX, "",
                null, "需要三跳及以上时填写，用 > 连接关系类型，反向加 :REV，例如 AFFECTS:REV>DEPENDS_ON:REV>BELONGS_TO；"
                    + "留空则使用上面两跳关系"));
            bounds.put(FIELD_MIN_CONFIDENCE, numberField(FIELD_MIN_CONFIDENCE, "最低置信度", MIN_CONFIDENCE_MIN, MIN_CONFIDENCE_MAX, 0.05, 0.6,
                "两跳都低于该值的路径不推导，推导结果再按链路衰减"));
            bounds.put(FIELD_LIMIT, numberField(FIELD_LIMIT, "单次最多推导", LIMIT_MIN, LIMIT_MAX, 10, 100, "一次运行产出的候选上限，防止一次跑遍全库"));
            bounds.put(FIELD_TEMPLATE, stringField(FIELD_TEMPLATE, "输出模板", TEMPLATE_MIN, TEMPLATE_MAX, "{head} 经由 {mid} 部署于 {tail}",
                PATH_PLACEHOLDERS, "候选标题的句式，可用占位符：" + String.join(" ", PATH_PLACEHOLDERS)));
            return bounds;
        }
        if (KnowledgeDerivationService.TYPE_AI.equals(derivationType)) {
            bounds.put(FIELD_PROMPT, stringField(FIELD_PROMPT, "推导要求", PROMPT_MIN, PROMPT_MAX, "从下列断言中归纳尚未直接写明的结论，只输出有把握的候选", null, "交给模型的附加要求，模型只能使用已给出的断言"));
            bounds.put(FIELD_MIN_CONFIDENCE, numberField(FIELD_MIN_CONFIDENCE, "最低置信度", MIN_CONFIDENCE_MIN, MIN_CONFIDENCE_MAX, 0.05, 0.7, "低于该值的断言不进入模型输入"));
            bounds.put(FIELD_LIMIT, numberField(FIELD_LIMIT, "单次最多推导", LIMIT_MIN, AI_LIMIT_MAX, 10, 50, "一次运行产出的候选上限"));
            return bounds;
        }
        bounds.put(FIELD_TEMPLATE, stringField(FIELD_TEMPLATE, "输出模板", TEMPLATE_MIN, TEMPLATE_MAX, "{subject} 的 {predicate} 为 {object}",
            CLAIM_PLACEHOLDERS, "候选标题的句式，必须包含 {subject}，可用占位符：" + String.join(" ", CLAIM_PLACEHOLDERS)));
        bounds.put(FIELD_MIN_CONFIDENCE, numberField(FIELD_MIN_CONFIDENCE, "最低置信度", MIN_CONFIDENCE_MIN, MIN_CONFIDENCE_MAX, 0.05, 0.7, "低于该值的断言不参与推导"));
        bounds.put(FIELD_LIMIT, numberField(FIELD_LIMIT, "单次最多推导", LIMIT_MIN, LIMIT_MAX, 10, 100, "一次运行产出的候选上限"));
        return bounds;
    }

    /**
     * Check a definition against the bounds and complete it with the defaults it left out.
     *
     * <p>Returns the definition that will actually run, so the caller stores what was validated
     * rather than what was submitted.</p>
     */
    public static Map<String, Object> validate(String derivationType, Map<String, Object> definition, List<String> relationTypes) {
        Map<String, Object> bounds = bounds(derivationType, relationTypes);
        for (Object key : definition.keySet()) {
            if (!bounds.containsKey(text(key))) {
                throw new IllegalArgumentException("规则定义包含不支持的字段：" + text(key) + "（可选 " + String.join("、", bounds.keySet()) + "）");
            }
        }
        Map<String, Object> validated = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : bounds.entrySet()) {
            String field = entry.getKey();
            Object raw = definition.get(field);
            if (raw == null || text(raw).trim().isEmpty()) {
                validated.put(field, defaultOf(entry.getValue()));
                continue;
            }
            validated.put(field, validateField(field, raw, entry.getValue(), relationTypes));
        }
        if (KnowledgeDerivationService.TYPE_GRAPH_PATH.equals(derivationType)) {
            validatePathTypes(text(validated.get(FIELD_PATH_TYPES)), relationTypes);
        }
        return validated;
    }

    /**
     * Check a multi-hop path before it runs: every hop must exist in the dictionary and the
     * chain must stay inside the hop limit, otherwise the walk silently answers nothing.
     */
    private static void validatePathTypes(String raw, List<String> relationTypes) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        List<PathHop> hops = new ArrayList<PathHop>();
        for (String segment : raw.split(">")) {
            String spec = segment.trim();
            if (spec.isEmpty()) {
                throw new IllegalArgumentException("多跳路径不能包含空段：" + raw);
            }
            boolean reverse = spec.toUpperCase(Locale.ROOT).endsWith(PATH_REVERSE_SUFFIX);
            String type = reverse ? spec.substring(0, spec.length() - PATH_REVERSE_SUFFIX.length()).trim() : spec;
            if (type.isEmpty()) {
                throw new IllegalArgumentException("多跳路径缺少关系类型：" + spec);
            }
            String normalized = type.toUpperCase(Locale.ROOT);
            if (relationTypes != null && !relationTypes.isEmpty() && !relationTypes.contains(normalized)) {
                throw new IllegalArgumentException("多跳路径的关系需在关系字典内：" + normalized + "（可选 " + String.join("、", relationTypes) + "）");
            }
            hops.add(new PathHop(normalized, reverse));
        }
        if (hops.size() < PATH_HOPS_MIN || hops.size() > PATH_HOPS_MAX) {
            throw new IllegalArgumentException("多跳路径需要 " + PATH_HOPS_MIN + "-" + PATH_HOPS_MAX + " 跳，当前 " + hops.size());
        }
    }

    /**
     * Parse a validated path into hops. Empty means "use the two-hop fields", which is what every
     * rule created before multi-hop paths existed already stores.
     */
    public static List<PathHop> parsePathTypes(String raw) {
        List<PathHop> hops = new ArrayList<PathHop>();
        if (raw == null || raw.trim().isEmpty()) {
            return hops;
        }
        for (String segment : raw.split(">")) {
            String spec = segment.trim();
            if (spec.isEmpty()) {
                continue;
            }
            boolean reverse = spec.toUpperCase(Locale.ROOT).endsWith(PATH_REVERSE_SUFFIX);
            String type = reverse ? spec.substring(0, spec.length() - PATH_REVERSE_SUFFIX.length()).trim() : spec;
            if (!type.isEmpty()) {
                hops.add(new PathHop(type.toUpperCase(Locale.ROOT), reverse));
            }
        }
        return hops;
    }

    /** One hop of a derivation path: which relation to walk, and in which direction. */
    public static final class PathHop {

        private final String type;

        private final boolean reverse;

        public PathHop(String type, boolean reverse) {
            this.type = type;
            this.reverse = reverse;
        }

        public String type() {
            return type;
        }

        public boolean reverse() {
            return reverse;
        }
    }

    /**
     * What may be edited on a rule and what may not, stated once and returned to the console.
     */
    public static Map<String, Object> editPolicy() {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("name", "可改：规则名称随时可改");
        policy.put("definition", "可改：仅限上述字段，且必须落在取值范围内");
        policy.put("scheduleType", "可改：手动衍生与计划衍生可随时互转");
        policy.put("enabled", "可改：停用只影响后续执行，已产出的候选保留");
        policy.put("derivationType", "不可改：推导方式决定定义结构，需要换方式请新建规则");
        policy.put("presetCode", "不可改：预设来源只用于识别与恢复默认");
        policy.put("reset", "预设规则可一键恢复默认定义，自定义规则无默认值可恢复");
        return policy;
    }

    // ------------------------------------------------------------------ presets
    private static List<Map<String, Object>> buildPresets() {
        List<Map<String, Object>> presets = new ArrayList<Map<String, Object>>();
        presets.add(preset("DEPLOY_CHAIN", "部署链路推导", KnowledgeDerivationService.TYPE_GRAPH_PATH,
            "组件属于某服务、服务部署在某集群，推出组件最终的部署位置——没人写下这条，但它决定了故障影响谁。",
            "A 属于 B、B 部署在 C → A 部署在 C",
            pathDefinition("PART_OF", "LOCATED_IN", 0.6, 100, "{head} 经由 {mid} 部署于 {tail}")));
        presets.add(preset("IMPACT_CHAIN", "依赖影响面推导", KnowledgeDerivationService.TYPE_GRAPH_PATH,
            "沿依赖走两跳，把间接依赖显式化：改一个组件之前，先知道谁会被牵连。",
            "A 依赖 B、B 依赖 C → A 间接依赖 C",
            pathDefinition("DEPENDS_ON", "DEPENDS_ON", 0.6, 100, "{head} 经 {mid} 间接依赖 {tail}")));
        presets.add(preset("IMPACT_SCOPE", "漏洞影响面推导", KnowledgeDerivationService.TYPE_GRAPH_PATH,
            "漏洞影响组件、产品依赖该组件、产品部署在项目、项目属于客户：把“这个漏洞影响到谁”的整条链路显式化，"
            + "四跳之外没人会手写这条结论，但它正是安全公告之后第一个被问到的问题。",
            "漏洞 ←受影响← 组件 ←依赖← 产品 ←部署← 项目 →属于→ 客户",
            pathDefinition("AFFECTS", "DEPENDS_ON", 0.5, 50, "{head} 可能影响 {tail}",
                // Walked from the vulnerability outwards: it affects a component, a product
                // depends on that component, the product is deployed in a project, and the
                // project belongs to a customer. Only the middle hops run against the arrow,
                // because the affected component is the target of the impact, not its source.
                "AFFECTS>DEPENDS_ON:REV>DEPLOYED_IN:REV>BELONGS_TO")));
        presets.add(preset("OWNER_CHAIN", "归属与责任推导", KnowledgeDerivationService.TYPE_GRAPH_PATH,
            "组件所属系统由谁管理，推出该组件的负责方——问题来了该找谁，往往没写下来。",
            "A 属于 B、B 由 C 管理 → A 由 C 负责",
            pathDefinition("PART_OF", "MANAGES", 0.55, 100, "{head} 所属 {mid} 由 {tail} 负责")));
        presets.add(preset("CAUSE_CHAIN", "因果链推导", KnowledgeDerivationService.TYPE_GRAPH_PATH,
            "原因之上还有原因，把两跳因果连成链，用于根因分析与风险预判。",
            "A 导致 B、B 导致 C → A 间接导致 C",
            pathDefinition("CAUSES", "CAUSES", 0.6, 80, "{head} 经 {mid} 间接导致 {tail}")));
        presets.add(preset("CONTAINS_CHAIN", "内容归属推导", KnowledgeDerivationService.TYPE_GRAPH_PATH,
            "容器里有什么、这些内容又归属哪个体系，用于目录与知识域的归并。",
            "A 包含 B、B 属于 C → A 的内容归属 C",
            pathDefinition("CONTAINS", "PART_OF", 0.6, 100, "{head} 包含 {mid}，{mid} 归属 {tail}")));
        presets.add(preset("RESOURCE_CHAIN", "资源占用推导", KnowledgeDerivationService.TYPE_GRAPH_PATH,
            "谁用了什么资源、这些资源在哪里，推出资源的实际落点。",
            "A 使用 B、B 位于 C → A 使用的资源位于 C",
            pathDefinition("USES", "LOCATED_IN", 0.6, 100, "{head} 使用的 {mid} 位于 {tail}")));
        presets.add(preset("CLAIM_SENTENCE", "断言成句", KnowledgeDerivationService.TYPE_TEMPLATE,
            "把已通过审核的断言整理成一句可直接引用的话，供检索与问答复用。",
            "断言（主体 / 谓词 / 客体）→ 一句完整陈述",
            // 门槛落在 0.6：抽取实际给出的置信度普遍就在 0.6，设成 0.7 的话这条预设永远
            // 拿不到输入，界面上看到的就是"规则在、扫描恒为 0"。
            claimDefinition(0.6, 100, "{subject} 的 {predicate} 为 {object}")));
        presets.add(preset("AI_CONCLUSION", "模型归纳结论", KnowledgeDerivationService.TYPE_AI,
            "让模型从已有断言中归纳没人直接写下的结论；无论是否配置模型，产出都是待审候选，批准后才成为知识。",
            "多条断言 → 模型归纳的新结论（需人工批准）",
            aiDefinition(0.7, 50, "从下列断言中归纳尚未直接写明的结论，只输出有把握的候选")));
        return presets;
    }

    private static Map<String, Object> preset(String code, String name, String type, String description, String scene, Map<String, Object> definition) {
        Map<String, Object> preset = new LinkedHashMap<String, Object>();
        preset.put("presetCode", code);
        preset.put("name", name);
        preset.put("derivationType", type);
        preset.put("description", description);
        preset.put("scene", scene);
        preset.put("scheduleType", KnowledgeDerivationService.SCHEDULE_MANUAL);
        preset.put("definition", definition);
        return preset;
    }

    private static Map<String, Object> pathDefinition(String firstType, String secondType, double minConfidence, int limit, String template) {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put(FIELD_FIRST_TYPE, firstType);
        definition.put(FIELD_SECOND_TYPE, secondType);
        definition.put(FIELD_MIN_CONFIDENCE, minConfidence);
        definition.put(FIELD_LIMIT, limit);
        definition.put(FIELD_TEMPLATE, template);
        return definition;
    }

    private static Map<String, Object> pathDefinition(String firstType, String secondType, double minConfidence, int limit, String template,
        String pathTypes) {
        Map<String, Object> definition = pathDefinition(firstType, secondType, minConfidence, limit, template);
        definition.put(FIELD_PATH_TYPES, pathTypes);
        return definition;
    }

    private static Map<String, Object> claimDefinition(double minConfidence, int limit, String template) {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put(FIELD_TEMPLATE, template);
        definition.put(FIELD_MIN_CONFIDENCE, minConfidence);
        definition.put(FIELD_LIMIT, limit);
        return definition;
    }

    private static Map<String, Object> aiDefinition(double minConfidence, int limit, String prompt) {
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        definition.put(FIELD_PROMPT, prompt);
        definition.put(FIELD_MIN_CONFIDENCE, minConfidence);
        definition.put(FIELD_LIMIT, limit);
        return definition;
    }

    // ------------------------------------------------------------------- fields
    private static Map<String, Object> enumField(String field, String label, List<String> options, String fallback, String hint) {
        Map<String, Object> spec = base(field, label, "ENUM", fallback, hint);
        List<String> values = new ArrayList<String>();
        if (options != null) {
            for (String option : options) {
                if (option != null && !option.trim().isEmpty() && !values.contains(option.trim())) {
                    values.add(option.trim());
                }
            }
        }
        spec.put("options", values);
        return spec;
    }

    private static Map<String, Object> numberField(String field, String label, double min, double max, double step, double fallback, String hint) {
        Map<String, Object> spec = base(field, label, "NUMBER", fallback, hint);
        spec.put("min", min);
        spec.put("max", max);
        spec.put("step", step);
        return spec;
    }

    private static Map<String, Object> stringField(String field, String label, int minLength, int maxLength, String fallback, List<String>
        placeholders, String hint) {
        Map<String, Object> spec = base(field, label, "STRING", fallback, hint);
        spec.put("minLength", minLength);
        spec.put("maxLength", maxLength);
        if (placeholders != null) {
            spec.put("placeholders", new ArrayList<String>(placeholders));
        }
        return spec;
    }

    private static Map<String, Object> base(String field, String label, String kind, Object fallback, String hint) {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("field", field);
        spec.put("label", label);
        spec.put("kind", kind);
        spec.put("default", fallback);
        spec.put("hint", hint);
        return spec;
    }

    private static Object defaultOf(Object spec) {
        if (spec instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) spec;
            return typed.get("default");
        }
        return null;
    }

    private static Object validateField(String field, Object raw, Object spec, List<String> relationTypes) {
        if (!(spec instanceof Map)) {
            return raw;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) spec;
        String kind = text(typed.get("kind"));
        String label = text(typed.get("label"));
        if ("NUMBER".equals(kind)) {
            double value = toDouble(raw, Double.NaN);
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException(label + "必须是数字：" + text(raw));
            }
            double min = toDouble(typed.get("min"), 0.0);
            double max = toDouble(typed.get("max"), Double.MAX_VALUE);
            if (value < min || value > max) {
                throw new IllegalArgumentException(label + "需在 " + trim(min) + " 与 " + trim(max) + " 之间：" + trim(value));
            }
            // A count stays a count; a confidence stays a fraction.
            if (FIELD_LIMIT.equals(field)) {
                return Integer.valueOf((int) Math.rint(value));
            }
            return Double.valueOf(trim(value));
        }
        String value = text(raw).trim();
        if ("ENUM".equals(kind)) {
            List<String> options = new ArrayList<String>();
            Object rawOptions = typed.get("options");
            if (rawOptions instanceof List) {
                for (Object option : (List<?>) rawOptions) {
                    options.add(text(option));
                }
            }
            // The dictionary is the bound; an empty dictionary means the schema has not been
            // published yet, and refusing every rule until it is would be worse than trusting
            // the operator.
            if (!options.isEmpty() && !options.contains(value)) {
                throw new IllegalArgumentException(label + "需在关系字典内：" + value + "（可选 " + String.join("、", options) + "）");
            }
            return value;
        }
        int minLength = (int) toDouble(typed.get("minLength"), 0.0);
        int maxLength = (int) toDouble(typed.get("maxLength"), 1000.0);
        if (value.length() < minLength || value.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度需在 " + minLength + " 与 " + maxLength + " 之间，当前 " + value.length());
        }
        Object rawPlaceholders = typed.get("placeholders");
        if (rawPlaceholders instanceof List) {
            List<String> allowed = new ArrayList<String>();
            for (Object placeholder : (List<?>) rawPlaceholders) {
                allowed.add(text(placeholder));
            }
            Matcher matcher = PLACEHOLDER.matcher(value);
            while (matcher.find()) {
                if (!allowed.contains(matcher.group())) {
                    throw new IllegalArgumentException(label + "包含无法填充的占位符：" + matcher.group() + "（可用 " + String.join(" ", allowed) + "）");
                }
            }
            if (allowed.contains("{subject}") && !value.contains("{subject}")) {
                throw new IllegalArgumentException(label + "必须包含 {subject}，否则候选标题没有主体");
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> target = new LinkedHashMap<String, Object>(source);
        Object definition = source.get("definition");
        if (definition instanceof Map) {
            target.put("definition", new LinkedHashMap<String, Object>((Map<String, Object>) definition));
        }
        return target;
    }

    private static double trim(double value) {
        return Math.round(value * 10000.0) / 10000.0;
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

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
