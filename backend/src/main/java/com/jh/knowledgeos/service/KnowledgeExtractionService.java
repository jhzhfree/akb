package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.KnowledgeStatus;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.CardClaimMapper;
import com.jh.knowledgeos.mapper.IndexMapper;
import com.jh.knowledgeos.mapper.GraphMapper;
import com.jh.knowledgeos.mapper.GovernanceMapper;
import com.jh.knowledgeos.mapper.EvidenceMapper;
import org.springframework.stereotype.Service;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * Application service that converts parsed evidence into cards/claims and enqueues their retrieval-index updates.
 */
@Service
public class KnowledgeExtractionService {

    private final KnowledgeMapper mapper;

    private final CardClaimMapper cardClaimMapper;

    private final EvidenceMapper evidenceMapper;

    private final GovernanceMapper governanceMapper;

    private final GraphMapper graphMapper;

    private final IndexMapper indexMapper;


    private final AiClient aiClient;

    private final ObjectMapper objectMapper;

    private final IdGenerator idGenerator;

    private final TransactionRunner transactionRunner;

    private final IndexOutboxService outboxService;

    private final EntityResolutionService entityResolutionService;

    private final PlatformEventPublisher publisher;

    private final KbScenarioService scenarios;

    private final SimpleDateFormat dateTimeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public KnowledgeExtractionService(KnowledgeMapper mapper, AiClient aiClient, ObjectMapper objectMapper, IdGenerator idGenerator, TransactionRunner
        transactionRunner, IndexOutboxService outboxService, EntityResolutionService entityResolutionService, PlatformEventPublisher publisher,
        EvidenceMapper evidenceMapper, GovernanceMapper governanceMapper, GraphMapper graphMapper, IndexMapper indexMapper, CardClaimMapper cardClaimMapper,
        KbScenarioService scenarios) {
        this.cardClaimMapper = cardClaimMapper;
        this.evidenceMapper = evidenceMapper;
        this.governanceMapper = governanceMapper;
        this.graphMapper = graphMapper;
        this.indexMapper = indexMapper;
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
        this.entityResolutionService = entityResolutionService;
        this.publisher = publisher;
        this.scenarios = scenarios;
    }

    /** Graph step skipped because the library's scenario builds no graph. */
    private Map<String, Object> skippedGraph() {
        Map<String, Object> graph = new LinkedHashMap<String, Object>();
        graph.put("skipped", "GRAPH_DISABLED");
        graph.put("entities", 0);
        graph.put("relations", 0);
        return graph;
    }

    /** Entity resolution skipped because the scenario does not want it. */
    private Map<String, Object> skippedResolution() {
        Map<String, Object> resolution = new LinkedHashMap<String, Object>();
        resolution.put("skipped", "ENTITY_RESOLUTION_DISABLED");
        resolution.put("merged", 0);
        return resolution;
    }

    /**
     * Runs AI extraction outside the database transaction, persists cards/claims in a
     * short transaction, and enqueues rebuildable retrieval-view updates.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extract(Map<String, Object> request) {
        Map<String, Object> response = aiClient.post(AiApiPaths.EXTRACT, request);
        Long knowledgeBaseId = Long.valueOf(String.valueOf(request.get("knowledgeBaseId")));
        Long documentId = request.get("documentId") == null ? null : Long.valueOf(String.valueOf(request.get("documentId")));
        String permissionTags = normalizePermissionTags(request.get("permissionTags"));
        // Source authority is read once and stamped onto every extracted object: retrieval uses it
        // as a ranking signal (design V1.1 §8.3) instead of re-reading rows per object.
        Double authorityScore = loadAuthorityScore(documentId);
        // Retried on deadlock for the same reason batch ingestion is: cards, claims and entities all
        // upsert here, and a worker importing another document at the same time asks for the same
        // entity rows in a different order — MySQL then rolls this transaction back whole and asks
        // it to restart. Every statement inside is an idempotent upsert and the AI call above sits
        // outside the transaction, so redoing it neither duplicates rows nor pays for the model twice.
        // What this library wants derived is a property of the library, not of the pipeline: a
        // policy handbook needs versioned assertions and conflict detection but no graph walk,
        // while a company directory wants exactly the opposite.
        Map<String, Object> extractPolicy = scenarios.extraction(knowledgeBaseId);
        boolean graphEnabled = scenarios.graphEnabled(knowledgeBaseId);
        boolean claimsEnabled = Boolean.TRUE.equals(extractPolicy.get("claims"));
        PersistedKnowledge persisted = transactionRunner.retryingOnDeadlock(() -> {
            List<Map<String, Object>> cards = persistCards(knowledgeBaseId, documentId, response.get("cards"), permissionTags, authorityScore);
            List<Map<String, Object>> claims = claimsEnabled
                ? persistClaims(knowledgeBaseId, documentId, response.get("claims"), permissionTags, authorityScore)
                : new ArrayList<Map<String, Object>>();
            Map<String, Object> graph = graphEnabled ? persistAuthoritativeGraph(knowledgeBaseId, documentId, response, permissionTags)
                : skippedGraph();
            return new PersistedKnowledge(cards, claims, graph);
        });
        List<Map<String, Object>> savedCards = persisted.cards;
        List<Map<String, Object>> savedClaims = persisted.claims;
        response.put("authoritativeGraph", persisted.graph);
        Map<String, Object> graphEvent = null;
        if (graphEnabled) {
            Map<String, Object> graphPayload = buildKnowledgeIndexPayload(knowledgeBaseId, new ArrayList<Map<String, Object>>(),
                new ArrayList<Map<String, Object>>(), withPermissionTags(response.get("entities"), permissionTags),
                withPermissionTags(response.get("relations"), permissionTags));
            String graphKey = "KB:" + knowledgeBaseId + ":GRAPH:" + (documentId == null ? "MANUAL" : documentId);
            graphEvent = transactionRunner.required(() -> outboxService.enqueue(knowledgeBaseId, "GRAPH", graphKey,
                "GRAPH_BATCH_UPSERT", AiApiPaths.INDEX_KNOWLEDGE, graphPayload, documentId == null ? "GRAPH_EXTRACT:" + graphKey + ":" +
                UUID.randomUUID().toString() : "GRAPH_EXTRACT:" + graphKey + ":1"));
        }
        Map<String, Object> indexSummary = new LinkedHashMap<String, Object>();
        indexSummary.put("mode", "transactional-outbox");
        indexSummary.put("cardEvents", savedCards.size());
        indexSummary.put("claimEvents", savedClaims.size());
        indexSummary.put("graphEvent", graphEvent);
        response.put("knowledgeIndex", indexSummary);
        response.put("conflicts", Boolean.TRUE.equals(extractPolicy.get("conflictDetection"))
            ? detectAndPersistConflicts(knowledgeBaseId, savedClaims) : new ArrayList<Object>());
        // Entity resolution runs here, outside the persistence transaction: it issues an external
        // AI call and then merges duplicate graph nodes (design V1.1 §5.4).
        response.put("entityResolution", Boolean.TRUE.equals(extractPolicy.get("entityResolution"))
            ? entityResolutionService.resolveAndMerge(knowledgeBaseId, response.get("entities")) : skippedResolution());
        announceExtraction(knowledgeBaseId, documentId, savedCards, savedClaims);
        return response;
    }

    /**
     * One EXTRACTION_COMPLETED event per batch.
     *
     * <p>Emission is deliberately batched rather than one event per card: a single document can
     * yield dozens of objects, and subscribers care about the batch outcome with its identifiers,
     * while individual objects announce themselves again when they are reviewed or superseded.</p>
     */
    private void announceExtraction(Long knowledgeBaseId, Long documentId, List<Map<String, Object>> savedCards, List<Map<String, Object>> savedClaims) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("documentId", documentId);
        detail.put("cardCount", savedCards == null ? 0 : savedCards.size());
        detail.put("claimCount", savedClaims == null ? 0 : savedClaims.size());
        detail.put("cardCodes", codes(savedCards, "knowledge_code", 20));
        detail.put("claimCodes", codes(savedClaims, "claim_code", 20));
        detail.put("cardTitles", titles(savedCards, 5));
        publisher.emitUser(PlatformEventTypes.EXTRACTION_COMPLETED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "DOCUMENT", documentId == null
            ? null : String.valueOf(documentId), "知识抽取完成：新增 " + (savedCards == null ? 0 : savedCards.size()) + " 张卡片、" + (savedClaims == null ? 0 :
            savedClaims.size()) + " 条主张", detail);
        int candidateCount = (savedCards == null ? 0 : savedCards.size()) + (savedClaims == null ? 0 : savedClaims.size());
        if (candidateCount > 0) {
            // Candidates are distinguished from approved knowledge so a subscriber can follow the
            // review pipeline instead of assuming extraction means "usable now" (§16.2).
            publisher.emitUser(PlatformEventTypes.KNOWLEDGE_CANDIDATE_CREATED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "DOCUMENT",
                documentId == null ? null : String.valueOf(documentId), "新增 " + candidateCount + " 条待审核候选知识", detail);
        }
    }

    private List<String> codes(List<Map<String, Object>> items, String key, int limit) {
        List<String> values = new ArrayList<String>();
        if (items == null) {
            return values;
        }
        for (Map<String, Object> item : items) {
            if (values.size() >= limit) {
                break;
            }
            Object value = item.get(key);
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    private List<String> titles(List<Map<String, Object>> items, int limit) {
        List<String> values = new ArrayList<String>();
        if (items == null) {
            return values;
        }
        for (Map<String, Object> item : items) {
            if (values.size() >= limit) {
                break;
            }
            Object value = item.get("title");
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            values.add(text.length() > 80 ? text.substring(0, 80) + "…" : text);
        }
        return values;
    }

    /**
     * Source authority from the owning source; neutral 0.5 when unresolvable.
     */
    private Double loadAuthorityScore(Long documentId) {
        if (documentId == null) {
            return 0.5D;
        }
        Map<String, Object> header = indexMapper.getDocumentIndexHeader(documentId);
        return header == null ? 0.5D : doubleValue(header.get("authorityScore"), 0.5D);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> persistCards(Long knowledgeBaseId, Long documentId, Object cardsObject, String permissionTags, Double authorityScore) {
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
            card.setPermissionTags(permissionTags);
            card.setVersion(1);
            cardClaimMapper.insertCard(card);
            governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CARD", card.getKnowledgeCode(), "CREATED", "AI_EXTRACTION");
            if (documentId != null) {
                cardClaimMapper.linkCardEvidenceFromDocument(card.getKnowledgeCode(), documentId);
            }
            source.put("knowledge_code", card.getKnowledgeCode());
            source.put("status", card.getStatus());
            source.put("permission_tags", permissionTags);
            // Retrieval quality signals: authority of the source, freshness anchor, evidence count.
            source.put("authority_score", authorityScore);
            source.put("updated_at", dateTimeFormatter.format(new Date()));
            source.put("evidence_count", cardClaimMapper.countCardEvidence(card.getKnowledgeCode()));
            attachEvidenceAnchor("CARD", card.getKnowledgeCode(), source);
            Map<String, Object> payload = buildSingleKnowledgeIndexPayload(knowledgeBaseId, source, null);
            outboxService.enqueue(knowledgeBaseId, "CARD", "KB:" + knowledgeBaseId + ":CARD:" + card.getKnowledgeCode(), "KNOWLEDGE_OBJECT_UPSERT",
                AiApiPaths.INDEX_KNOWLEDGE, payload, "CARD_CREATE:" + card.getKnowledgeCode() + ":1");
            saved.add(source);
        }
        return saved;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> persistClaims(Long knowledgeBaseId, Long documentId, Object claimsObject, String permissionTags, Double authorityScore) {
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
            claim.setQualifierJson(jsonOrDefault(source.get("qualifiers"), "{}"));
            claim.setPolarity(stringValue(source.get("polarity")));
            claim.setConfidence(doubleValue(source.get("confidence"), 0.0D));
            claim.setCertainty(source.get("certainty") == null ? claim.getConfidence() : doubleValue(source.get("certainty"), claim.getConfidence()));
            claim.setValidFrom(dateValue(source.get("valid_from")));
            claim.setValidTo(dateValue(source.get("valid_to")));
            claim.setVersion(source.get("version") == null ? 1 : integerValue(source.get("version"), 1));
            claim.setStatus(KnowledgeStatus.UNCERTAIN.name());
            claim.setPermissionTags(permissionTags);
            cardClaimMapper.insertClaim(claim);
            governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CLAIM", claim.getClaimCode(), "CREATED", "AI_EXTRACTION");
            if (documentId != null) {
                cardClaimMapper.linkClaimEvidenceFromDocument(claim.getClaimCode(), documentId);
            }
            source.put("claim_code", claim.getClaimCode());
            source.put("status", claim.getStatus());
            source.put("permission_tags", permissionTags);
            // Keep claim semantics visible downstream (conflict detection, UI, retrieval ranking).
            source.put("polarity", claim.getPolarity());
            source.put("certainty", claim.getCertainty());
            source.put("qualifiers", claim.getQualifierJson());
            source.put("valid_from", formatDate(claim.getValidFrom()));
            source.put("valid_to", formatDate(claim.getValidTo()));
            source.put("authority_score", authorityScore);
            source.put("updated_at", dateTimeFormatter.format(new Date()));
            source.put("evidence_count", cardClaimMapper.countClaimEvidence(claim.getClaimCode()));
            attachEvidenceAnchor("CLAIM", claim.getClaimCode(), source);
            Map<String, Object> payload = buildSingleKnowledgeIndexPayload(knowledgeBaseId, null, source);
            outboxService.enqueue(knowledgeBaseId, "CLAIM", "KB:" + knowledgeBaseId + ":CLAIM:" + claim.getClaimCode(), "KNOWLEDGE_OBJECT_UPSERT",
                AiApiPaths.INDEX_KNOWLEDGE, payload, "CLAIM_CREATE:" + claim.getClaimCode() + ":1");
            saved.add(source);
        }
        return saved;
    }

    /**
     * Persist entities, relations and knowledge-entity links of one extraction batch.
     *
     * <p>Entity codes are deterministic: the same term inside one knowledge base must reuse
     * one node, otherwise relations and mentions can never meet. Relation endpoints arrive
     * as names (extraction works on text) and are resolved against this batch first and the
     * authoritative entity table second, so a relation is not dropped when the model quotes
     * the entity name instead of its code.</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> persistAuthoritativeGraph(Long kb, Long documentId, Map<String, Object> response, String permissionTags) {
        Long sourceId = null;
        if (documentId != null) {
            Map<String, Object> header = indexMapper.getDocumentIndexHeader(documentId);
            if (header != null && header.get("sourceId") != null) {
                sourceId = Long.valueOf(String.valueOf(header.get("sourceId")));
            }
        }
        String aclFrom = sourceId == null ? "EXTRACTION_REQUEST" : "SOURCE:" + sourceId;
        Map<String, String> codeByName = new LinkedHashMap<String, String>();
        int entityCount = 0;
        Object entitiesObject = response.get("entities");
        if (entitiesObject instanceof List) {
            for (Object value : (List<Object>) entitiesObject) {
                if (!(value instanceof Map)) {
                    continue;
                }
                Map<String, Object> entity = (Map<String, Object>) value;
                String name = stringValue(first(entity, "canonical_name", "name"));
                String explicit = stringValue(first(entity, "entity_code", "code"));
                // Reuse the node of a term this knowledge base already knows, otherwise derive a
                // stable code from the name so the next document connects to the same node.
                String known = explicit.isEmpty() && !name.isEmpty() ? existingEntityCode(kb, name) : "";
                String code = !explicit.isEmpty() ? explicit : (!known.isEmpty() ? known : (name.isEmpty() ? idGenerator.next("ENT_") :
                    stableCode("ENT_", kb + "|" + normalizeName(name))));
                graphMapper.upsertExtractedEntity(kb, sourceId, code, stringValue(first(entity, "entity_type", "type")), name,
                    stringValue(entity.get("description")), doubleValue(entity.get("confidence"), 0.0D), "ACTIVE", permissionTags, aclFrom);
                String scopeHash = aclScopeHash(permissionTags);
                graphMapper.insertEntityAclScope(kb, code, sourceId, documentId, permissionTags, scopeHash, aclFrom);
                graphMapper.upsertEntityFacet(kb, code, code + ":" + scopeHash, sourceId, documentId, stringValue(first(entity, "entity_type",
                    "type")), name, stringValue(entity.get("description")), permissionTags, scopeHash, aclFrom);
                graphMapper.recomputeEntityAclMergePolicy(kb, code);
                entity.put("entity_code", code);
                if (!name.isEmpty()) {
                    codeByName.put(normalizeName(name), code);
                }
                entityCount++;
            }
        }
        int relationCount = 0;
        Object relationsObject = response.get("relations");
        if (relationsObject instanceof List) {
            for (Object value : (List<Object>) relationsObject) {
                if (!(value instanceof Map)) {
                    continue;
                }
                Map<String, Object> relation = (Map<String, Object>) value;
                EntityRef source = resolveEntity(kb, first(relation, "source_entity_code", "source", "subject"), codeByName);
                EntityRef target = resolveEntity(kb, first(relation, "target_entity_code", "target", "object"), codeByName);
                if (source == null || target == null || source.code.equals(target.code)) {
                    continue;
                }
                String type = stringValue(first(relation, "relation_type", "type", "predicate"));
                if (type.isEmpty()) {
                    type = "RELATED_TO";
                }
                String explicit = stringValue(first(relation, "relation_code", "code"));
                String relationCode = !explicit.isEmpty() ? explicit : stableCode("REL_", kb + "|" + source.code + "|" + type + "|" + target.code);
                graphMapper.upsertExtractedRelation(kb, sourceId, relationCode, source.id, type, target.id, doubleValue(relation.get("confidence"),
                    0.0D), "ACTIVE", permissionTags, aclFrom);
                String scopeHash = aclScopeHash(permissionTags);
                graphMapper.insertRelationAclScope(kb, relationCode, sourceId, documentId, permissionTags, scopeHash, aclFrom);
                graphMapper.upsertRelationFacet(kb, relationCode, relationCode + ":" + scopeHash, sourceId, documentId, source.code, target.code,
                    type, permissionTags, scopeHash, aclFrom);
                graphMapper.recomputeRelationAclMergePolicy(kb, relationCode);
                relation.put("relation_code", relationCode);
                // Stamp the resolved entity codes so the graph index event connects the real
                // entity nodes; name-only endpoints would create duplicate nodes in the graph view.
                relation.put("source_entity_code", source.code);
                relation.put("target_entity_code", target.code);
                relationCount++;
            }
        }
        int linkCount = persistKnowledgeEntityLinks(kb, response.get("claims"), true, permissionTags, codeByName) + persistKnowledgeEntityLinks(kb,
            response.get("cards"), false, permissionTags, codeByName);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("entityCount", entityCount);
        summary.put("relationCount", relationCount);
        summary.put("knowledgeEntityLinkCount", linkCount);
        return summary;
    }

    /**
     * Code of the node already carrying this term, or empty when the term is new.
     */
    private String existingEntityCode(Long kb, String name) {
        Map<String, Object> row = graphMapper.findEntityRef(kb, name);
        return row == null ? "" : stringValue(row.get("entityCode"));
    }

    /**
     * Resolve one entity reference that may be a code, a batch name or a stored name.
     */
    private EntityRef resolveEntity(Long kb, Object raw, Map<String, String> codeByName) {
        String value = stringValue(raw);
        if (value.isEmpty()) {
            return null;
        }
        String batchCode = codeByName.get(normalizeName(value));
        Map<String, Object> row = graphMapper.findEntityRef(kb, batchCode == null ? value : batchCode);
        if (row == null && batchCode != null) {
            row = graphMapper.findEntityRef(kb, value);
        }
        if (row == null || row.get("id") == null) {
            return null;
        }
        return new EntityRef(Long.valueOf(String.valueOf(row.get("id"))), stringValue(row.get("entityCode")));
    }

    /**
     * Stable identifier derived from authoritative content; identical input gives one node.
     */
    private String stableCode(String prefix, String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return prefix + hex(digest.digest(material.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception ex) {
            return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    @SuppressWarnings("unchecked")
    private int persistKnowledgeEntityLinks(Long kb, Object objects, boolean claim, String permissionTags, Map<String, String> codeByName) {
        int links = 0;
        if (!(objects instanceof List)) {
            return links;
        }
        for (Object value : (List<Object>) objects) {
            if (!(value instanceof Map)) {
                continue;
            }
            Map<String, Object> object = (Map<String, Object>) value;
            String objectCode = stringValue(first(object, claim ? "claim_code" : "knowledge_code", "code"));
            Object entityCodes = first(object, "entity_codes", "entities");
            if (!(entityCodes instanceof List)) {
                continue;
            }
            for (Object entityValue : (List<Object>) entityCodes) {
                String reference = entityValue instanceof Map ? stringValue(first((Map<String, Object>) entityValue, "entity_code", "code")) :
                    stringValue(entityValue);
                EntityRef entity = resolveEntity(kb, reference, codeByName);
                if (entity == null) {
                    continue;
                }
                if (claim) {
                    graphMapper.linkClaimEntityAcl(objectCode, entity.code, "ABOUT", permissionTags);
                } else {
                    graphMapper.linkCardEntityAcl(objectCode, entity.code, "ABOUT", permissionTags);
                }
                links++;
            }
        }
        return links;
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private Object first(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    /**
     * Stamp the evidence anchor of one knowledge object onto its retrieval payload.
     *
     * <p>A retrieved card or claim can only be cited by an answer when the citation carries a
     * resolvable {@code evidence_code} and content hash: the answer gate treats any other citation
     * as unverifiable. Evidence is linked card-level in MySQL, so the anchor is read here instead of
     * being reconstructed from the extracted text during retrieval.</p>
     */
    private void attachEvidenceAnchor(String objectType, String objectCode, Map<String, Object> target) {
        if (objectCode == null || objectCode.isEmpty()) {
            return;
        }
        Map<String, Object> anchor = evidenceMapper.findPrimaryEvidenceAnchor(objectType, objectCode);
        if (anchor == null) {
            return;
        }
        Object code = anchor.get("evidenceCode");
        if (code != null && stringValue(code).length() > 0) {
            target.put("evidence_code", stringValue(code));
            target.put("evidence_hash", stringValue(anchor.get("evidenceHash")));
        }
    }

    private Map<String, Object> buildSingleKnowledgeIndexPayload(Long kb, Map<String, Object> card, Map<String, Object> claim) {
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

    @SuppressWarnings("unchecked")
    private Object withPermissionTags(Object value, String permissionTags) {
        if (!(value instanceof List)) {
            return value;
        }
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
        for (Object row : (List<Object>) value) {
            if (!(row instanceof Map)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<String, Object>((Map<String, Object>) row);
            copy.put("permission_tags", permissionTags);
            output.add(copy);
        }
        return output;
    }

    private Map<String, Object> buildKnowledgeIndexPayload(Long kb, List<Map<String, Object>> cards, List<Map<String, Object>> claims, Object
        entities, Object relations) {
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
        List<Claim> candidates = cardClaimMapper.listClaimsBySubjects(knowledgeBaseId, subjects);
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
                governanceMapper.insertConflict(knowledgeBaseId, objectA, objectB, stringValue(conflict.get("conflictType")),
                    doubleValue(conflict.get("conflictScore"), 0.0D));
                governanceMapper.updateClaimStatus(objectA, KnowledgeStatus.CONFLICTED.name());
                governanceMapper.updateClaimStatus(objectB, KnowledgeStatus.CONFLICTED.name());
                governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CLAIM", objectA, "CONFLICT_DETECTED", "CONFLICT_ENGINE");
                governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CLAIM", objectB, "CONFLICT_DETECTED", "CONFLICT_ENGINE");
                String conflictOperation = "CONFLICT:" + objectA + ":" + objectB;
                outboxService.enqueueObjectStatus(knowledgeBaseId, "CLAIM", objectA, KnowledgeStatus.CONFLICTED.name(), conflictOperation + ":A");
                outboxService.enqueueObjectStatus(knowledgeBaseId, "CLAIM", objectB, KnowledgeStatus.CONFLICTED.name(), conflictOperation + ":B");
            }
        });
        return conflictsObject;
    }

    private String valueOrId(Object value, String prefix) {
        return value == null || String.valueOf(value).trim().isEmpty() ? idGenerator.next(prefix) : String.valueOf(value);
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

    private Integer integerValue(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * Parse a validity boundary; unparsable or missing timestamps stay null (open bound).
     */
    private Date dateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return (Date) value;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.length() == 10) {
            // Date-only bounds are anchored at day start for from, day end for to semantics handled by caller.
            raw = raw + " 00:00:00";
        }
        try {
            return dateTimeFormatter.parse(raw);
        } catch (ParseException ex) {
            return null;
        }
    }

    private String formatDate(Date value) {
        return value == null ? "" : dateTimeFormatter.format(value);
    }

    private String normalizePermissionTags(Object value) {
        if (value == null) {
            return "[]";
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return "[]";
        }
        try {
            if (raw.startsWith("[")) {
                Object parsed = objectMapper.readValue(raw, Object.class);
                return objectMapper.writeValueAsString(parsed);
            }
            return objectMapper.writeValueAsString(java.util.Arrays.asList(raw.split(",")));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid permissionTags", ex);
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

        private final Map<String, Object> graph;

        private PersistedKnowledge(List<Map<String, Object>> cards, List<Map<String, Object>> claims, Map<String, Object> graph) {
            this.cards = cards;
            this.claims = claims;
            this.graph = graph;
        }
    }

    /**
     * A resolved entity endpoint: authoritative row id plus the stable code edges reference.
     */
    private static final class EntityRef {

        private final Long id;

        private final String code;

        private EntityRef(Long id, String code) {
            this.id = id;
            this.code = code;
        }
    }

    /**
     * Stable hash for one evidence ACL scope; tags are normalized before hashing.
     */
    private String aclScopeHash(String tagsJson) {
        try {
            @SuppressWarnings("unchecked")
            List<String> tags = objectMapper.readValue(tagsJson, List.class);
            Collections.sort(tags);
            String normalized = objectMapper.writeValueAsString(tags);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot hash ACL scope", ex);
        }
    }
}
