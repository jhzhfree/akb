package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ConnectorItem;
import com.jh.knowledgeos.domain.ConnectorSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a connector is allowed to turn into knowledge, and how much of it at a time.
 *
 * <p>Kept in the connector's own {@code config_json} next to the type-specific settings: a Jira
 * connector already carries its JQL there, and the ingest knobs are the same kind of per-source
 * decision. No table change, and a connector that says nothing gets the conservative defaults.</p>
 *
 * <p>The defaults are deliberately cautious: manual ingestion, a small quota, everything in scope.
 * Remote systems hold orders of magnitude more noise than a curated document set, and extraction
 * costs a model call per item, so nothing runs on its own until an operator turns it on.</p>
 */
public final class ConnectorIngestConfig {

    /** Manual by default: syncing refreshes the catalog, ingestion stays a human decision. */
    public static final String MODE_MANUAL = "MANUAL";

    public static final String MODE_AUTO = "AUTO";

    /** One automatic pass stays small enough that a sync still answers in reasonable time. */
    public static final int DEFAULT_QUOTA = 20;

    public static final int MAX_QUOTA = 200;

    /** How many catalog rows one automatic pass looks at before deciding. */
    public static final int SCAN_LIMIT = 500;

    /** Remote content is trusted a little less than an uploaded file unless told otherwise. */
    private static final double DEFAULT_AUTHORITY = 0.70D;

    private final double authorityScore;

    private final String permissionTags;

    private final String mode;

    private final int quota;

    private final Set<String> types;

    private final List<String[]> attributeRules;

    private ConnectorIngestConfig(double authorityScore, String permissionTags, String mode, int quota, Set<String> types,
        List<String[]> attributeRules) {
        this.authorityScore = authorityScore;
        this.permissionTags = permissionTags;
        this.mode = mode;
        this.quota = quota;
        this.types = types;
        this.attributeRules = attributeRules;
    }

    public static ConnectorIngestConfig of(ConnectorSource source, ObjectMapper objectMapper) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        if (source.getConfig() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) source.getConfig();
            config.putAll(parsed);
        }
        String json = source.getConfigJson();
        if (json != null && !json.trim().isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                if (parsed != null) {
                    config.putAll(parsed);
                }
            } catch (Exception ex) {
                // A malformed config must not stop a sync; fall back to the defaults.
            }
        }
        return new ConnectorIngestConfig(authority(config), text(config.get("permissionTags")), mode(config), quota(config), types(config),
            attributeRules(config));
    }

    /**
     * Whether an item is in scope. An empty scope means everything: connectors are usually pointed
     * at one project already, and asking people to restate that as a filter invites mistakes.
     */
    public boolean accepts(ConnectorItem item) {
        if (!types.isEmpty()) {
            String type = item.getItemType() == null ? "" : item.getItemType().trim();
            if (!types.contains(type)) {
                return false;
            }
        }
        if (attributeRules.isEmpty()) {
            return true;
        }
        Map<String, Object> attributes = attributes(item);
        for (String[] rule : attributeRules) {
            Object value = attributes.get(rule[0]);
            if (value == null || !rule[1].equalsIgnoreCase(String.valueOf(value).trim())) {
                return false;
            }
        }
        return true;
    }

    public boolean auto() {
        return MODE_AUTO.equalsIgnoreCase(mode);
    }

    public double authorityScore() {
        return authorityScore;
    }

    public String permissionTags() {
        return permissionTags;
    }

    public int quota() {
        return quota;
    }

    public String mode() {
        return mode;
    }

    public Map<String, Object> describe() {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("mode", mode);
        view.put("auto", auto());
        view.put("quota", quota);
        view.put("authorityScore", authorityScore);
        view.put("permissionTags", permissionTags);
        view.put("types", new ArrayList<String>(types));
        List<String> attributes = new ArrayList<String>(attributeRules.size());
        for (String[] rule : attributeRules) {
            attributes.add(rule[0] + "=" + rule[1]);
        }
        view.put("attributes", attributes);
        return view;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributes(ConnectorItem item) {
        if (item.getAttributes() != null) {
            return item.getAttributes();
        }
        String json = item.getAttributesJson();
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private static double authority(Map<String, Object> config) {
        Object value = config.get("authorityScore");
        if (value == null) {
            return DEFAULT_AUTHORITY;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return parsed <= 0D || parsed > 1D ? DEFAULT_AUTHORITY : parsed;
        } catch (NumberFormatException ex) {
            return DEFAULT_AUTHORITY;
        }
    }

    private static String mode(Map<String, Object> config) {
        String value = text(config.get("ingestMode"));
        return MODE_AUTO.equalsIgnoreCase(value) ? MODE_AUTO : MODE_MANUAL;
    }

    private static int quota(Map<String, Object> config) {
        Object value = config.get("ingestQuota");
        if (value == null) {
            return DEFAULT_QUOTA;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value).trim());
            return Math.min(Math.max(parsed, 1), MAX_QUOTA);
        } catch (NumberFormatException ex) {
            return DEFAULT_QUOTA;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> types(Map<String, Object> config) {
        Set<String> types = new LinkedHashSet<String>();
        Object scope = config.get("ingestScope");
        if (scope instanceof Map) {
            Object raw = ((Map<String, Object>) scope).get("types");
            if (raw instanceof Iterable) {
                for (Object entry : (Iterable<Object>) raw) {
                    String type = text(entry);
                    if (!type.isEmpty()) {
                        types.add(type);
                    }
                }
            }
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> attributeRules(Map<String, Object> config) {
        List<String[]> rules = new ArrayList<String[]>();
        Object scope = config.get("ingestScope");
        if (!(scope instanceof Map)) {
            return rules;
        }
        Object raw = ((Map<String, Object>) scope).get("attributes");
        if (!(raw instanceof Iterable)) {
            return rules;
        }
        for (Object entry : (Iterable<Object>) raw) {
            String text = text(entry);
            int split = text.indexOf('=');
            if (split <= 0 || split == text.length() - 1) {
                continue;
            }
            rules.add(new String[] { text.substring(0, split).trim(), text.substring(split + 1).trim() });
        }
        return rules;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
