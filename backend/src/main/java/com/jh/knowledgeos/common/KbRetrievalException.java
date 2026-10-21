package com.jh.knowledgeos.common;

/**
 * 协议化检索失败：带 HTTP 状态码与机器可读的 {@code code}。
 *
 * <p>NovaWork 侧靠状态码与 code 分支（401/403/404 各自有明确处理：404 是"这个库不存在"，
 * 403 是"这个 key 没权限"），所以不能沿用控制台那套 {@code {success,status,message}} 的形状，
 * 得是 {@code {code,message}}。</p>
 */
public class KbRetrievalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    private final String code;

    public KbRetrievalException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static KbRetrievalException kbNotFound(String id) {
        return new KbRetrievalException(404, "KB_NOT_FOUND", "知识库不存在: " + id);
    }

    public static KbRetrievalException invalidArgument(String message) {
        return new KbRetrievalException(400, "INVALID_ARGUMENT", message);
    }
}
