package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.KnowledgeStatus;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application service that converts parsed evidence into cards/claims and enqueues their retrieval-index updates.
 */
@Service
public class KnowledgeExtractionService {
    private final KnowledgeMapper mapper;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final TransactionRunner transactionRunner;
    private final IndexOutboxService outboxService;

    public KnowledgeExtractionService(
            KnowledgeMapper mapper,
            AiClient aiClient,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TransactionRunner transactionRunner,
            IndexOutboxService outboxService) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
    }

    /**
     * Runs AI extraction outside the database transaction, persists cards/claims in a
     * short transaction, and enqueues rebuildable retrieval-view updates.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extract(Map<String, Object> request) {
        Map<String, Object> response = aiClient.post(AiApiPaths.EXTRACT, request);
        Long knowledgeBaseId = Long.valueOf(String.valueOf(request.get("knowledgeBaseId")));
        Long documentId = request.get("documentId") == null
                ? null
                : Long.valueOf(String.valueOf(request.get("documentId")));

        PersistedKnowledge persisted = transactionRunner.required(() -> {
            List<Map<String, Object>> cards = persistCards(
                    knowledgeBaseId, documentId, response.get("cards"));
            List<Map<String, Object>> claims = persistClaims(
                    knowledgeBaseId, documentId, response.get("claims"));
            return new PersistedKnowledge(cards, claims);
        });
        List<Map<String, Object>> savedCards = persisted.cards;
        List<Map<String, Object>> savedClaims = persisted.claims;

        Map<String, Object> graphPayload = buildKnowledgeIndexPayload(
                knowledgeBaseId,
                new ArrayList<Map<String, Object>>(),
                new ArrayList<Map<String, Object>>(),
                response.get("entities"),
                response.get("relations"));
        String graphKey = "KB:" + knowledgeBaseId + ":GRAPH:" + (documentId == null ? "MANUAL" : documentId);
        Map<String, Object> graphEvent = transactionRunner.required(() -> outboxService.enqueue(
                knowledgeBaseId,
                "GRAPH",
                graphKey,
                "GRAPH_BATCH_UPSERT",
                AiApiPaths.INDEX_KNOWLEDGE,
                graphPayload,
                documentId == null
                        ? "GRAPH_EXTRACT:" + graphKey + ":" + UUID.randomUUID().toString()
                        : "GRAPH_EXTRACT:" + graphKey + ":1"));
        Map<String, Object> indexSummary = new LinkedHashMap<String, Object>();
        indexSummary.put("mode", "transactional-outbox");
        indexSummary.put("cardEvents", savedCards.size());
        indexSummary.put("claimEvents", savedClaims.size());
        indexSummary.put("graphEvent", graphEvent);
        response.put("knowledgeIndex", indexSummary);

        response.put("conflicts", detectAndPersistConflicts(knowledgeBaseId, savedClaims));
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> persistCards(
            Long knowledgeBaseId, Long documentId, Object cardsObject) {
        List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
        if (!(cardsObject instanceof List)) {
            return saved;
        }

        for (Object item : (List<Object>) cardsObject) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<String, Object>((Map<String, Object>) item);
            KnowledgeCard card = new KnowledgeCard();
            card.setKnowledgeCode(valueOrId(source.get("knowledge_code"), "K_"));
            card.setKnowledgeBaseId(knowledgeBaseId);
            card.setType(stringValue(source.get("type")));
            card.setTitle(stringValue(source.get("title")));
            card.setSummary(stringValue(source.get("summary")));
            card.setContent(stringValue(source.get("content")));
            card.setConfidence(doubleValue(source.get("confidence"), 0.0D));
            card.setStatus(KnowledgeStatus.DRAFT.name());
            card.setVersion(1);
            mapper.insertCard(card);
            if (documentId != null) {
                mapper.linkCardEvidenceFromDocument(card.getKnowledgeCode(), documentId);
            }
            source.put("knowledge_code", card.getKnowledgeCode());
            source.put("status", card.getStatus());
            Map<String, Object> payload = buildSingleKnowledgeIndexPayload(knowledgeBaseId, source, null);
            outboxService.enqueue(
                    knowledgeBaseId,
                    "CARD",
                    "KB:" + knowledgeBaseId + ":CARD:" + card.getKnowledgeCode(),
                    "KNOWLEDGE_OBJECT_UPSERT",
                    AiApiPaths.INDEX_KNOWLEDGE,
                    payload,
                    "CARD_CREATE:" + card.getKnowledgeCode() + ":1");
            saved.add(source);
        }
        return saved;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> persistClaims(
            Long knowledgeBaseId, Long documentId, Object claimsObject) {
        List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
        if (!(claimsObject instanceof List)) {
            return saved;
        }

        for (Object item : (List<Object>) claimsObject) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<String, Object>((Map<String, Object>) item);
            Claim claim = new Claim();
            claim.setClaimCode(valueOrId(source.get("claim_code"), "CLM_"));
            claim.setKnowledgeBaseId(knowledgeBaseId);
            claim.setSubject(stringValue(source.get("subject")));
            claim.setPredicate(stringValue(source.get("predicate")));
            claim.setObjectValue(stringValue(source.get("object")));
            claim.setConditionJson(jsonOrDefault(source.get("conditions"), "[]"));
            claim.setConfidence(doubleValue(source.get("confidence"), 0.0D));
            claim.setStatus(KnowledgeStatus.UNCERTAIN.name());
            mapper.insertClaim(claim);
            if (documentId != null) {
                mapper.linkClaimEvidenceFromDocument(claim.getClaimCode(), documentId);
            }
            source.put("claim_code", claim.getClaimCode());
            source.put("status", claim.getStatus());
            Map<String, Object> payload = buildSingleKnowledgeIndexPayload(knowledgeBaseId, null, source);
            outboxService.enqueue(
                    knowledgeBaseId,
                    "CLAIM",
                    "KB:" + knowledgeBaseId + ":CLAIM:" + claim.getClaimCode(),
                    "KNOWLEDGE_OBJECT_UPSERT",
                    AiApiPaths.INDEX_KNOWLEDGE,
                    payload,
                    "CLAIM_CREATE:" + claim.getClaimCode() + ":1");
            saved.add(source);
        }
        return saved;
    }

    private Map<String, Object> buildSingleKnowledgeIndexPayload(
            Long kb, Map<String, Object> card, Map<String, Object> claim) {
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> claims = new ArrayList<Map<String, Object>>();
        if (card != null) {
            cards.add(card);
        }
        if (claim != null) {
            claims.add(claim);
        }
        return buildKnowledgeIndexPayload(kb, cards, claims, null, null);
    }

    private Map<String, Object> buildKnowledgeIndexPayload(
            Long kb,
            List<Map<String, Object>> cards,
            List<Map<String, Object>> claims,
            Object entities,
            Object relations) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("cards", cards);
        payload.put("claims", claims);
        payload.put("entities", entities);
        payload.put("relations", relations);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Object detectAndPersistConflicts(Long knowledgeBaseId, List<Map<String, Object>> newClaims) {
        if (newClaims.isEmpty()) {
            return new ArrayList<Object>();
        }

        List<String> subjects = new ArrayList<String>();
        List<String> focusCodes = new ArrayList<String>();
        for (Map<String, Object> claim : newClaims) {
            String subject = stringValue(claim.get("subject"));
            if (!subject.isEmpty() && !subjects.contains(subject)) {
                subjects.add(subject);
            }
            focusCodes.add(stringValue(claim.get("claim_code")));
        }

        List<Claim> candidates = mapper.listClaimsBySubjects(knowledgeBaseId, subjects);
        List<Map<String, Object>> conflictClaims = new ArrayList<Map<String, Object>>(candidates.size());
        for (Claim existing : candidates) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("claimCode", existing.getClaimCode());
            item.put("subject", existing.getSubject());
            item.put("predicate", existing.getPredicate());
            item.put("objectValue", existing.getObjectValue());
            item.put("confidence", existing.getConfidence());
            item.put("status", existing.getStatus());
            conflictClaims.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("claims", conflictClaims);
        payload.put("focusCodes", focusCodes);
        Map<String, Object> detected = aiClient.post(AiApiPaths.DETECT_CONFLICTS, payload);
        Object conflictsObject = detected.get("conflicts");
        if (!(conflictsObject instanceof List)) {
            return new ArrayList<Object>();
        }

        transactionRunner.required(() -> {
            for (Object item : (List<Object>) conflictsObject) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> conflict = (Map<String, Object>) item;
                String objectA = stringValue(conflict.get("objectACode"));
                String objectB = stringValue(conflict.get("objectBCode"));
                mapper.insertConflict(
                        knowledgeBaseId,
                        objectA,
                        objectB,
                        stringValue(conflict.get("conflictType")),
                        doubleValue(conflict.get("conflictScore"), 0.0D));
                mapper.updateClaimStatus(objectA, KnowledgeStatus.CONFLICTED.name());
                mapper.updateClaimStatus(objectB, KnowledgeStatus.CONFLICTED.name());
                String conflictOperation = "CONFLICT:" + objectA + ":" + objectB;
                outboxService.enqueueObjectStatus(
                        knowledgeBaseId, "CLAIM", objectA, KnowledgeStatus.CONFLICTED.name(),
                        conflictOperation + ":A");
                outboxService.enqueueObjectStatus(
                        knowledgeBaseId, "CLAIM", objectB, KnowledgeStatus.CONFLICTED.name(),
                        conflictOperation + ":B");
            }
        });
        return conflictsObject;
    }

    private String valueOrId(Object value, String prefix) {
        return value == null || String.valueOf(value).trim().isEmpty()
                ? idGenerator.next(prefix)
                : String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleValue(Object value, Double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String jsonOrDefault(Object value, String defaultJson) {
        try {
            return objectMapper.writeValueAsString(value == null ? new ArrayList<Object>() : value);
        } catch (Exception ex) {
            return defaultJson;
        }
    }
    private static final class PersistedKnowledge {
        private final List<Map<String, Object>> cards;
        private final List<Map<String, Object>> claims;

        private PersistedKnowledge(
                List<Map<String, Object>> cards,
                List<Map<String, Object>> claims) {
            this.cards = cards;
            this.claims = claims;
        }
    }

}
