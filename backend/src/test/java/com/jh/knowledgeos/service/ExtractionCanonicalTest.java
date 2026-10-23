package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExtractionCanonicalTest {
    @Test
    void preservesOperatorsDecimalPointsWordBoundariesAndNegation() {
        assertNotEquals(ExtractionCanonical.text("额度>=5"), ExtractionCanonical.text("额度<=5"));
        assertNotEquals(ExtractionCanonical.text("1.5天"), ExtractionCanonical.text("15天"));
        assertNotEquals(ExtractionCanonical.text("a bc"), ExtractionCanonical.text("ab c"));
        assertNotEquals(ExtractionCanonical.text("支持"), ExtractionCanonical.text("不支持"));
    }

    @Test
    void canonicalizesJsonStructureAndLabelsWithoutLosingBoundaries() {
        assertEquals(ExtractionCanonical.json("{\"b\":2,\"a\":1}", "{}"),
            ExtractionCanonical.json("{\"a\":1,\"b\":2}", "{}"));
        assertNotEquals(ExtractionCanonical.json("{\"a\":\"bc\"}", "{}"),
            ExtractionCanonical.json("{\"ab\":\"c\"}", "{}"));
        assertNotEquals(ExtractionCanonical.labels("[\"hr\",\"pay\"]"), ExtractionCanonical.labels("[\"hrpay\"]"));
        assertEquals(ExtractionCanonical.labels("[\"pay\",\"hr\"]"), ExtractionCanonical.labels("[\"hr\",\"pay\"]"));
        assertEquals("[]", ExtractionCanonical.labels(null));
        assertThrows(IllegalArgumentException.class, () -> ExtractionCanonical.labels("not-json"));
    }
}
