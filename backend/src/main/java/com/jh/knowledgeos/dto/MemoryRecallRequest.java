package com.jh.knowledgeos.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/** Scoped recall request; namespace and Agent identity come from the API key, not this body. */
public class MemoryRecallRequest {

    @NotBlank
    @Size(max = 300)
    private String query;

    @Pattern(regexp = "AGENT|TEAM|PROJECT|TASK|WORKFLOW|SESSION")
    private String scopeType;

    @Size(max = 128)
    private String scopeCode;

    @Min(1)
    @Max(50)
    private Integer limit;

    public String getQuery() { return query; }
    public void setQuery(String value) { query = value; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String value) { scopeType = value; }
    public String getScopeCode() { return scopeCode; }
    public void setScopeCode(String value) { scopeCode = value; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer value) { limit = value; }
}
