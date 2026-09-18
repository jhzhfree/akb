package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects a knowledge neighborhood from the authoritative MySQL model.
 *
 * <p>Neo4j serves graph traversal, but a derived view can be empty while the authoritative model
 * is rich, so an operator would see isolated dots. This read-only projection walks at most
 * {@code depth} hops from the seed and stops at {@code limit} nodes.</p>
 */
@Service
public class AuthoritativeGraphNeighborhoodService {
    private static final String[] NODE_TYPES = {"CARD", "CLAIM", "EVIDENCE", "ENTITY", "SOURCE"};

    private final KnowledgeMapper mapper;

    public AuthoritativeGraphNeighborhoodService(KnowledgeMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> neighborhood(Long kb, String code, int depth, int limit) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("provider", "authoritative");
        String seedCode = code == null ? "" : code.trim();
        if (seedCode.isEmpty()) {
            result.put("available", false);
            result.put("reason", "SEED_REQUIRED");
            result.put("nodes", new ArrayList<Map<String, Object>>());
            result.put("edges", new ArrayList<Map<String, Object>>());
            return result;
        }
        Map<String, Object> seed = mapper.resolveGraphNode(kb, seedCode);
        if (seed == null || seed.isEmpty()) {
            result.put("available", false);
            result.put("reason", "SEED_NOT_FOUND");
            result.put("nodes", new ArrayList<Map<String, Object>>());
            result.put("edges", new ArrayList<Map<String, Object>>());
            return result;
        }
        String seedType = String.valueOf(seed.get("nodeType"));
        int hops = depth < 1 ? 1 : (depth > 3 ? 3 : depth);
        int cap = limit < 10 ? 10 : (limit > 400 ? 400 : limit);

        List<Edge> edges = loadEdges(kb);
        Map<String, Set<Integer>> adjacency = new HashMap<String, Set<Integer>>();
        for (int i = 0; i < edges.size(); i++) {
            link(adjacency, edges.get(i).source, i);
            link(adjacency, edges.get(i).target, i);
        }

        Set<String> reached = new LinkedHashSet<String>();
        Deque<String> queue = new ArrayDeque<String>();
        Map<String, Integer> distance = new HashMap<String, Integer>();
        String root = key(seedType, seedCode);
        reached.add(root);
        distance.put(root, 0);
        queue.add(root);
        boolean truncated = false;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int step = distance.get(current);
            if (step >= hops) continue;
            Set<Integer> incident = adjacency.get(current);
            if (incident == null) continue;
            for (Integer index : incident) {
                Edge edge = edges.get(index);
                String next = edge.source.equals(current) ? edge.target : edge.source;
                if (!distance.containsKey(next)) {
                    if (reached.size() >= cap) {
                        truncated = true;
                        continue;
                    }
                    distance.put(next, step + 1);
                    reached.add(next);
                    queue.add(next);
                }
            }
        }

        Map<String, Map<String, Object>> meta = loadNodeMetadata(kb, reached);
        List<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        Map<String, Integer> degree = new HashMap<String, Integer>();
        List<Map<String, Object>> visibleEdges = new ArrayList<Map<String, Object>>();
        for (Edge edge : edges) {
            if (!reached.contains(edge.source) || !reached.contains(edge.target)) continue;
            degree(degree, edge.source);
            degree(degree, edge.target);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("source", code(edge.source));
            item.put("sourceType", type(edge.source));
            item.put("target", code(edge.target));
            item.put("targetType", type(edge.target));
            item.put("type", edge.type);
            item.put("semanticType", edge.semanticType);
            item.put("confidence", edge.confidence);
            visibleEdges.add(item);
        }
        for (String nodeKey : reached) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("code", code(nodeKey));
            item.put("type", type(nodeKey));
            Map<String, Object> found = meta.get(nodeKey);
            item.put("title", found == null ? code(nodeKey) : text(found.get("title")));
            item.put("status", found == null ? "" : text(found.get("status")));
            item.put("objectType", found == null ? "" : text(found.get("objectType")));
            item.put("confidence", found == null ? null : found.get("confidence"));
            item.put("distance", distance.get(nodeKey));
            item.put("degree", degree.get(nodeKey) == null ? 0 : degree.get(nodeKey));
            nodes.add(item);
        }

        result.put("available", true);
        result.put("seed", seed);
        result.put("depth", hops);
        result.put("nodes", nodes);
        result.put("edges", visibleEdges);
        result.put("truncated", truncated);
        return result;
    }

    private List<Edge> loadEdges(Long kb) {
        List<Edge> edges = new ArrayList<Edge>();
        add(edges, mapper.listGraphCardEvidenceEdges(kb), "CARD", "EVIDENCE");
        add(edges, mapper.listGraphClaimEvidenceEdges(kb), "CLAIM", "EVIDENCE");
        add(edges, mapper.listGraphEvidenceSourceEdges(kb), "EVIDENCE", "SOURCE");
        add(edges, mapper.listGraphClaimEntityEdges(kb), "CLAIM", "ENTITY");
        add(edges, mapper.listGraphCardEntityEdges(kb), "CARD", "ENTITY");
        add(edges, mapper.listGraphEntityRelationEdges(kb), "ENTITY", "ENTITY");
        return edges;
    }

    private Map<String, Map<String, Object>> loadNodeMetadata(Long kb, Set<String> keys) {
        Map<String, List<String>> wanted = new HashMap<String, List<String>>();
        for (String type : NODE_TYPES) wanted.put(type, new ArrayList<String>());
        for (String key : keys) wanted.get(type(key)).add(code(key));
        Map<String, Map<String, Object>> meta = new HashMap<String, Map<String, Object>>();
        // An empty IN () list is invalid SQL, so only populated node types are queried.
        collect(meta, "CARD", kb, wanted.get("CARD"), mapper::listGraphCards);
        collect(meta, "CLAIM", kb, wanted.get("CLAIM"), mapper::listGraphClaims);
        collect(meta, "EVIDENCE", kb, wanted.get("EVIDENCE"), mapper::listGraphEvidence);
        collect(meta, "ENTITY", kb, wanted.get("ENTITY"), mapper::listGraphEntities);
        collect(meta, "SOURCE", kb, wanted.get("SOURCE"), mapper::listGraphSources);
        return meta;
    }

    private void collect(Map<String, Map<String, Object>> meta, String type, Long kb,
            List<String> codes, NodeLoader loader) {
        if (codes == null || codes.isEmpty()) return;
        List<Map<String, Object>> rows = loader.load(kb, codes);
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            meta.put(key(type, text(row.get("code"))), row);
        }
    }

    /** Loads display metadata for one node type. */
    private interface NodeLoader {
        List<Map<String, Object>> load(Long kb, List<String> codes);
    }

    private static void add(List<Edge> edges, List<Map<String, Object>> rows,
            String sourceType, String targetType) {
        if (rows == null) return;
        for (Map<String, Object> row : rows) {
            String source = text(row.get("sourceCode"));
            String target = text(row.get("targetCode"));
            if (source.isEmpty() || target.isEmpty()) continue;
            Object confidence = row.get("confidence");
            edges.add(new Edge(key(sourceType, source), key(targetType, target),
                    text(row.get("edgeType")), text(row.get("semanticType")),
                    confidence instanceof Number ? ((Number) confidence).doubleValue() : null));
        }
    }

    private static void link(Map<String, Set<Integer>> adjacency, String node, int index) {
        Set<Integer> set = adjacency.get(node);
        if (set == null) {
            set = new HashSet<Integer>();
            adjacency.put(node, set);
        }
        set.add(index);
    }

    private static void degree(Map<String, Integer> degree, String node) {
        Integer current = degree.get(node);
        degree.put(node, current == null ? 1 : current + 1);
    }

    private static String key(String type, String code) {
        return type + ":" + code;
    }

    private static String type(String nodeKey) {
        int separator = nodeKey.indexOf(':');
        return separator < 0 ? nodeKey : nodeKey.substring(0, separator);
    }

    private static String code(String nodeKey) {
        int separator = nodeKey.indexOf(':');
        return separator < 0 ? nodeKey : nodeKey.substring(separator + 1);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** One projected graph edge with typed endpoints. */
    private static final class Edge {
        private final String source;
        private final String target;
        private final String type;
        private final String semanticType;
        private final Double confidence;

        private Edge(String source, String target, String type, String semanticType, Double confidence) {
            this.source = source;
            this.target = target;
            this.type = type;
            this.semanticType = semanticType;
            this.confidence = confidence;
        }
    }
}
