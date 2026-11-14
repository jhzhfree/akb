package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Restricted detail must not load unverified child content, even for a visible parent. */
class KnowledgeDetailAclProjectionTest {
    private final KnowledgeMapper mapper = mock(KnowledgeMapper.class);
    private final EvidenceMapper evidence = mock(EvidenceMapper.class);
    private final GovernanceMapper governance = mock(GovernanceMapper.class);
    private final AiClient ai = mock(AiClient.class);
    private final AuthoritativeGraphNeighborhoodService graph = mock(AuthoritativeGraphNeighborhoodService.class);
    private final KnowledgeReadService service = new KnowledgeReadService(mapper, ai, graph,
        new ObjectMapper(), evidence, governance, mock(DocumentMapper.class),
        mock(CardClaimMapper.class), mock(TraceMapper.class));

    @Test void publicOnlyVisibleCardOmitsUnverifiedChildren() { restricted("CARD", Collections.emptyList()); }
    @Test void scopedVisibleClaimOmitsUnverifiedChildren() { restricted("CLAIM", Collections.singletonList("team-a")); }
    private void restricted(String type, List<String> tags) {
        Map<String, Object> parent = Collections.singletonMap("objectCode", "visible");
        when(mapper.getKnowledgeObject(7L, type, "visible", tags)).thenReturn(parent);
        Map<String, Object> result = service.knowledgeDetail(7L, type, "visible", tags);
        assertSame(parent, result.get("object"));
        assertEquals(true, result.get("detailsRestricted"));
        assertEquals(Arrays.asList("evidence", "conflicts", "reviews", "assessments", "versions", "graph"), result.get("omittedSections"));
        for (String section : Arrays.asList("evidence", "conflicts", "reviews", "assessments", "versions")) {
            assertEquals(Collections.emptyList(), result.get(section));
        }
        assertEquals(Collections.emptyMap(), result.get("graph"));
        verify(mapper).getKnowledgeObject(7L, type, "visible", tags);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(evidence, governance, ai, graph);
    }
    @Test void invisibleParentDoesNotLoadAnyChildren() {
        // MyBatis selectOne returns null for no row; Mockito's default Map answer is emptyMap.
        when(mapper.getKnowledgeObject(7L, "CARD", "private-b", Collections.singletonList("team-a")))
            .thenReturn(null);
        assertTrue(service.knowledgeDetail(7L, "CARD", "private-b", Collections.singletonList("team-a")).isEmpty());
        verify(mapper).getKnowledgeObject(7L, "CARD", "private-b", Collections.singletonList("team-a"));
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(evidence, governance, ai, graph);
    }
    @Test void restrictedChunkAndContentUnitFailBeforeAnyRead() {
        for (String type : Arrays.asList("CHUNK", "CONTENT_UNIT")) {
            for (List<String> tags : Arrays.asList(Collections.<String>emptyList(), Collections.singletonList("team-a"))) {
                SecurityException error = assertThrows(SecurityException.class,
                    () -> service.knowledgeDetail(7L, type, "private-b", tags));
                assertEquals("ACL_FILTERED_CHUNK_DETAIL_UNAVAILABLE", error.getMessage());
            }
        }
        verifyNoInteractions(mapper, evidence, governance, ai, graph);
    }
    @Test void unconstrainedObjectKeepsLegacyChildren() {
        when(mapper.getKnowledgeObject(7L, "CARD", "x", null)).thenReturn(Collections.singletonMap("objectCode", "x"));
        Map<String, Object> privateChild = Collections.singletonMap("quoteText", "legacy child");
        when(evidence.listEvidenceForObject("CARD", "x")).thenReturn(Collections.singletonList(privateChild));
        Map<String, Object> result = service.knowledgeDetail(7L, "CARD", "x", null);
        assertFalse(result.containsKey("detailsRestricted"));
        assertEquals(Collections.singletonList(privateChild), result.get("evidence"));
        verify(governance).listObjectConflicts("x");
        verify(governance).listReviewsForObject("CARD", "x");
        verify(governance).listAssessmentsForObject(7L, "CARD", "x");
        verify(mapper).listKnowledgeVersions(7L, "CARD", "x");
    }
    @Test void unconstrainedChunkKeepsLegacyNeighbors() {
        Map<String, Object> parent = new LinkedHashMap<>(); parent.put("id", 2L); parent.put("documentId", 3L);
        when(mapper.getContentUnitObject(7L, "chunk")).thenReturn(parent);
        service.knowledgeDetail(7L, "CHUNK", "chunk", null);
        verify(mapper).listEvidenceForContentUnit(7L, "chunk");
        verify(mapper).listNeighborContentUnits(3L, 2L);
    }
}
