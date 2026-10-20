package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotNull;

/** Explicitly disables or restores memory capabilities that were granted to an API Key. */
public class AgentMemoryKeyPolicyRequest {

    @NotNull
    private Boolean readEnabled;

    @NotNull
    private Boolean writeEnabled;

    public Boolean getReadEnabled() { return readEnabled; }
    public void setReadEnabled(Boolean value) { readEnabled = value; }
    public Boolean getWriteEnabled() { return writeEnabled; }
    public void setWriteEnabled(Boolean value) { writeEnabled = value; }
}
