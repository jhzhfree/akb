package com.jh.knowledgeos.exception;

import com.jh.knowledgeos.common.ApiErrorCode;
import com.jh.knowledgeos.exception.RateLimitException;
import com.jh.knowledgeos.exception.UnauthorizedException;
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

    /**
     * Authorization failures raised by knowledge-base sharing rules and role checks.
     * The message is safe to show in the console: it names the account right, not data.
     */
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Object> forbidden(SecurityException e) {
        return ApiResponse.fail(ApiErrorCode.FORBIDDEN, e.getMessage());
    }

    /** External callers without a usable API key: told to authenticate rather than to give up. */
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Object> unauthorized(UnauthorizedException e) {
        return ApiResponse.fail(ApiErrorCode.UNAUTHORIZED, e.getMessage());
    }

    /** Quota breach: 429 so well-behaved clients can back off and retry later. */
    @ExceptionHandler(RateLimitException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ApiResponse<Object> rateLimited(RateLimitException e) {
        return ApiResponse.fail(ApiErrorCode.RATE_LIMITED, e.getMessage());
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
