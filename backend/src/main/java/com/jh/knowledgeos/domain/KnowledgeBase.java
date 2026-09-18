package com.jh.knowledgeos.domain;
/** Authoritative knowledge-base workspace metadata. */
public class KnowledgeBase {
    private Long id; private String baseCode; private String name; private String description;
    private String status; private java.time.LocalDateTime createdTime;
    public Long getId(){return id;} public void setId(Long id){this.id=id;}
    public String getBaseCode(){return baseCode;} public void setBaseCode(String v){this.baseCode=v;}
    public String getName(){return name;} public void setName(String v){this.name=v;}
    public String getDescription(){return description;} public void setDescription(String v){this.description=v;}
    public String getStatus(){return status;} public void setStatus(String v){this.status=v;}
    public java.time.LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(java.time.LocalDateTime v){this.createdTime=v;}
}
