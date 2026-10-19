package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** Operator decision on a captured answer-feedback item. */
public class QueryFeedbackReviewRequest {

    @NotBlank
    @Pattern(regexp = "NEW|IN_REVIEW|ACTION_REQUIRED|RESOLVED|REJECTED")
    private String status;

    @Size(max = 1000)
    private String reviewNote;

    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String value) { reviewNote = value; }
}
