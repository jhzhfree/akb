package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * External text ingestion contract; reuses the console upload pipeline.
 */
public class ExternalIngestRequest {

    /** 知识库编码（{@code KB_xxxx}）；仅接受 KB_ 开头的系统标识。 */
    @javax.validation.constraints.NotBlank
    @javax.validation.constraints.Pattern(regexp = "KB_[A-Za-z0-9]{1,125}")
    private String knowledgeBaseId;

    @NotBlank
    private String text;

    private String title;

    private List<String> permissionTags;

    @com.fasterxml.jackson.annotation.JsonSetter("knowledgeBaseCode")
    public void rejectRemovedSelector(Object ignored) {
        throw new IllegalArgumentException("请仅使用 knowledgeBaseId（KB_ 开头）");
    }

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
