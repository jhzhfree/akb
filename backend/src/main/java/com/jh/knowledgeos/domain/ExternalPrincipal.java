package com.jh.knowledgeos.domain;

/** Service identity used to share knowledge-base grants across external credentials. */
public class ExternalPrincipal {

    private Long id;
    private String principalCode;
    private String name;
    private Long createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPrincipalCode() { return principalCode; }
    public void setPrincipalCode(String principalCode) { this.principalCode = principalCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
