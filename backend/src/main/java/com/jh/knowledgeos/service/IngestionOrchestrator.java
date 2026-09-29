package com.jh.knowledgeos.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

/**
 * Application orchestration boundary for document ingestion.
 * Keeps controllers/facades independent from the persistence-heavy ingestion implementation and
 * provides the stable seam for future parse/extract/adjudicate/index workflow coordination.
 */
@Service
public class IngestionOrchestrator {
    private final DocumentIngestionService ingestion;

    public IngestionOrchestrator(DocumentIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    public Map<String, Object> upload(Long kb, MultipartFile file, Double authority, String permissionTags) throws Exception {
        return ingestion.upload(kb, file, authority, permissionTags);
    }

    public Map<String, Object> retry(Long documentId) throws Exception {
        return ingestion.retry(documentId);
    }
}
