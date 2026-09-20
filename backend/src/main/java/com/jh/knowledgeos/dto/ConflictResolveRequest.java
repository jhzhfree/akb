package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;

/**
 * API request for resolving a detected knowledge conflict.
 */
public class ConflictResolveRequest {
    @NotBlank
    private String status;
    /** How the conflict was settled — "resolved" alone tells a later reviewer nothing (§10.2). */
    private String resolution;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }
}
