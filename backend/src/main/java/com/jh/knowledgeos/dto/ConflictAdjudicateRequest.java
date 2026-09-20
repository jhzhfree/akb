package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;

/**
 * API request for adjudicating a detected knowledge conflict.
 */
public class ConflictAdjudicateRequest {
    /** KEEP_A / KEEP_B pick the surviving object, MERGE keeps object A, REJECT_BOTH drops both, IGNORE leaves both untouched. */
    @NotBlank
    private String decision;

    /** The reviewer is part of the decision record: a verdict without an author cannot be audited. */
    private String reviewer;

    private String comment;

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
