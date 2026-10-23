package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Lossless semantic normalization for extraction identities and security label comparisons. */
public final class ExtractionCanonical {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractionCanonical() { }

    /** Keep punctuation, operators, case and word boundaries; only normalize Unicode and whitespace. */
    public static String text(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }

    /** Serialize JSON with sorted object keys without deleting punctuation inside string values. */
    public static String json(String value, String fallback) {
        try {
            Object parsed = JSON.readValue(value == null || value.trim().isEmpty() ? fallback : value, Object.class);
            return JSON.writeValueAsString(ordered(parsed));
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid extraction JSON", ex); }
    }

    /** Null and an empty array both describe public data; label spelling remains exact. */
    public static String labels(String value) {
        try {
            Object raw = JSON.readValue(value == null || value.trim().isEmpty() ? "[]" : value, Object.class);
            if (raw == null) { return "[]"; }
            if (!(raw instanceof List)) { throw new IllegalArgumentException("Labels must be an array"); }
            TreeSet<String> tags = new TreeSet<String>();
            for (Object item : (List<?>) raw) {
                if (!(item instanceof String)) { throw new IllegalArgumentException("Label must be a string"); }
                tags.add((String) item);
            }
            return JSON.writeValueAsString(tags);
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid permission labels", ex); }
    }

    private static Object ordered(Object value) {
        if (value instanceof Map) {
            Map<String, Object> out = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(entry.getKey()), ordered(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object item : (List<?>) value) { out.add(ordered(item)); }
            return out;
        }
        return value;
    }
}
