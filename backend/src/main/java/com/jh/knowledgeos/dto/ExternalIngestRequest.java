package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import java.util.List;

/** External text ingestion contract; reuses the console upload pipeline. */
public class ExternalIngestRequest {
    private Long knowledgeBaseId;

    @NotBlank
    private String text;

    private String title;
    private List<String> permissionTags;

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<String> getPermissionTags() { return permissionTags; }
    public void setPermissionTags(List<String> permissionTags) { this.permissionTags = permissionTags; }
}
