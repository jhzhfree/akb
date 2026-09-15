package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;

/**
 * API request for resolving a detected knowledge conflict.
 */
public class ConflictResolveRequest {
    @NotBlank
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
