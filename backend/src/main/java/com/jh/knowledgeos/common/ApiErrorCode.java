package com.jh.knowledgeos.common;

/**
 * Stable API error-code catalog shared by controllers and exception handling.
 */
public enum ApiErrorCode {
    OK("OK"),
    INVALID_ARGUMENT("KB-400-001"),
    VALIDATION_FAILED("KB-400-002"),
    RESOURCE_NOT_FOUND("KB-404-001"),
    AI_SERVICE_ERROR("KB-502-001"),
    DATABASE_ERROR("KB-500-002"),
    INTERNAL_ERROR("KB-500-001");

    private final String code;
    ApiErrorCode(String code) { this.code = code; }
    public String getCode() { return code; }
}
