package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * External text ingestion contract; reuses the console upload pipeline.
 */
public class ExternalIngestRequest {

    /** 知识库编码（{@code KB_xxxx}）；过渡期也收数字主键。 */
    private String knowledgeBaseId;

    @NotBlank
    private String text;

    private String title;

    private List<String> permissionTags;

    public String getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getPermissionTags() {
        return permissionTags;
    }

    public void setPermissionTags(List<String> permissionTags) {
        this.permissionTags = permissionTags;
    }
}
