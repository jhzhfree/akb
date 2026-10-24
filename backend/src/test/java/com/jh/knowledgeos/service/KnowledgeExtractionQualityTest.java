package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.mapper.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class KnowledgeExtractionQualityTest {
    final KnowledgeMapper mapper = mock(KnowledgeMapper.class);
    final CardClaimMapper cards = mock(CardClaimMapper.class);
    final DocumentMapper documents = mock(DocumentMapper.class);
    final KbScenarioService scenarios = mock(KbScenarioService.class);
    final ExtractionModelGate gate = mock(ExtractionModelGate.class);
    final TransactionRunner tx = mock(TransactionRunner.class);
    final AiClient ai = mock(AiClient.class);
    final IndexOutboxService outbox = mock(IndexOutboxService.class);
    final ExtractionPostStageMapper postStages = mock(ExtractionPostStageMapper.class);
    final Map<Long, com.jh.knowledgeos.domain.ExtractionPostStage> stageRows = new HashMap<Long,
        com.jh.knowledgeos.domain.ExtractionPostStage>();
    final KnowledgeExtractionService service;

    @SuppressWarnings("unchecked")
    KnowledgeExtractionQualityTest() {
        when(tx.retryingOnDeadlock(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        when(tx.required(any(Supplier.class))).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        final long[] stageId = {0L};
        when(postStages.insertStage(any())).thenAnswer(inv -> {
            com.jh.knowledgeos.domain.ExtractionPostStage stage = inv.getArgument(0);
            stage.setId(++stageId[0]);
            stageRows.put(stage.getId(), stage);
            return 1;
        });
        when(postStages.listStagesForRun(anyLong(), anyString())).thenAnswer(inv -> {
            List<com.jh.knowledgeos.domain.ExtractionPostStage> matching = new ArrayList<>();
            for (com.jh.knowledgeos.domain.ExtractionPostStage stage : stageRows.values()) {
                if (stage.getRunId().equals(inv.getArgument(1))) { matching.add(stage); }
            }
            return matching;
        });
        when(postStages.claimStage(anyLong(), anyString())).thenAnswer(inv -> {
            com.jh.knowledgeos.domain.ExtractionPostStage stage = stageRows.get(inv.getArgument(0));
            if (stage == null || !"PENDING".equals(stage.getStatus())) { return 0; }
            stage.setStatus("PROCESSING");
            stage.setAttemptCount((stage.getAttemptCount() == null ? 0 : stage.getAttemptCount()) + 1);
            return 1;
        });
        when(postStages.findStage(anyLong())).thenAnswer(inv -> stageRows.get(inv.getArgument(0)));
        when(postStages.markStageSucceeded(anyLong())).thenAnswer(inv -> {
            stageRows.get(inv.getArgument(0)).setStatus("SUCCEEDED");
            return 1;
        });
        when(postStages.markStageRetry(anyLong(), anyString(), anyString(), any())).thenAnswer(inv -> {
            stageRows.get(inv.getArgument(0)).setStatus(inv.getArgument(1));
            stageRows.get(inv.getArgument(0)).setLastError(inv.getArgument(2));
            return 1;
        });
        when(scenarios.extraction(1L)).thenReturn(row("claims", true));
        when(documents.findReextractContext(9L)).thenReturn(row("knowledgeBaseId", 1L, "permissionTags", "[]"));
        when(documents.lockDocumentForExtraction(9L, 1L)).thenReturn(9L);
        when(documents.findUnitsWithEvidence(9L)).thenReturn(Arrays.asList(row("evidence_id", 3L, "evidence_code", "EV1",
            "content", "年假应提前三个工作日申请。报销上限为100元。")));
        when(cards.insertCard(any())).thenReturn(1);
        when(cards.insertClaim(any())).thenReturn(1);
        IdGenerator ids = mock(IdGenerator.class);
        when(ids.next(anyString())).thenAnswer(inv -> "K_TEST_" + UUID.randomUUID().toString().replace("-", ""));
        service = new KnowledgeExtractionService(mapper, ai, new ObjectMapper(), ids, tx, outbox,
            mock(EntityResolutionService.class), mock(PlatformEventPublisher.class), mock(EvidenceMapper.class),
            mock(GovernanceMapper.class), mock(GraphMapper.class), mock(IndexMapper.class), cards, scenarios, documents, gate,
            postStages);
    }

    static Map<String, Object> row(Object... entries) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) { row.put((String) entries[i], entries[i + 1]); }
        return row;
    }

    Map<String, Object> card(String content) {
        return row("title", "申请要求", "summary", content, "content", content, "type", "RULE", "evidence_ids", Arrays.asList("EV1"));
    }

    Map<String, Object> extract(List<Map<String, Object>> input) {
        when(gate.extract(eq(ai), anyMap())).thenReturn(row("cards", input, "claims", Collections.emptyList()));
        return service.extract(row("knowledgeBaseId", 1L, "documentId", 9L));
    }

    @Test
    void sameTitleDifferentContentAndShortBodyEqualToSummaryAreRetained() {
        Map<String, Object> result = extract(Arrays.asList(card("年假应提前三个工作日申请。"), card("报销上限为100元。")));
        assertEquals(2, ((List<?>) result.get("cards")).size());
        verify(cards, times(2)).insertCard(any());
        verify(cards, never()).linkCardEvidenceFromDocument(anyString(), anyLong());
    }

    @Test
    void suppliedUnknownEvidenceRejectsCandidateWithoutWholeDocumentFallback() {
        Map<String, Object> candidate = card("年假应提前三个工作日申请。");
        candidate.put("evidence_ids", Arrays.asList("EV1", "OTHER_DOCUMENT"));
        Map<String, Object> result = extract(Arrays.asList(candidate));
        assertEquals("QUALITY_REJECTED", result.get("extractionStatus"));
        assertTrue(((List<?>) result.get("cards")).isEmpty());
        verify(cards, never()).insertCard(any());
        verify(cards, never()).linkCardEvidenceFromDocument(anyString(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rolledBackAttemptDoesNotInflateCommittedCounters() {
        when(tx.retryingOnDeadlock(any(Supplier.class))).thenAnswer(inv -> {
            Supplier<?> work = inv.getArgument(0);
            work.get(); // Simulate a rolled-back attempt; its counters must be discarded.
            return work.get();
        });
        Map<String, Object> result = extract(Arrays.asList(card("年假应提前三个工作日申请。")));
        Map<String, Object> stats = (Map<String, Object>) result.get("extractionStats");
        assertEquals(1, ((Map<String, Object>) stats.get("card")).get("created"));
    }

    @Test
    void replayReusesCommittedIdentityAfterDocumentLock() {
        final Map<String, String> identities = new HashMap<String, String>();
        when(cards.findCardCodeByDedupKey(eq(1L), anyString())).thenAnswer(inv -> identities.get(inv.getArgument(1)));
        when(cards.insertCard(any())).thenAnswer(inv -> {
            KnowledgeCard card = inv.getArgument(0);
            identities.put(card.getDedupKey(), card.getKnowledgeCode());
            return 1;
        });
        extract(Arrays.asList(card("年假应提前三个工作日申请。")));
        Map<String, Object> second = extract(Arrays.asList(card("年假应提前三个工作日申请。")));
        assertTrue(((List<?>) second.get("cards")).isEmpty());
        assertEquals("EXTRACTED", second.get("extractionStatus"));
        verify(cards, times(1)).insertCard(any());
        verify(documents, times(2)).lockDocumentForExtraction(9L, 1L);
    }

    @Test
    void postProcessingFailureRetainsCreatedClaimsAndReturnsPartial() {
        Map<String, Object> policy = row("claims", true, "conflictDetection", true);
        when(scenarios.extraction(1L)).thenReturn(policy);
        com.jh.knowledgeos.domain.Claim claim = new com.jh.knowledgeos.domain.Claim();
        claim.setClaimCode("C1");
        claim.setSubject("员工");
        claim.setPredicate("要求");
        claim.setObjectValue("提前提交申请");
        when(cards.listClaimsBySubjects(eq(1L), anyList())).thenReturn(Arrays.asList(claim));
        when(ai.post(eq(AiApiPaths.DETECT_CONFLICTS), anyMap())).thenThrow(new IllegalStateException("model unavailable"));
        when(gate.extract(eq(ai), anyMap())).thenReturn(row("cards", Collections.emptyList(), "claims", Arrays.asList(
            row("subject", "员工", "predicate", "要求", "object", "提前提交申请", "evidence_ids", Arrays.asList("EV1")))));
        Map<String, Object> result = service.extract(row("knowledgeBaseId", 1L, "documentId", 9L));
        assertEquals("PARTIAL", result.get("extractionStatus"));
        assertEquals(1, ((List<?>) result.get("claims")).size());
    }

    @Test
    void documentExtractionRegistersAllPostStagesWithStableRunKeys() {
        extract(Arrays.asList(card("年假应提前三个工作日申请。")));
        org.mockito.ArgumentCaptor<com.jh.knowledgeos.domain.ExtractionPostStage> captor =
            org.mockito.ArgumentCaptor.forClass(com.jh.knowledgeos.domain.ExtractionPostStage.class);
        verify(postStages, times(4)).insertStage(captor.capture());
        Set<String> codes = new HashSet<String>();
        Set<String> keys = new HashSet<String>();
        for (com.jh.knowledgeos.domain.ExtractionPostStage stage : captor.getAllValues()) {
            codes.add(stage.getStageCode());
            keys.add(stage.getIdempotencyKey());
            assertEquals(9L, stage.getDocumentId());
            assertNotNull(stage.getRunId());
            assertTrue(stage.getIdempotencyKey().startsWith("EXTRACT_POST:" + stage.getRunId() + ":"));
        }
        assertEquals(new HashSet<String>(Arrays.asList("GRAPH_INDEX", "CONFLICT_DETECTION", "ENTITY_RESOLUTION", "EVENT_PUBLISHED")), codes);
        assertEquals(3, keys.size());
    }
}
