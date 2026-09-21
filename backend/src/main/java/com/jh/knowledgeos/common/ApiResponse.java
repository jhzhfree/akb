package com.jh.knowledgeos.common;

import org.slf4j.MDC;

/**
 * Generic HTTP response envelope used by the public backend API.
 */
public class ApiResponse<T> {

    private boolean success;

    private String code;

    private String message;

    private String traceId;

    private T data;

    public ApiResponse() {
    }

    public ApiResponse(boolean success, String code, String message, String traceId, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.traceId = traceId;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<T>(true, ApiErrorCode.OK.getCode(), "成功", MDC.get("traceId"), data);
    }

    public static <T> ApiResponse<T> fail(ApiErrorCode code, String message) {
        return new ApiResponse<T>(false, code.getCode(), message, MDC.get("traceId"), null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return fail(ApiErrorCode.INTERNAL_ERROR, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getTraceId() {
        return traceId;
    }

    public T getData() {
        return data;
    }

    public void setSuccess(boolean value) {
        success = value;
    }

    public void setCode(String value) {
        code = value;
    }

    public void setMessage(String value) {
        message = value;
    }

    public void setTraceId(String value) {
        traceId = value;
    }

    public void setData(T value) {
        data = value;
    }
}
