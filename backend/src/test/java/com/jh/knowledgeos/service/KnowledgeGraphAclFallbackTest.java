package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeGraphAclFallbackTest {
    private final AiClient ai = mock(AiClient.class);
    private final AuthoritativeGraphNeighborhoodService authoritative = mock(AuthoritativeGraphNeighborhoodService.class);
    private final KnowledgeMapper mapper = mock(KnowledgeMapper.class);
    private final KnowledgeReadService service = new KnowledgeReadService(mapper, ai, authoritative,
        new ObjectMapper(), mock(EvidenceMapper.class), mock(GovernanceMapper.class),
        mock(DocumentMapper.class), mock(CardClaimMapper.class), mock(TraceMapper.class));

    @Test void publicOnlyNeverFallsBackOnEmptyGraph() { rejects(Collections.emptyList(), false); }
    @Test void scopedNeverFallsBackOnEmptyGraph() { rejects(Collections.singletonList("team-a"), false); }
    @Test void publicOnlyNeverFallsBackOnProviderFailure() { rejects(Collections.emptyList(), true); }
    @Test void scopedNeverFallsBackOnProviderFailure() { rejects(Collections.singletonList("team-a"), true); }
    private void rejects(List<String> tags, boolean fails) {
        if (fails) when(ai.get(anyString())).thenThrow(new IllegalStateException("unavailable"));
        else when(ai.get(anyString())).thenReturn(Collections.singletonMap("edges", Collections.emptyList()));
        SecurityException ex = assertThrows(SecurityException.class, () -> service.graph(7L, "x", tags, 2, 80));
        assertTrue(ex.getMessage().contains("ACL_FILTERED_GRAPH_UNAVAILABLE"));
        verifyNoInteractions(authoritative);
    }
    @Test void restrictedNullProviderResponseNeverFallsBack() {
        assertThrows(SecurityException.class, () -> service.graph(7L, "x", Collections.emptyList(), null, null));
        verifyNoInteractions(authoritative);
    }
    @Test void explicitlyUnconstrainedCallRetainsFallback() {
        when(authoritative.neighborhood(7L, "x", 2, 80)).thenReturn(new LinkedHashMap<>());
        assertEquals("authoritative", service.graph(7L, "x", null, null, null).get("source"));
        verify(authoritative).neighborhood(7L, "x", 2, 80);
    }
    @Test void filteredNonemptyGraphIsPreserved() {
        Map<String, Object> served = new LinkedHashMap<>();
        served.put("edges", Collections.singletonList(Collections.singletonMap("source", "public")));
        when(ai.get(anyString())).thenReturn(served);
        assertSame(served, service.graph(7L, "x", Collections.emptyList(), null, null));
        assertEquals("neo4j", served.get("source"));
        verify(ai).get("/ai/graph/7/x?depth=2&limit=80&permissionTags=");
        verifyNoInteractions(authoritative);
    }
    @Test void knowledgeDetailDoesNotAttachUnfilteredFallback() {
        when(mapper.getKnowledgeObject(7L, "CARD", "x", Collections.emptyList()))
            .thenReturn(Collections.singletonMap("code", "x"));
        Map<String, Object> detail = service.knowledgeDetail(7L, "CARD", "x", Collections.emptyList());
        assertEquals(Collections.emptyMap(), detail.get("graph"));
        verifyNoInteractions(authoritative);
    }
    @Test void ambiguousTagsFailBeforeAnyProviderCall() {
        for (String tag : Arrays.asList("a,b", "a&permissionTags=b", "a#x", "a%2Cb", "a?x", "a+b", "a;b", "a=b", " a", "a ", "a\n", "", null)) {
            SecurityException ex = assertThrows(SecurityException.class,
                () -> service.graph(7L, "x", Collections.singletonList(tag), 2, 80));
            assertTrue(ex.getMessage().contains("ACL_GRAPH_TAG_TRANSPORT_UNSAFE"));
        }
        verifyNoInteractions(ai, authoritative);
    }
    @Test void ordinaryScopedTagRetainsItsExactGrant() {
        Map<String, Object> served = new LinkedHashMap<>();
        served.put("edges", Collections.singletonList(Collections.singletonMap("source", "x")));
        when(ai.get(anyString())).thenReturn(served);
        service.graph(7L, "x", Collections.singletonList("team-a"), 2, 80);
        verify(ai).get("/ai/graph/7/x?depth=2&limit=80&permissionTags=team-a");
        verifyNoInteractions(authoritative);
    }
    @Test void unicodeWhitespaceCannotRewriteGrantedTags() {
        // Python str.strip also recognizes these non-ASCII separators.
        for (String whitespace : Arrays.asList("\u00a0", "\u0085", "\u2000", "\u2028", "\u2029", "\u202f", "\u205f", "\u3000")) {
            for (String tag : Arrays.asList(whitespace + "team-a", "team-a" + whitespace, "team" + whitespace + "-a")) {
                SecurityException ex = assertThrows(SecurityException.class,
                    () -> service.graph(7L, "x", Collections.singletonList(tag), 2, 80));
                assertTrue(ex.getMessage().contains("ACL_GRAPH_TAG_TRANSPORT_UNSAFE"));
            }
        }
        verifyNoInteractions(ai, authoritative);
    }
}
