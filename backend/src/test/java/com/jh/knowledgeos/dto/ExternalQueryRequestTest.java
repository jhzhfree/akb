package com.jh.knowledgeos.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@code minScore} contract that REST / SSE / MCP all share:
 *
 * <ul>
 *   <li>Range is 0..1, inclusive — the same {@code @Min(0) @Max(1)} on the DTO that the SSE
 *       controller and the MCP argument parser mirror as explicit range checks.</li>
 *   <li>Absent means "no filtering", not 0.0. Those differ: 0.0 accepts every candidate that
 *       scored at all, while absent asks for the engine's own unfiltered recall.</li>
 * </ul>
 */
class ExternalQueryRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void notSetStaysNull() {
        // Absent → null → no filtering. Must not collapse to 0.0, which is a different request.
        assertNull(new ExternalQueryRequest().getMinScore());
    }

    @Test
    void minScoreInRangePassesValidation() {
        // Boundaries are inclusive: 0 and 1 are part of the allowed range.
        for (double v : new double[] { 0.0d, 0.6d, 1.0d }) {
            assertTrue(violationsOf(new ExternalQueryRequest(), v).isEmpty(),
                "minScore=" + v + " must be accepted");
        }
    }

    @Test
    void minScoreOutOfRangeFailsValidation() {
        for (double v : new double[] { -0.1d, 1.1d, 2.0d }) {
            Set<ConstraintViolation<ExternalQueryRequest>> vios = violationsOf(new ExternalQueryRequest(), v);
            assertEquals(1, vios.size(), "minScore=" + v + " must produce exactly one @Min/@Max violation");
            String msg = vios.iterator().next().getMessage();
            assertTrue(msg != null && (msg.contains("0") || msg.contains("1")),
                "violation message should reference the allowed range, got: " + msg);
        }
    }

    @Test
    void jacksonDeserialisesMinScore() throws Exception {
        ExternalQueryRequest r = MAPPER.readValue("{\"knowledgeBaseId\":1,\"question\":\"q\",\"minScore\":0.6}",
            ExternalQueryRequest.class);
        assertEquals(Double.valueOf(0.6d), r.getMinScore());
    }

    @Test
    void jacksonWithoutMinScoreLeavesNull() throws Exception {
        // Back-compat: a body written before this parameter existed must behave exactly as it did.
        ExternalQueryRequest r = MAPPER.readValue("{\"knowledgeBaseId\":1,\"question\":\"q\",\"topK\":3}",
            ExternalQueryRequest.class);
        assertNull(r.getMinScore());
    }

    private static Set<ConstraintViolation<ExternalQueryRequest>> violationsOf(ExternalQueryRequest r, double value) {
        // @NotBlank on `question` would otherwise add its own violation and throw off counts.
        r.setQuestion("q");
        r.setMinScore(value);
        return validator.validate(r);
    }
}
