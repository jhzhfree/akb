package com.jh.knowledgeos.domain;

import java.util.Date;

/**
 * Persistence model for an extracted factual assertion and its governance state.
 *
 * <p>Fields follow the claim model of the design document (V1.1 section 6): an assertion states that,
 * under {@code conditions} and optional {@code qualifiers}, a subject relates to an object with a given
 * {@code polarity} and {@code certainty}, and it is only valid inside its validity window.</p>
 */
public class Claim {

    private Long id;

    private String claimCode;

    private Long knowledgeBaseId;

    private String subject;

    private String predicate;

    private String objectValue;

    private String conditionJson;

    private String qualifierJson;

    private String polarity = "POSITIVE";

    private Double confidence;

    private Double certainty;

    private String status;

    private String permissionTags;

    private Date validFrom;

    private Date validTo;

    private Integer version = 1;

    /**
     * 断言等价指纹：规范化后的主词/谓词/宾词 + 极性 + 条件 + 有效期。
     *
     * <p>极性、条件、有效期必须参与：只差极性或有效期的两条主张并不等价，只按三元组
     * 比对会把它们错误地当成同一条。</p>
     */
    private String claimFingerprint;

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

    public String getClaimCode() {
        return claimCode;
    }

    public void setClaimCode(String claimCode) {
        this.claimCode = claimCode;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getPredicate() {
        return predicate;
    }

    public void setPredicate(String predicate) {
        this.predicate = predicate;
    }

    public String getObjectValue() {
        return objectValue;
    }

    public void setObjectValue(String objectValue) {
        this.objectValue = objectValue;
    }

    public String getConditionJson() {
        return conditionJson;
    }

    public void setConditionJson(String conditionJson) {
        this.conditionJson = conditionJson;
    }

    public String getQualifierJson() {
        return qualifierJson;
    }

    public void setQualifierJson(String qualifierJson) {
        this.qualifierJson = qualifierJson;
    }

    public String getPolarity() {
        return polarity;
    }

    /**
     * Normalizes polarity to POSITIVE/NEGATIVE; anything unknown degrades to POSITIVE.
     */
    public void setPolarity(String polarity) {
        String normalized = polarity == null ? "" : polarity.trim().toUpperCase();
        this.polarity = "NEGATIVE".equals(normalized) ? "NEGATIVE" : "POSITIVE";
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public Double getCertainty() {
        return certainty;
    }

    /**
     * Certainty is the linguistic strength of the statement; it defaults to the model confidence.
     */
    public void setCertainty(Double certainty) {
        this.certainty = certainty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPermissionTags() {
        return permissionTags;
    }

    public void setPermissionTags(String permissionTags) {
        this.permissionTags = permissionTags;
    }

    public Date getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(Date validFrom) {
        this.validFrom = validFrom;
    }

    public Date getValidTo() {
        return validTo;
    }

    public void setValidTo(Date validTo) {
        this.validTo = validTo;
    }

    public Integer getVersion() {
        return version;
    }

    /**
     * Version is the assertion revision counter used by supersession lineage.
     */
    public void setVersion(Integer version) {
        this.version = version == null || version < 1 ? 1 : version;
    }

    public String getClaimFingerprint() {
        return claimFingerprint;
    }

    public void setClaimFingerprint(String claimFingerprint) {
        this.claimFingerprint = claimFingerprint;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }
}
