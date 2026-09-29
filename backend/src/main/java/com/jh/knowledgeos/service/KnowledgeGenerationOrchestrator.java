package com.jh.knowledgeos.service;

import org.springframework.stereotype.Service;
import java.util.Map;

/** Stable orchestration boundary for extraction/generation independent of extraction internals. */
@Service
public class KnowledgeGenerationOrchestrator {
    private final KnowledgeExtractionService extraction;

    public KnowledgeGenerationOrchestrator(KnowledgeExtractionService extraction) {
        this.extraction = extraction;
    }

    public Map<String, Object> extract(Map<String, Object> request) {
        return extraction.extract(request);
    }
}
