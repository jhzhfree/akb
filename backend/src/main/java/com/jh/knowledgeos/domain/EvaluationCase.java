package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/**
 * One graded question: what was asked and which knowledge objects an answer had to reach.
 *
 * <p>Expected evidence and expected answer stay optional on purpose — a dataset can grade recall
 * long before anyone has written a gold answer.</p>
 */
public class EvaluationCase {
    private Long id;
    private String caseCode;
    private Long datasetId;
    private String question;
    private String expectedObjectCodesJson;
    private String expectedEvidenceCodesJson;
    private String expectedAnswer;
    private String tagsJson;
    private String difficulty;
    private String version;
    private String status;
    private LocalDateTime createdTime;

    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getCaseCode(){return caseCode;} public void setCaseCode(String v){caseCode=v;}
    public Long getDatasetId(){return datasetId;} public void setDatasetId(Long v){datasetId=v;}
    public String getQuestion(){return question;} public void setQuestion(String v){question=v;}
    public String getExpectedObjectCodesJson(){return expectedObjectCodesJson;}
    public void setExpectedObjectCodesJson(String v){expectedObjectCodesJson=v;}
    public String getExpectedEvidenceCodesJson(){return expectedEvidenceCodesJson;}
    public void setExpectedEvidenceCodesJson(String v){expectedEvidenceCodesJson=v;}
    public String getExpectedAnswer(){return expectedAnswer;} public void setExpectedAnswer(String v){expectedAnswer=v;}
    public String getTagsJson(){return tagsJson;} public void setTagsJson(String v){tagsJson=v;}
    public String getDifficulty(){return difficulty;} public void setDifficulty(String v){difficulty=v;}
    public String getVersion(){return version;} public void setVersion(String v){version=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(LocalDateTime v){createdTime=v;}
}
