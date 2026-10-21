package com.jh.knowledgeos.common;

import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 解析当前请求的调用方 IP。
 *
 * <p>取的是"当前线程绑定的那个请求"，而不是让每个调用点把 {@code HttpServletRequest} 传进来：
 * 落审计、落 trace 的地方分散在多个服务和多个通道，逐个传参的代价是漏掉某一处——而漏掉的那条
 * 记录看起来一切正常，只是永远缺一列。</p>
 *
 * <p>优先 {@code X-Forwarded-For} 的第一个地址：请求经过网关时 socket 地址是网关自己的，
 * 记下来所有调用都长一个样，没有区分度。其次 {@code X-Real-IP}，都没有才退回 socket 地址。
 * 要说明的是 XFF 可被伪造，这个值只在请求确实经过可信反向代理时才等于真实来源——
 * 与本项目信任 {@code X-Authenticated-*} 头是同一个前提。</p>
 */
@Component
public class ClientIpResolver {

    /** 列宽上限（见 V59 / V60 迁移），超长截断避免写入失败。 */
    private static final int MAX_LENGTH = 64;

    public String current() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String ip = firstForwardedIp(request.getHeader("X-Forwarded-For"));
        if (ip == null) {
            ip = blankToNull(request.getHeader("X-Real-IP"));
        }
        if (ip == null) {
            ip = blankToNull(request.getRemoteAddr());
        }
        if (ip == null) {
            return null;
        }
        return ip.length() <= MAX_LENGTH ? ip : ip.substring(0, MAX_LENGTH);
    }

    /** {@code X-Forwarded-For} 可能是 "client, proxy1, proxy2"：第一个才是原始来源。 */
    private String firstForwardedIp(String header) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(",")) {
            String candidate = blankToNull(part);
            // 链路上的代理没拿到来源时会填 unknown，顺着往下找而不是把它记成 IP。
            if (candidate != null && !"unknown".equalsIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        String trimmed = value == null ? null : value.trim();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return null;
        }
        return ((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest();
    }
}
