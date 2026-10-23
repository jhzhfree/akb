package com.jh.knowledgeos.domain;

/**
 * Persistence model for a governed, reusable knowledge-card artifact.
 */
public class KnowledgeCard {

    private Long id;

    private String knowledgeCode;

    private Long knowledgeBaseId;

    private String type;

    private String title;

    private String summary;

    private String content;

    private Double confidence;

    private String status;

    private String permissionTags;

    private Integer version;

    /**
     * 知识等价指纹：完整规范化内容 + 关键语义字段。
     *
     * <p>只用来判断「不同来源是否表达了同一条知识」，命中不意味着可以合并——两侧资产
     * 各自保留状态与权限标签，重复关系交给人工裁决。</p>
     */
    private String contentFingerprint;

    /**
     * 抽取幂等键：知识库 + 文档 + 抽取版本 + 指纹，由数据库唯一约束保证不重复落库。
     */
    private String dedupKey;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKnowledgeCode() {
        return knowledgeCode;
    }

    public void setKnowledgeCode(String knowledgeCode) {
        this.knowledgeCode = knowledgeCode;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public void setContentFingerprint(String contentFingerprint) {
        this.contentFingerprint = contentFingerprint;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getPermissionTags() {
        return permissionTags;
    }

    public void setPermissionTags(String permissionTags) {
        this.permissionTags = permissionTags;
    }
}
