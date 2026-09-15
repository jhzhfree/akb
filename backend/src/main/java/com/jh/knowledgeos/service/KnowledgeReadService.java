package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Read-only application service for cards, claims, sources, conflicts, and operational views.
 */
@Service
public class KnowledgeReadService {
    private final KnowledgeMapper mapper;
    private final AiClient aiClient;

    public KnowledgeReadService(KnowledgeMapper mapper, AiClient aiClient) {
        this.mapper = mapper;
        this.aiClient = aiClient;
    }

    public Map<String, Object> graph(Long kb, String code) {
        return aiClient.get("/ai/graph/" + kb + "/" + code + "?depth=2&limit=80");
    }

    public Map<String, Object> capabilities() {
        return aiClient.get(AiApiPaths.CAPABILITIES);
    }

    public List<KnowledgeCard> cards(Long kb) {
        return mapper.listCards(kb);
    }

    public List<Claim> claims(Long kb) {
        return mapper.listClaims(kb);
    }

    public List<KnowledgeSource> sources(Long kb) {
        return mapper.listSources(kb);
    }

    public List<Map<String, Object>> documents(Long kb) {
        return mapper.listDocuments(kb);
    }

    public Map<String, Object> trace(String queryId) {
        return mapper.getTrace(queryId);
    }
}
