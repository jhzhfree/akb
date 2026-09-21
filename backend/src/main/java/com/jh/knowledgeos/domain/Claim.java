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
}
