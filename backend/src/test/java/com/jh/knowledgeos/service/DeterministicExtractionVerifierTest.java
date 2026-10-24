package com.jh.knowledgeos.service;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicExtractionVerifierTest {
    private final DeterministicExtractionVerifier verifier = new DeterministicExtractionVerifier();

    @Test
    void aForgedDeterministicMarkerCannotApproveUnsupportedCardContent() {
        Map<String, Object> candidate = row("derivation", "DETERMINISTIC", "title", "年假规定",
            "content", "所有员工均可无限期休假", "summary", "所有员工均可无限期休假", "evidence_ids", Arrays.asList("EV1"));
        Map<String, Object> audit = verifier.auditCard(candidate, evidence());
        assertFalse((Boolean) audit.get("verified"));
        assertEquals("EVIDENCE_OR_CONTENT_MISMATCH", ((List<?>) audit.get("reasonCodes")).get(0));
        assertTrue(((List<?>) audit.get("evidenceAnchors")).isEmpty());
    }

    @Test
    void directlyQuotedCardHasVersionedEvidenceAnchor() {
        Map<String, Object> candidate = row("derivation", "DETERMINISTIC", "title", "年假规定",
            "content", "年假应提前三个工作日申请。", "summary", "年假应提前三个工作日申请。", "evidence_ids", Arrays.asList("EV1"));
        Map<String, Object> audit = verifier.auditCard(candidate, evidence());
        assertTrue((Boolean) audit.get("verified"));
        assertEquals(DeterministicExtractionVerifier.VERSION, audit.get("verifierVersion"));
        assertEquals("EV1", ((Map<?, ?>) ((List<?>) audit.get("evidenceAnchors")).get(0)).get("evidenceCode"));
    }

    @Test
    void clauseExtractionRejectsLostQualifiersAndUnknownEvidence() {
        Map<String, Object> valid = row("derivation", "DETERMINISTIC", "subject", "小李", "predicate", "年假额度",
            "object", "5天", "conditions", Collections.emptyList(), "qualifiers", Collections.emptyMap(),
            "valid_from", "", "valid_to", "", "evidence_ids", Arrays.asList("EV1"));
        assertTrue(verifier.verifyClaim(valid, evidence()));
        valid.put("qualifiers", Collections.singletonMap("minimum", Boolean.TRUE));
        assertFalse(verifier.verifyClaim(valid, evidence()));
        valid.put("qualifiers", Collections.emptyMap());
        valid.put("evidence_ids", Arrays.asList("OTHER_KB_EVIDENCE"));
        assertFalse(verifier.verifyClaim(valid, evidence()));
    }

    private List<Map<String, Object>> evidence() {
        return Collections.singletonList(row("evidence_code", "EV1", "unit_id", "UNIT_1", "heading_path", "年假规定",
            "content", "员工: 小李; 年假额度: 5天\n年假应提前三个工作日申请。"));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) { result.put((String) values[i], values[i + 1]); }
        return result;
    }
}
