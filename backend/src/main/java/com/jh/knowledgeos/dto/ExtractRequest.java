package com.jh.knowledgeos.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API request that triggers knowledge extraction for a document payload.
 */
public class ExtractRequest {
    @NotNull
    @Min(1)
    private Long knowledgeBaseId;

    private Long sourceId;
    private Long documentId;
    private String text;
    private List<Map<String, Object>> chunks;

    public Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", knowledgeBaseId);
        if (sourceId != null) {
            payload.put("sourceId", sourceId);
        }
        if (documentId != null) {
            payload.put("documentId", documentId);
        }
        payload.put("text", text == null ? "" : text);
        if (chunks != null) {
            payload.put("chunks", chunks);
        }
        return payload;
    }

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public List<Map<String, Object>> getChunks() { return chunks; }
    public void setChunks(List<Map<String, Object>> chunks) { this.chunks = chunks; }
}
