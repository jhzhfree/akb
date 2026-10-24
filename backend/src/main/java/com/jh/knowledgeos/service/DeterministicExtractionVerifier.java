package com.jh.knowledgeos.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independently checks the narrow set of extraction candidates eligible for automatic approval.
 * A derivation label is a claim from the extractor, not proof by itself.
 */
public class DeterministicExtractionVerifier {
    public static final String VERSION = "DV1";
    private static final Pattern CLAUSE_FIELD = Pattern.compile("^\\s*([^:：]{1,28})\\s*[:：]\\s*(.+?)\\s*$");

    public boolean verifyCard(Map<String, Object> candidate, List<Map<String, Object>> evidencePool) {
        if (!markedDeterministic(candidate)) { return false; }
        String content = ExtractionCanonical.text(value(candidate.get("content")));
        String summary = ExtractionCanonical.text(value(candidate.get("summary")));
        String title = ExtractionCanonical.text(value(candidate.get("title")));
        if (content.isEmpty() || title.isEmpty()) { return false; }
        for (Map<String, Object> evidence : evidenceFor(candidate, evidencePool)) {
            String body = ExtractionCanonical.text(value(evidence.get("content")));
            String heading = ExtractionCanonical.text(value(evidence.get("heading_path")));
            if (body.contains(content) && (summary.isEmpty() || body.contains(summary))
                && (body.contains(title) || heading.contains(title))) {
                return true;
            }
        }
        return false;
    }

    public boolean verifyClaim(Map<String, Object> candidate, List<Map<String, Object>> evidencePool) {
        if (!markedDeterministic(candidate) || !isEmptyCollection(candidate.get("conditions"))
            || !isEmptyCollection(candidate.get("qualifiers")) || !value(candidate.get("valid_from")).trim().isEmpty()
            || !value(candidate.get("valid_to")).trim().isEmpty()) {
            return false;
        }
        String subject = ExtractionCanonical.text(value(candidate.get("subject")));
        String predicate = ExtractionCanonical.text(value(candidate.get("predicate")));
        String object = ExtractionCanonical.text(value(candidate.get("object")));
        if (subject.isEmpty() || predicate.isEmpty() || object.isEmpty()) { return false; }
        for (Map<String, Object> evidence : evidenceFor(candidate, evidencePool)) {
            if (containsClauseRow(evidence.get("content"), subject, predicate, object)) { return true; }
        }
        return false;
    }

    /** Compact, persisted explanation for the server-side approval decision. */
    public Map<String, Object> auditCard(Map<String, Object> candidate, List<Map<String, Object>> evidencePool) {
        return audit("CARD", candidate, evidencePool, verifyCard(candidate, evidencePool));
    }

    /** Compact, persisted explanation for the server-side approval decision. */
    public Map<String, Object> auditClaim(Map<String, Object> candidate, List<Map<String, Object>> evidencePool) {
        return audit("CLAIM", candidate, evidencePool, verifyClaim(candidate, evidencePool));
    }

    private Map<String, Object> audit(String type, Map<String, Object> candidate, List<Map<String, Object>> evidencePool,
        boolean verified) {
        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("verifierVersion", VERSION);
        result.put("verified", verified);
        boolean marked = markedDeterministic(candidate);
        result.put("reasonCodes", Collections.singletonList(!marked ? "MARKER_ABSENT" : verified ? "DIRECT_EVIDENCE_MATCH"
            : "EVIDENCE_OR_CONTENT_MISMATCH"));
        List<Map<String, Object>> anchors = new java.util.ArrayList<Map<String, Object>>();
        if (verified && candidate != null && candidate.get("evidence_ids") instanceof List && evidencePool != null) {
            for (Object id : (List<?>) candidate.get("evidence_ids")) {
                for (Map<String, Object> evidence : evidencePool) {
                    if (id != null && String.valueOf(id).equals(String.valueOf(evidence.get("evidence_code")))) {
                        Map<String, Object> anchor = new java.util.LinkedHashMap<String, Object>();
                        anchor.put("objectType", type);
                        anchor.put("evidenceCode", id);
                        anchor.put("unitId", evidence.get("unit_id"));
                        anchor.put("headingPath", evidence.get("heading_path"));
                        anchors.add(anchor);
                        break;
                    }
                }
            }
        }
        result.put("evidenceAnchors", anchors);
        return result;
    }

    /** Mirror the extractor's narrow key/value row parser instead of matching unrelated text. */
    private boolean containsClauseRow(Object rawContent, String subject, String predicate, String object) {
        String content = value(rawContent);
        for (String line : content.split("\\r?\\n")) {
            boolean subjectFound = false;
            boolean attributeFound = false;
            for (String segment : line.split("[；;]")) {
                Matcher matched = CLAUSE_FIELD.matcher(segment);
                if (!matched.matches()) { continue; }
                String key = ExtractionCanonical.text(matched.group(1));
                String fieldValue = ExtractionCanonical.text(matched.group(2));
                if (subject.equals(fieldValue)) { subjectFound = true; }
                if (predicate.equals(key) && object.equals(fieldValue)) { attributeFound = true; }
            }
            if (subjectFound && attributeFound) { return true; }
        }
        return false;
    }

    private boolean markedDeterministic(Map<String, Object> candidate) {
        return candidate != null && "DETERMINISTIC".equalsIgnoreCase(value(candidate.get("derivation")).trim());
    }

    private List<Map<String, Object>> evidenceFor(Map<String, Object> candidate, List<Map<String, Object>> evidencePool) {
        Object rawIds = candidate.get("evidence_ids");
        if (!(rawIds instanceof List) || ((List<?>) rawIds).isEmpty() || evidencePool == null) { return Collections.emptyList(); }
        for (Object rawId : (List<?>) rawIds) {
            if (rawId == null) { return Collections.emptyList(); }
            boolean found = false;
            for (Map<String, Object> evidence : evidencePool) {
                if (String.valueOf(rawId).equals(String.valueOf(evidence.get("evidence_code")))) {
                    found = true;
                    break;
                }
            }
            if (!found) { return Collections.emptyList(); }
        }
        java.util.ArrayList<Map<String, Object>> matched = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> evidence : evidencePool) {
            for (Object rawId : (List<?>) rawIds) {
                if (String.valueOf(rawId).equals(String.valueOf(evidence.get("evidence_code")))) {
                    matched.add(evidence);
                    break;
                }
            }
        }
        return matched;
    }

    private boolean isEmptyCollection(Object value) {
        return value == null || value instanceof List && ((List<?>) value).isEmpty()
            || value instanceof Map && ((Map<?, ?>) value).isEmpty();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
