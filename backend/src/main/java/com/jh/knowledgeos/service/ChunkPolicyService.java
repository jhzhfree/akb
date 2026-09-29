package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunking policy of a knowledge base.
 *
 * Chunking used to be a pair of environment constants inside the AI service, so a
 * procedure manual and a design report in the same deployment were cut with the same
 * window. The policy now lives with the data it describes: the console edits it per
 * knowledge base, ingestion forwards it to the parser, and every chunk keeps the
 * strategy that produced it so a re-cut is explainable.
 */
@Service
public class ChunkPolicyService {

    public static final String DEFAULT_STRATEGY = "SEMANTIC";

    public static final int DEFAULT_CHUNK_SIZE = 900;

    public static final int DEFAULT_CHUNK_OVERLAP = 120;

    public static final int DEFAULT_MIN_CHUNK_SIZE = 120;

    public static final String DEFAULT_MEASURE = "CHAR";

    private static final Set<String> STRATEGIES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("SEMANTIC", "FIXED", "SENTENCE",
        "STRUCTURE", "PARENT_CHILD")));

    private static final Set<String> MEASURES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("CHAR", "TOKEN")));

    private static final int CHUNK_SIZE_MIN = 200;

    private static final int CHUNK_SIZE_MAX = 4000;

    private static final int OVERLAP_MAX = 1000;

    private final KnowledgeMapper mapper;

    public ChunkPolicyService(KnowledgeMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Built-in policy: what a knowledge base uses until someone tunes it.
     */
    public Map<String, Object> defaults() {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("strategy", DEFAULT_STRATEGY);
        policy.put("chunkSize", DEFAULT_CHUNK_SIZE);
        policy.put("chunkOverlap", DEFAULT_CHUNK_OVERLAP);
        policy.put("minChunkSize", DEFAULT_MIN_CHUNK_SIZE);
        policy.put("measure", DEFAULT_MEASURE);
        policy.put("keepStructure", true);
        policy.put("respectSentence", true);
        policy.put("parentChild", false);
        return policy;
    }

    /**
     * Effective policy of a knowledge base, plus whether it was customized.
     */
    public Map<String, Object> policy(Long kb) {
        Map<String, Object> stored = mapper.getChunkPolicy(kb);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("policy", stored == null ? defaults() : fromRow(stored));
        result.put("customized", stored != null);
        result.put("updatedBy", stored == null ? null : stored.get("updatedBy"));
        result.put("updatedTime", stored == null ? null : stored.get("updatedTime"));
        return result;
    }

    /**
     * Policy handed to the parser on upload: never null, always bounded.
     */
    public Map<String, Object> policyFor(Long kb) {
        Map<String, Object> stored = mapper.getChunkPolicy(kb);
        return stored == null ? defaults() : fromRow(stored);
    }

    @Transactional
    public Map<String, Object> save(Long kb, Map<String, Object> request) {
        Map<String, Object> policy = normalize(request);
        User current = AuthContext.get();
        String updatedBy = current == null ? "" : String.valueOf(current.getUsername());
        mapper.saveChunkPolicy(kb, String.valueOf(policy.get("strategy")), intValue(policy.get("chunkSize"), DEFAULT_CHUNK_SIZE),
            intValue(policy.get("chunkOverlap"), DEFAULT_CHUNK_OVERLAP), intValue(policy.get("minChunkSize"), DEFAULT_MIN_CHUNK_SIZE),
            String.valueOf(policy.get("measure")), boolValue(policy.get("keepStructure"), true), boolValue(policy.get("respectSentence"), true),
            boolValue(policy.get("parentChild"), false), updatedBy);
        return policy(kb);
    }

    @Transactional
    public Map<String, Object> reset(Long kb) {
        mapper.resetChunkPolicy(kb);
        return policy(kb);
    }

    /**
     * Vocabulary and bounds for the console: strategies with the document type they
     * suit, plus the numeric limits, so the editor never offers an impossible value.
     */
    public Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("default", defaults());
        schema.put("strategies", strategies());
        schema.put("measures", Arrays.asList(measure("CHAR", "字符数"), measure("TOKEN", "估算 Token")));
        Map<String, Object> ranges = new LinkedHashMap<String, Object>();
        ranges.put("chunkSize", range(CHUNK_SIZE_MIN, CHUNK_SIZE_MAX));
        ranges.put("chunkOverlap", range(0, OVERLAP_MAX));
        ranges.put("minChunkSize", range(0, OVERLAP_MAX));
        schema.put("ranges", ranges);
        return schema;
    }

    /**
     * Merge a console payload over the defaults, clamping every value into bounds.
     */
    public Map<String, Object> normalize(Map<String, Object> request) {
        Map<String, Object> base = defaults();
        if (request == null || request.isEmpty()) {
            return base;
        }
        String strategy = stringValue(request.get("strategy"), DEFAULT_STRATEGY).toUpperCase();
        if (!STRATEGIES.contains(strategy)) {
            strategy = DEFAULT_STRATEGY;
        }
        String measure = stringValue(request.get("measure"), DEFAULT_MEASURE).toUpperCase();
        if (!MEASURES.contains(measure)) {
            measure = DEFAULT_MEASURE;
        }
        int size = clamp(intValue(request.get("chunkSize"), DEFAULT_CHUNK_SIZE), CHUNK_SIZE_MIN, CHUNK_SIZE_MAX);
        int overlap = clamp(intValue(request.get("chunkOverlap"), DEFAULT_CHUNK_OVERLAP), 0, OVERLAP_MAX);
        int minSize = clamp(intValue(request.get("minChunkSize"), DEFAULT_MIN_CHUNK_SIZE), 0, OVERLAP_MAX);
        // An overlap that swallows the window would duplicate the whole document.
        overlap = Math.min(overlap, size / 2);
        minSize = Math.min(minSize, size / 2);
        boolean parentChild = boolValue(request.get("parentChild"), false) || "PARENT_CHILD".equals(strategy);
        base.put("strategy", strategy);
        base.put("chunkSize", size);
        base.put("chunkOverlap", overlap);
        base.put("minChunkSize", minSize);
        base.put("measure", measure);
        base.put("keepStructure", boolValue(request.get("keepStructure"), true));
        base.put("respectSentence", boolValue(request.get("respectSentence"), true));
        base.put("parentChild", parentChild);
        return base;
    }

    private List<Map<String, Object>> strategies() {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(strategy("SEMANTIC", "语义分段（默认）", "按语义块累积到窗口上限，表格与代码独立成块，遇到新章节即切分"));
        items.add(strategy("FIXED", "固定窗口滑窗", "按窗口在整篇正文上滑动并带真实重叠，适合无结构长文本"));
        items.add(strategy("SENTENCE", "句子聚合", "以句子为最小单位聚合到窗口，切分点始终落在句末"));
        items.add(strategy("STRUCTURE", "章节结构", "每个标题路径生成一块，仅在超长时硬切，适合手册与规程"));
        items.add(strategy("PARENT_CHILD", "父子分块", "先按章节生成父块，再生成窗口子块，检索命中子块、回答回看父块"));
        return items;
    }

    private static Map<String, Object> strategy(String name, String label, String description) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("label", label);
        item.put("description", description);
        return item;
    }

    private static Map<String, Object> measure(String name, String label) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("name", name);
        item.put("label", label);
        return item;
    }

    private static Map<String, Object> range(int min, int max) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("min", min);
        item.put("max", max);
        return item;
    }

    private Map<String, Object> fromRow(Map<String, Object> row) {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("strategy", stringValue(row.get("strategy"), DEFAULT_STRATEGY));
        policy.put("chunkSize", intValue(row.get("chunkSize"), DEFAULT_CHUNK_SIZE));
        policy.put("chunkOverlap", intValue(row.get("chunkOverlap"), DEFAULT_CHUNK_OVERLAP));
        policy.put("minChunkSize", intValue(row.get("minChunkSize"), DEFAULT_MIN_CHUNK_SIZE));
        policy.put("measure", stringValue(row.get("measure"), DEFAULT_MEASURE));
        policy.put("keepStructure", boolValue(row.get("keepStructure"), true));
        policy.put("respectSentence", boolValue(row.get("respectSentence"), true));
        policy.put("parentChild", boolValue(row.get("parentChild"), false));
        return policy;
    }

    private static int clamp(int value, int min, int max) {
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
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        if ("1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)) {
            return true;
        }
        return "0".equals(text) || "false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text) ? false : fallback;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
