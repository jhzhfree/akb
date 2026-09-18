package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/**
 * Persistence model describing one blue/green retrieval-schema release.
 */
public class IndexSchemaRelease {
    private Long id; private String releaseId; private Long knowledgeBaseId; private String schemaVersion;
    private String state; private String elasticsearchTarget; private String milvusTarget; private Long expectedCount;
    private Long elasticsearchCount; private Long milvusCount; private String validationJson; private String errorText;
    private String snapshotChecksum; private Long sourceHighWatermark; private Long sourceEndWatermark;
    private Long snapshotCount; private String integrityState;
    private String generationId; private String aclManifestHash; private Long aclManifestCount;
    private LocalDateTime createdTime; private LocalDateTime activatedTime; private LocalDateTime retiredTime;
    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getReleaseId(){return releaseId;} public void setReleaseId(String v){releaseId=v;}
    public Long getKnowledgeBaseId(){return knowledgeBaseId;} public void setKnowledgeBaseId(Long v){knowledgeBaseId=v;}
    public String getSchemaVersion(){return schemaVersion;} public void setSchemaVersion(String v){schemaVersion=v;}
    public String getState(){return state;} public void setState(String v){state=v;}
    public String getElasticsearchTarget(){return elasticsearchTarget;} public void setElasticsearchTarget(String v){elasticsearchTarget=v;}
    public String getMilvusTarget(){return milvusTarget;} public void setMilvusTarget(String v){milvusTarget=v;}
    public Long getExpectedCount(){return expectedCount;} public void setExpectedCount(Long v){expectedCount=v;}
    public Long getElasticsearchCount(){return elasticsearchCount;} public void setElasticsearchCount(Long v){elasticsearchCount=v;}
    public Long getMilvusCount(){return milvusCount;} public void setMilvusCount(Long v){milvusCount=v;}
    public String getValidationJson(){return validationJson;} public void setValidationJson(String v){validationJson=v;}
    public String getErrorText(){return errorText;} public void setErrorText(String v){errorText=v;}
    public String getSnapshotChecksum(){return snapshotChecksum;} public void setSnapshotChecksum(String v){snapshotChecksum=v;}
    public Long getSourceHighWatermark(){return sourceHighWatermark;} public void setSourceHighWatermark(Long v){sourceHighWatermark=v;}
    public Long getSourceEndWatermark(){return sourceEndWatermark;} public void setSourceEndWatermark(Long v){sourceEndWatermark=v;}
    public Long getSnapshotCount(){return snapshotCount;} public void setSnapshotCount(Long v){snapshotCount=v;}
    public String getIntegrityState(){return integrityState;} public void setIntegrityState(String v){integrityState=v;}
    public String getGenerationId(){return generationId;} public void setGenerationId(String v){generationId=v;}
    public String getAclManifestHash(){return aclManifestHash;} public void setAclManifestHash(String v){aclManifestHash=v;}
    public Long getAclManifestCount(){return aclManifestCount;} public void setAclManifestCount(Long v){aclManifestCount=v;}
    public LocalDateTime getCreatedTime(){return createdTime;} public void setCreatedTime(LocalDateTime v){createdTime=v;}
    public LocalDateTime getActivatedTime(){return activatedTime;} public void setActivatedTime(LocalDateTime v){activatedTime=v;}
    public LocalDateTime getRetiredTime(){return retiredTime;} public void setRetiredTime(LocalDateTime v){retiredTime=v;}
}
