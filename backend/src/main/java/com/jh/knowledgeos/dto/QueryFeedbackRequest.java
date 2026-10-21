package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** Explicit user feedback about a delivered answer. */
public class QueryFeedbackRequest {

    @NotBlank
    @Size(max = 64)
    private String queryId;

    /** 问题原文，可选：带上它记忆才好在下次相似提问时被召回。 */
    private String question;

    @NotBlank
    @Pattern(regexp = "HELPFUL|UNHELPFUL")
    private String rating;

    @Pattern(regexp = "INACCURATE|INCOMPLETE|OUTDATED|NO_CITATION|IRRELEVANT|OTHER")
    private String reasonCode;

    @Size(max = 1000)
    private String comment;

    @Size(max = 4000)
    private String suggestedAnswer;

    public String getQueryId() { return queryId; }
    public void setQueryId(String value) { queryId = value; }
    public String getQuestion() { return question; }
    public void setQuestion(String value) { question = value; }
    public String getRating() { return rating; }
    public void setRating(String value) { rating = value; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String value) { reasonCode = value; }
    public String getComment() { return comment; }
    public void setComment(String value) { comment = value; }
    public String getSuggestedAnswer() { return suggestedAnswer; }
    public void setSuggestedAnswer(String value) { suggestedAnswer = value; }
}
