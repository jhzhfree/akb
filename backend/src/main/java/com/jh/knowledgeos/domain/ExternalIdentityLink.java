package com.jh.knowledgeos.domain;

/** IdP identity link between an issuer subject and an AKB USER principal. */
public class ExternalIdentityLink {
    private Long id;
    private Long principalId;
    private String issuer;
    private String subject;
    private Long operatorId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPrincipalId() { return principalId; }
    public void setPrincipalId(Long principalId) { this.principalId = principalId; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
}
