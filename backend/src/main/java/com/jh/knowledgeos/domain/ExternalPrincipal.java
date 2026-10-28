package com.jh.knowledgeos.domain;

/** Service identity used to share knowledge-base grants across external credentials. */
public class ExternalPrincipal {

    private Long id;
    private String principalCode;
    private String principalType;
    private Long ownerUserId;
    private String externalInstanceId;
    private String externalSubjectId;
    private String name;
    private String status;
    private Long createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPrincipalCode() { return principalCode; }
    public void setPrincipalCode(String principalCode) { this.principalCode = principalCode; }
    public String getPrincipalType() { return principalType; }
    public void setPrincipalType(String principalType) { this.principalType = principalType; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getExternalInstanceId() { return externalInstanceId; }
    public void setExternalInstanceId(String externalInstanceId) { this.externalInstanceId = externalInstanceId; }
    public String getExternalSubjectId() { return externalSubjectId; }
    public void setExternalSubjectId(String externalSubjectId) { this.externalSubjectId = externalSubjectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
