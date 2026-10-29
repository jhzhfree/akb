package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.common.KbRetrievalException;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.KnowledgeBase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contract conversion and knowledge-base selection for the third-party retrieval endpoint. */
class KbRetrievalServiceTest {

    private ExternalKnowledgeFacade facade;

    private KnowledgeBaseService bases;

    private KbRetrievalService service;

    @BeforeEach
    void setUp() {
        facade = mock(ExternalKnowledgeFacade.class);
        bases = mock(KnowledgeBaseService.class);
        service = new KbRetrievalService(facade, bases);
    }

    @Test
    void boundKeyUsesItsKnowledgeBaseWhenHeaderIsOmitted() {
        ApiKey key = new ApiKey();
        key.setKnowledgeBaseId(41L);

        assertEquals(Long.valueOf(41L), service.resolveKnowledgeBase(key, null, null));
    }

    @Test
    void resolvesNumericIdAndBaseCode() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(41L);
        base.setBaseCode("HR-POLICY");
        when(bases.get(41L)).thenReturn(base);
        when(bases.getExternalByCode("hr-policy")).thenReturn(base);

        assertEquals(Long.valueOf(41L), service.resolveKnowledgeBase(null, "41", null));
        assertEquals(Long.valueOf(41L), service.resolveKnowledgeBase(null, null, "hr-policy"));
    }

    @Test
    void missingOrUnknownKnowledgeBaseReturnsProtocolError() {
        KbRetrievalException missing = assertThrows(KbRetrievalException.class,
            () -> service.resolveKnowledgeBase(null, null, null));
        assertEquals(400, missing.getStatus());
        assertEquals("INVALID_ARGUMENT", missing.getCode());

        KbRetrievalException unknown = assertThrows(KbRetrievalException.class,
            () -> service.resolveKnowledgeBase(null, "999", null));
        assertEquals(404, unknown.getStatus());
        assertEquals("KB_NOT_FOUND", unknown.getCode());
    }

    @Test
    void retrievalReturnsTopKSortedChunksAndOptionalAnswer() throws Exception {
        Map<String, Object> high = candidate("EVD_HIGH", "high", 0.9, null);
        Map<String, Object> middle = candidate("EVD_MIDDLE", "middle", 0.4, null);
        Map<String, Object> reranked = candidate("EVD_RERANKED", "reranked", 0.1, 0.8);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("candidates", Arrays.asList(middle, high, reranked));
        result.put("answer", "结论");
        result.put("answerFormat", "text");
        result.put("queryId", "Q-1");
        result.put("confidence", 0.88);
        result.put("conclusionSource", "EVIDENCE");
        result.put("citations", Arrays.<Object>asList(citation()));
        when(facade.retrieval(any(ApiKey.class), eq(41L), eq("policy?"), eq(2), isNull(), eq("REST"),
            isNull(), eq(true), eq(false))).thenReturn(result);

        Map<String, Object> response = service.retrieve(new ApiKey(), 41L, "policy?", 2, true, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) response.get("chunks");
        assertEquals(2, chunks.size());
        assertEquals("EVD_RERANKED", chunks.get(0).get("id"));
        assertEquals("EVD_HIGH", chunks.get(1).get("id"));
        assertEquals(0.8, ((Number) chunks.get(0).get("score")).doubleValue(), 0.00001);
        assertTrue(chunks.get(0).containsKey("metadata"));

        @SuppressWarnings("unchecked")
        Map<String, Object> answer = (Map<String, Object>) response.get("answer");
        assertEquals("ok", answer.get("status"));
        assertEquals("Q-1", answer.get("queryId"));
        assertEquals("结论", answer.get("text"));
        assertEquals("EVIDENCE", answer.get("conclusionSource"));
        assertEquals(1, ((List<?>) answer.get("citations")).size());
    }

    @Test
    void retrievalOnlyOmitsAnswerAndReturnsEmptyChunksForNoCandidates() throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("candidates", new ArrayList<Object>());
        when(facade.retrieval(any(ApiKey.class), eq(41L), eq("no hit"), eq(5), isNull(), eq("REST"),
            isNull(), eq(false), eq(false))).thenReturn(result);

        Map<String, Object> response = service.retrieve(new ApiKey(), 41L, "no hit", 5, false, false);

        assertEquals(0, ((List<?>) response.get("chunks")).size());
        assertFalse(response.containsKey("answer"));
        verify(facade).retrieval(any(ApiKey.class), eq(41L), eq("no hit"), eq(5), isNull(), eq("REST"),
            isNull(), eq(false), eq(false));
    }

    private static Map<String, Object> candidate(
        String evidenceCode, String content, double score, Double rerankScore) {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put("evidenceCode", evidenceCode);
        metadata.put("sourceName", "policy.pdf");
        Map<String, Object> candidate = new HashMap<String, Object>();
        candidate.put("content", content);
        candidate.put("score", score);
        candidate.put("rerankScore", rerankScore);
        candidate.put("metadata", metadata);
        return candidate;
    }

    private static Map<String, Object> citation() {
        Map<String, Object> citation = new HashMap<String, Object>();
        citation.put("index", 1);
        citation.put("evidenceCode", "EVD_HIGH");
        citation.put("title", "员工政策");
        citation.put("excerpt", "相关条款");
        return citation;
    }
}
