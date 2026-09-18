package com.jh.knowledgeos.exception;

import com.jh.knowledgeos.common.ApiErrorCode;
import com.jh.knowledgeos.common.ApiResponse;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/**
 * Central exception translator that converts backend failures into stable API responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> validation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(x -> x.getField() + ": " + x.getDefaultMessage()).collect(Collectors.joining("; "));
        return ApiResponse.fail(ApiErrorCode.VALIDATION_FAILED, message);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> bind(BindException e) {
        return ApiResponse.fail(ApiErrorCode.VALIDATION_FAILED, "请求参数错误");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> illegalArgument(IllegalArgumentException e) {
        return ApiResponse.fail(ApiErrorCode.INVALID_ARGUMENT, e.getMessage());
    }

    @ExceptionHandler(RestClientException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiResponse<Object> aiService(RestClientException e) {
        log.error("AI service request failed: {}", rootCause(e), e);
        return ApiResponse.fail(ApiErrorCode.AI_SERVICE_ERROR, "AI 服务调用失败：" + rootCause(e));
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Object> database(DataAccessException e) {
        log.error("Database operation failed: {}", rootCause(e), e);
        return ApiResponse.fail(ApiErrorCode.DATABASE_ERROR, "数据库操作失败：" + rootCause(e));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Object> unexpected(Exception e) {
        log.error("Unhandled request error: {}", rootCause(e), e);
        return ApiResponse.fail(ApiErrorCode.INTERNAL_ERROR, "系统内部错误：" + rootCause(e));
    }

    /**
     * Returns the deepest cause message so operators can diagnose a failure from the API
     * response instead of grepping the log; long driver messages are truncated.
     */
    private static String rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage().trim();
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
