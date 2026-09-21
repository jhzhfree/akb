package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API request for manual or intelligent knowledge adjudication.
 */
public class AdjudicateRequest {

    @NotBlank
    private String objectType;

    @NotBlank
    private String objectCode;

    @NotBlank
    private String decision;

    private String reviewer;

    private String comment;

    public Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("objectType", objectType);
        payload.put("objectCode", objectCode);
        payload.put("decision", decision);
        payload.put("reviewer", reviewer == null ? "" : reviewer);
        payload.put("comment", comment == null ? "" : comment);
        return payload;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getObjectCode() {
        return objectCode;
    }

    public void setObjectCode(String objectCode) {
        this.objectCode = objectCode;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
