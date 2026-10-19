package com.jh.knowledgeos.dto;

/** Explicit grant for one shared memory scope bound to an API Key. */
public class AgentMemoryScopeGrantRequest {
    private String scopeType;
    private String scopeCode;
    private Boolean canRead;
    private Boolean canWrite;
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeCode() { return scopeCode; }
    public void setScopeCode(String scopeCode) { this.scopeCode = scopeCode; }
    public Boolean getCanRead() { return canRead; }
    public void setCanRead(Boolean canRead) { this.canRead = canRead; }
    public Boolean getCanWrite() { return canWrite; }
    public void setCanWrite(Boolean canWrite) { this.canWrite = canWrite; }
}
