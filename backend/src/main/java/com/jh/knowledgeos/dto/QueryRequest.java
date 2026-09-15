package com.jh.knowledgeos.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API request for routed hybrid knowledge retrieval and answer generation.
 */
public class QueryRequest {
    @NotNull
    @Min(1)
    private Long knowledgeBaseId;

    @NotBlank
    @Size(max = 4000)
    private String query;

    @Min(1)
    @Max(50)
    private Integer topK = 10;

    private String userId;
    private List<String> permissionTags;

    public Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("query", query);
        payload.put("topK", topK);
        if (userId != null) {
            payload.put("userId", userId);
        }
        if (permissionTags != null) {
            payload.put("permissionTags", permissionTags);
        }
        return payload;
    }

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public List<String> getPermissionTags() { return permissionTags; }
    public void setPermissionTags(List<String> permissionTags) { this.permissionTags = permissionTags; }
}
