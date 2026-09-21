package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/**
 * Per-case grading (design §14): run-level averages cannot tell which question regressed.
 */
public class EvaluationResult {

    private Long id;

    private Long runId;

    private Long caseId;

    private String caseCode;

    private String queryId;

    private String status;

    private String expectedCodesJson;

    private String retrievedCodesJson;

    private Integer hitAt1;

    private Integer hitAt3;

    private Integer hitAt5;

    private Double reciprocalRank;

    private Double recallAt5;

    private Double ndcgAt10;

    private Integer latencyMs;

    private Integer citationTotal;

    private Integer citationHit;

    private Double citationAccuracy;

    private Double faithfulness;

    private String errorMessage;

    private LocalDateTime createdTime;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long v) {
        runId = v;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long v) {
        caseId = v;
    }

    public String getCaseCode() {
        return caseCode;
    }

    public void setCaseCode(String v) {
        caseCode = v;
    }

    public String getQueryId() {
        return queryId;
    }

    public void setQueryId(String v) {
        queryId = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public String getExpectedCodesJson() {
        return expectedCodesJson;
    }

    public void setExpectedCodesJson(String v) {
        expectedCodesJson = v;
    }

    public String getRetrievedCodesJson() {
        return retrievedCodesJson;
    }

    public void setRetrievedCodesJson(String v) {
        retrievedCodesJson = v;
    }

    public Integer getHitAt1() {
        return hitAt1;
    }

    public void setHitAt1(Integer v) {
        hitAt1 = v;
    }

    public Integer getHitAt3() {
        return hitAt3;
    }

    public void setHitAt3(Integer v) {
        hitAt3 = v;
    }

    public Integer getHitAt5() {
        return hitAt5;
    }

    public void setHitAt5(Integer v) {
        hitAt5 = v;
    }

    public Double getReciprocalRank() {
        return reciprocalRank;
    }

    public void setReciprocalRank(Double v) {
        reciprocalRank = v;
    }

    public Double getRecallAt5() {
        return recallAt5;
    }

    public void setRecallAt5(Double v) {
        recallAt5 = v;
    }

    public Double getNdcgAt10() {
        return ndcgAt10;
    }

    public void setNdcgAt10(Double v) {
        ndcgAt10 = v;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer v) {
        latencyMs = v;
    }

    public Integer getCitationTotal() {
        return citationTotal;
    }

    public void setCitationTotal(Integer v) {
        citationTotal = v;
    }

    public Integer getCitationHit() {
        return citationHit;
    }

    public void setCitationHit(Integer v) {
        citationHit = v;
    }

    public Double getCitationAccuracy() {
        return citationAccuracy;
    }

    public void setCitationAccuracy(Double v) {
        citationAccuracy = v;
    }

    public Double getFaithfulness() {
        return faithfulness;
    }

    public void setFaithfulness(Double v) {
        faithfulness = v;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String v) {
        errorMessage = v;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime v) {
        createdTime = v;
    }
}
