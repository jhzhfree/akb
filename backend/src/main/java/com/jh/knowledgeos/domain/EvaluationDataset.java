package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/** A replayable question set bound to one knowledge base (design §14). */
public class EvaluationDataset {
    private Long id;
    private String datasetCode;
    private Long knowledgeBaseId;
    private String name;
    private String description;
    private String version;
    private String status;
    private String createdBy;
    private LocalDateTime createdTime;

    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getDatasetCode(){return datasetCode;} public void setDatasetCode(String v){datasetCode=v;}
    public Long getKnowledgeBaseId(){return knowledgeBaseId;} public void setKnowledgeBaseId(Long v){knowledgeBaseId=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getVersion(){return version;} public void setVersion(String v){version=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getCreatedBy(){return createdBy;} public void setCreatedBy(String v){createdBy=v;}
    public LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(LocalDateTime v){createdTime=v;}
}
