package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.KbScenarioTemplates;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scenario profile of a knowledge base: how it is built, what is derived from it, and how it answers.
 *
 * <p>The profile is stored once per knowledge base and is read by three callers that used to
 * share one deployment-wide configuration: ingestion (which forwards the chunking half to the
 * parser and honours the extraction switches), derivation (which decides whether a graph is
 * built and which rules a new library starts with), and retrieval (which sends the query half
 * to the AI service as an override on top of the platform policy).</p>
 *
 * <p>An untouched library stores nothing: it follows its template, so a better default reaches
 * it later instead of being frozen at creation time. A library that edits any field stores the
 * whole profile and stops tracking the template — partial overrides would make "why does this
 * library answer differently" unanswerable.</p>
 */
@Service
public class KbScenarioService {

    private static final Logger LOG = LoggerFactory.getLogger(KbScenarioService.class);

    private static final int CHUNK_SIZE_MIN = 200;

    private static final int CHUNK_SIZE_MAX = 4000;

    private static final int CHUNK_OVERLAP_MAX = 1000;

    private static final int MIN_CHUNK_SIZE_MAX = 1000;

    private static final double WEIGHT_MIN = 0.0D;

    private static final double WEIGHT_MAX = 5.0D;

    private static final int RECALL_MULTIPLIER_MIN = 1;

    private static final int RECALL_MULTIPLIER_MAX = 10;

    private static final int MIN_CANDIDATES_MIN = 1;

    private static final int MIN_CANDIDATES_MAX = 200;

    private static final int RERANK_TOP_N_MAX = 200;

    private static final int RRF_K_MIN = 1;

    private static final int RRF_K_MAX = 1000;

    private static final int CONTEXT_MIN = 500;

    private static final int CONTEXT_MAX = 60000;

    private static final int MIN_CHUNK_EVIDENCE_MAX = 20;

    private static final List<String> STRATEGIES = Collections.unmodifiableList(Arrays.asList("SEMANTIC", "FIXED", "SENTENCE", "STRUCTURE",
        "PARENT_CHILD"));

    private static final List<String> MEASURES = Collections.unmodifiableList(Arrays.asList("CHAR", "TOKEN"));

    private final KnowledgeMapper mapper;

    private final ChunkPolicyService chunkPolicy;

    private final KnowledgeDerivationService derivation;

    private final ObjectMapper objectMapper;

    public KbScenarioService(KnowledgeMapper mapper, ChunkPolicyService chunkPolicy, KnowledgeDerivationService derivation,
        ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.chunkPolicy = chunkPolicy;
        this.derivation = derivation;
        this.objectMapper = objectMapper;
    }

    /**
     * Vocabulary, bounds and the current profile: everything the console needs to render the
     * editor without a second, drifting definition of what is allowed.
     */
    public Map<String, Object> schema(Long kb) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("schema", KbScenarioTemplates.schema(derivation.presets(kb), derivation.relationTypes(kb)));
        payload.putAll(policy(kb));
        return payload;
    }

    /** Effective profile of a knowledge base plus whether it was ever edited. */
    public Map<String, Object> policy(Long kb) {
        Map<String, Object> stored = readRow(kb);
        String scenario = stored == null ? KbScenarioTemplates.DEFAULT_SCENARIO : text(stored.get("scenario"));
        Map<String, Object> profile = stored == null ? KbScenarioTemplates.defaults(scenario) : normalize(scenario, readProfile(stored));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("scenario", KbScenarioTemplates.normalizeCode(scenario));
        payload.put("profile", profile);
        payload.put("template", KbScenarioTemplates.defaults(scenario));
        payload.put("customized", stored != null);
        payload.put("updatedBy", stored == null ? null : stored.get("updatedBy"));
        payload.put("updatedTime", stored == null ? null : stored.get("updatedTime"));
        return payload;
    }

    /**
     * Store an edited profile and apply the parts that live in their own tables: chunking goes
     * to the chunk policy the parser already reads, and the derivation rules a scenario wants
     * are installed if the library does not have them yet.
     */
    @Transactional
    public Map<String, Object> save(Long kb, Map<String, Object> request) {
        if (kb == null) {
            throw new IllegalArgumentException("缺少知识库：无法保存场景配置");
        }
        String scenario = KbScenarioTemplates.normalizeCode(text(section(request).get("scenario")));
        Map<String, Object> profile = normalize(scenario, section(section(request).get("profile")));
        mapper.saveKbScenario(kb, scenario, writeJson(profile), currentUser());
        applyChunkPolicy(kb, section(section(profile.get("generation")).get("chunk")));
        applyDerivationRules(kb, section(profile.get("derivation")));
        return policy(kb);
    }

    /** Drop the edited profile: the library goes back to tracking its template. */
    @Transactional
    public Map<String, Object> reset(Long kb) {
        if (kb == null) {
            throw new IllegalArgumentException("缺少知识库：无法恢复场景配置");
        }
        mapper.resetKbScenario(kb);
        return policy(kb);
    }

    /**
     * Install the scenario a library is created with: chunking, derivation rules and the profile
     * row itself. A failure here must not lose the knowledge base, so the seeding is best-effort
     * and only the profile row is authoritative.
     */
    @Transactional
    public void seed(Long kb, String scenario) {
        String code = KbScenarioTemplates.normalizeCode(scenario);
        Map<String, Object> profile = KbScenarioTemplates.defaults(code);
        try {
            applyChunkPolicy(kb, section(section(profile.get("generation")).get("chunk")));
        } catch (RuntimeException e) {
            LOG.warn("知识库 {} 应用场景分块策略失败：{}", kb, e.getMessage());
        }
        try {
            applyDerivationRules(kb, section(profile.get("derivation")));
        } catch (RuntimeException e) {
            LOG.warn("知识库 {} 安装场景衍生规则失败：{}", kb, e.getMessage());
        }
        mapper.saveKbScenario(kb, code, writeJson(profile), currentUser());
    }

    /**
     * The query half of the profile as an override the AI service merges onto the platform
     * policy. Null means "no opinion", which keeps a library that never edited anything on the
     * platform default — including future defaults it has not seen yet.
     */
    public Map<String, Object> queryOverride(Long kb) {
        if (kb == null) {
            return null;
        }
        Map<String, Object> stored = readRow(kb);
        if (stored == null) {
            return null;
        }
        Map<String, Object> profile = normalize(text(stored.get("scenario")), readProfile(stored));
        Map<String, Object> query = section(profile.get("query"));
        Map<String, Object> route = new LinkedHashMap<String, Object>();
        route.put("defaultStrategies", new ArrayList<Object>(list(query.get("defaultStrategies"))));
        Map<String, Object> channels = new LinkedHashMap<String, Object>();
        for (String name : KbScenarioTemplates.CHANNELS) {
            Map<String, Object> channel = section(section(query.get("channels")).get(name));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("enabled", Boolean.TRUE.equals(channel.get("enabled")));
            item.put("weight", doubleValue(channel.get("weight"), 1.0D));
            channels.put(name, item);
        }
        Map<String, Object> recall = new LinkedHashMap<String, Object>();
        recall.put("channels", channels);
        recall.put("recallMultiplier", intValue(query.get("recallMultiplier"), 3));
        recall.put("minRecallCandidates", intValue(query.get("minRecallCandidates"), 20));
        Map<String, Object> chunkBoost = new LinkedHashMap<String, Object>();
        chunkBoost.put("enabled", Boolean.TRUE.equals(query.get("chunkBoostEnabled")));
        Map<String, Object> fusion = new LinkedHashMap<String, Object>();
        fusion.put("rrfK", intValue(query.get("rrfK"), 60));
        fusion.put("qualitySignalsEnabled", Boolean.TRUE.equals(query.get("qualitySignalsEnabled")));
        fusion.put("chunkBoost", chunkBoost);
        Map<String, Object> rerank = new LinkedHashMap<String, Object>();
        rerank.put("enabled", Boolean.TRUE.equals(query.get("rerankEnabled")));
        rerank.put("topN", intValue(query.get("rerankTopN"), 0));
        Map<String, Object> answer = new LinkedHashMap<String, Object>();
        answer.put("contextMaxChars", intValue(query.get("contextMaxChars"), 8000));
        answer.put("minChunkEvidence", intValue(query.get("minChunkEvidence"), 3));
        Map<String, Object> override = new LinkedHashMap<String, Object>();
        override.put("route", route);
        override.put("recall", recall);
        override.put("fusion", fusion);
        override.put("rerank", rerank);
        override.put("answer", answer);
        return override;
    }

    /** Answer format a library defaults to; empty when the caller should decide. */
    public String answerFormat(Long kb) {
        Map<String, Object> stored = kb == null ? null : readRow(kb);
        Map<String, Object> profile = stored == null ? null : normalize(text(stored.get("scenario")), readProfile(stored));
        String format = text(section(profile == null ? null : profile.get("query")).get("answerFormat")).toLowerCase(Locale.ROOT);
        return KbScenarioTemplates.ANSWER_FORMATS.contains(format) ? format : "";
    }

    /** Whether this library builds and walks a graph; drives ingestion and the graph channel. */
    public boolean graphEnabled(Long kb) {
        Map<String, Object> stored = kb == null ? null : readRow(kb);
        Map<String, Object> profile = stored == null ? null : normalize(text(stored.get("scenario")), readProfile(stored));
        if (profile == null) {
            return true;
        }
        return Boolean.TRUE.equals(section(profile.get("derivation")).get("graphEnabled"));
    }

    /**
     * Chunking this library hands to the parser: the template's until somebody edits it.
     */
    public Map<String, Object> chunkPolicy(Long kb) {
        Map<String, Object> stored = readRow(kb);
        Map<String, Object> profile = stored == null ? null : normalize(text(stored.get("scenario")), readProfile(stored));
        if (profile == null) {
            profile = KbScenarioTemplates.defaults(KbScenarioTemplates.DEFAULT_SCENARIO);
        }
        return section(section(profile.get("generation")).get("chunk"));
    }

    /** Extraction switches of this library: whether claims, entity resolution and conflicts run. */
    public Map<String, Object> extraction(Long kb) {
        Map<String, Object> stored = kb == null ? null : readRow(kb);
        Map<String, Object> profile = stored == null ? null : normalize(text(stored.get("scenario")), readProfile(stored));
        if (profile == null) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("claims", Boolean.TRUE);
            fallback.put("entityResolution", Boolean.TRUE);
            fallback.put("conflictDetection", Boolean.TRUE);
            return fallback;
        }
        Map<String, Object> extract = section(section(profile.get("generation")).get("extract"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("claims", Boolean.TRUE.equals(extract.get("claims")));
        result.put("entityResolution", Boolean.TRUE.equals(extract.get("entityResolution")));
        result.put("conflictDetection", Boolean.TRUE.equals(extract.get("conflictDetection")));
        return result;
    }

    // ------------------------------------------------------------------ normalize

    /**
     * Merge a console payload over the scenario default, clamping every value into the bounds
     * the schema publishes. Unknown fields are dropped rather than stored: a typo that is
     * silently kept becomes a setting nobody remembers setting.
     */
    public Map<String, Object> normalize(String scenario, Map<String, Object> request) {
        Map<String, Object> base = KbScenarioTemplates.defaults(scenario);
        if (request == null || request.isEmpty()) {
            return base;
        }
        Map<String, Object> generation = section(base.get("generation"));
        generation.put("chunk", chunk(section(section(request.get("generation")).get("chunk")), section(generation.get("chunk"))));
        generation.put("extract", extract(section(section(request.get("generation")).get("extract")), section(generation.get("extract"))));
        base.put("derivation", derivation(section(request.get("derivation")), section(base.get("derivation"))));
        base.put("query", query(section(request.get("query")), section(base.get("query"))));
        return base;
    }

    private Map<String, Object> chunk(Map<String, Object> request, Map<String, Object> base) {
        Map<String, Object> chunk = new LinkedHashMap<String, Object>();
        String strategy = text(request.get("strategy")).toUpperCase(Locale.ROOT);
        if (!STRATEGIES.contains(strategy)) {
            strategy = text(base.get("strategy")).toUpperCase(Locale.ROOT);
        }
        if (!STRATEGIES.contains(strategy)) {
            strategy = "SEMANTIC";
        }
        int size = clamp(intValue(request.get("chunkSize"), intValue(base.get("chunkSize"), 900)), CHUNK_SIZE_MIN, CHUNK_SIZE_MAX);
        int overlap = clamp(intValue(request.get("chunkOverlap"), intValue(base.get("chunkOverlap"), 120)), 0, CHUNK_OVERLAP_MAX);
        int minSize = clamp(intValue(request.get("minChunkSize"), intValue(base.get("minChunkSize"), 120)), 0, MIN_CHUNK_SIZE_MAX);
        // An overlap that swallows the window would duplicate the whole document.
        overlap = Math.min(overlap, size / 2);
        minSize = Math.min(minSize, size / 2);
        String measure = text(request.get("measure")).toUpperCase(Locale.ROOT);
        if (!MEASURES.contains(measure)) {
            measure = text(base.get("measure")).toUpperCase(Locale.ROOT);
        }
        if (!MEASURES.contains(measure)) {
            measure = "CHAR";
        }
        boolean parentChild = Boolean.TRUE.equals(request.get("parentChild")) || Boolean.TRUE.equals(base.get("parentChild"))
            || "PARENT_CHILD".equals(strategy);
        chunk.put("strategy", strategy);
        chunk.put("chunkSize", size);
        chunk.put("chunkOverlap", overlap);
        chunk.put("minChunkSize", minSize);
        chunk.put("measure", measure);
        chunk.put("keepStructure", request.get("keepStructure") == null ? Boolean.TRUE.equals(base.get("keepStructure"))
            : Boolean.TRUE.equals(request.get("keepStructure")));
        chunk.put("respectSentence", request.get("respectSentence") == null ? Boolean.TRUE.equals(base.get("respectSentence"))
            : Boolean.TRUE.equals(request.get("respectSentence")));
        chunk.put("parentChild", parentChild);
        return chunk;
    }

    private Map<String, Object> extract(Map<String, Object> request, Map<String, Object> base) {
        Map<String, Object> extract = new LinkedHashMap<String, Object>();
        extract.put("claims", bool(request.get("claims"), base.get("claims")));
        extract.put("entityResolution", bool(request.get("entityResolution"), base.get("entityResolution")));
        extract.put("conflictDetection", bool(request.get("conflictDetection"), base.get("conflictDetection")));
        return extract;
    }

    private Map<String, Object> derivation(Map<String, Object> request, Map<String, Object> base) {
        Map<String, Object> derivation = new LinkedHashMap<String, Object>();
        boolean graphEnabled = bool(request.get("graphEnabled"), base.get("graphEnabled"));
        derivation.put("graphEnabled", graphEnabled);
        List<Object> presets = new ArrayList<Object>();
        for (Object item : list(request.get("presets"))) {
            String code = text(item).trim().toUpperCase(Locale.ROOT);
            if (DerivationRuleCatalog.preset(code) == null || presets.contains(code)) {
                continue;
            }
            presets.add(code);
        }
        if (presets.isEmpty()) {
            presets.addAll(list(base.get("presets")));
        }
        derivation.put("presets", presets);
        return derivation;
    }

    private Map<String, Object> query(Map<String, Object> request, Map<String, Object> base) {
        Map<String, Object> query = new LinkedHashMap<String, Object>();
        List<Object> strategies = new ArrayList<Object>();
        for (Object item : list(request.get("defaultStrategies"))) {
            String name = text(item).trim().toLowerCase(Locale.ROOT);
            if (KbScenarioTemplates.CHANNELS.contains(name) && !strategies.contains(name)) {
                strategies.add(name);
            }
        }
        if (strategies.isEmpty()) {
            strategies.addAll(list(base.get("defaultStrategies")));
        }
        query.put("defaultStrategies", strategies);
        Map<String, Object> requestChannels = section(request.get("channels"));
        Map<String, Object> baseChannels = section(base.get("channels"));
        Map<String, Object> channels = new LinkedHashMap<String, Object>();
        for (String name : KbScenarioTemplates.CHANNELS) {
            Map<String, Object> requested = section(requestChannels.get(name));
            Map<String, Object> fallback = section(baseChannels.get(name));
            Map<String, Object> channel = new LinkedHashMap<String, Object>();
            boolean enabled = requested.get("enabled") == null ? Boolean.TRUE.equals(fallback.get("enabled"))
                : Boolean.TRUE.equals(requested.get("enabled"));
            // A library without a graph cannot walk one, whatever the console asked for.
            if ("graph".equals(name) && !graphEnabledOf(request, base)) {
                enabled = false;
            }
            channel.put("enabled", enabled);
            channel.put("weight", clampDouble(doubleValue(requested.get("weight"), doubleValue(fallback.get("weight"), 1.0D)), WEIGHT_MIN,
                WEIGHT_MAX));
            channels.put(name, channel);
        }
        query.put("channels", channels);
        query.put("recallMultiplier", clamp(intValue(request.get("recallMultiplier"), intValue(base.get("recallMultiplier"), 3)),
            RECALL_MULTIPLIER_MIN, RECALL_MULTIPLIER_MAX));
        query.put("minRecallCandidates", clamp(intValue(request.get("minRecallCandidates"), intValue(base.get("minRecallCandidates"), 20)),
            MIN_CANDIDATES_MIN, MIN_CANDIDATES_MAX));
        query.put("rerankEnabled", request.get("rerankEnabled") == null ? Boolean.TRUE.equals(base.get("rerankEnabled"))
            : Boolean.TRUE.equals(request.get("rerankEnabled")));
        query.put("rerankTopN", clamp(intValue(request.get("rerankTopN"), intValue(base.get("rerankTopN"), 0)), 0, RERANK_TOP_N_MAX));
        query.put("rrfK", clamp(intValue(request.get("rrfK"), intValue(base.get("rrfK"), 60)), RRF_K_MIN, RRF_K_MAX));
        query.put("qualitySignalsEnabled", request.get("qualitySignalsEnabled") == null ? Boolean.TRUE.equals(base.get("qualitySignalsEnabled"))
            : Boolean.TRUE.equals(request.get("qualitySignalsEnabled")));
        query.put("chunkBoostEnabled", request.get("chunkBoostEnabled") == null ? Boolean.TRUE.equals(base.get("chunkBoostEnabled"))
            : Boolean.TRUE.equals(request.get("chunkBoostEnabled")));
        String format = text(request.get("answerFormat")).toLowerCase(Locale.ROOT);
        if (!KbScenarioTemplates.ANSWER_FORMATS.contains(format)) {
            format = text(base.get("answerFormat")).toLowerCase(Locale.ROOT);
        }
        if (!KbScenarioTemplates.ANSWER_FORMATS.contains(format)) {
            format = "default";
        }
        query.put("answerFormat", format);
        query.put("minChunkEvidence", clamp(intValue(request.get("minChunkEvidence"), intValue(base.get("minChunkEvidence"), 3)), 0,
            MIN_CHUNK_EVIDENCE_MAX));
        query.put("contextMaxChars", clamp(intValue(request.get("contextMaxChars"), intValue(base.get("contextMaxChars"), 8000)), CONTEXT_MIN,
            CONTEXT_MAX));
        return query;
    }

    private boolean graphEnabledOf(Map<String, Object> request, Map<String, Object> base) {
        Object value = request.get("graphEnabled");
        if (value == null) {
            value = base.get("graphEnabled");
        }
        return Boolean.TRUE.equals(value);
    }

    // ------------------------------------------------------------------ plumbing

    private void applyChunkPolicy(Long kb, Map<String, Object> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        chunkPolicy.save(kb, chunk);
    }

    private void applyDerivationRules(Long kb, Map<String, Object> derivationSection) {
        if (derivationSection == null) {
            return;
        }
        List<String> codes = new ArrayList<String>();
        for (Object item : list(derivationSection.get("presets"))) {
            String code = text(item).trim().toUpperCase(Locale.ROOT);
            if (!code.isEmpty() && !codes.contains(code)) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return;
        }
        derivation.seed(kb, codes);
    }

    private Map<String, Object> readRow(Long kb) {
        return kb == null ? null : mapper.getKbScenario(kb);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProfile(Map<String, Object> row) {
        String raw = text(row.get("profileJson"));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            LOG.warn("场景配置不可解析，按模板默认值处理：{}", e.getMessage());
            return null;
        }
    }

    private String writeJson(Map<String, Object> profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (Exception e) {
            throw new IllegalStateException("场景配置序列化失败：" + e.getMessage());
        }
    }

    private String currentUser() {
        User user = AuthContext.get();
        return user == null ? "" : String.valueOf(user.getUsername());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private static List<Object> list(Object value) {
        return value instanceof List ? new ArrayList<Object>((List<Object>) value) : new ArrayList<Object>();
    }

    private static boolean bool(Object value, Object fallback) {
        if (value == null) {
            return Boolean.TRUE.equals(fallback);
        }
        return Boolean.TRUE.equals(value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
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
