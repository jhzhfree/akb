package com.jh.knowledgeos.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 明文 HTTP 允许哪类 origin 的判定。
 *
 * <p>规则不写在这里：它读 classpath 上的 {@code intranet-http.json}，也就是仓库里的
 * {@code contracts/intranet-http.json}（由 pom 把该目录挂成资源目录，不做复制）。前端用
 * 同一份文件（经 scripts/sync-intranet-rules.mjs 生成模块），所以"内网"这个口径只有一处
 * 定义，两边不会各改各的。
 *
 * <p>不解析 DNS：解析会阻塞、可能失败，且结果随时可变，判出来的结论不可靠。
 */
public final class IntranetAddressRules {
    private static final String RESOURCE = "intranet-http.json";
    private static final JsonNode RULES = load();

    private IntranetAddressRules() {}

    /** 本机：单独一类场景，由独立开关控制，不算「内网」。 */
    public static boolean isLoopback(String host) {
        String value = normalize(host);
        if (value.isEmpty()) return false;
        JsonNode loopback = RULES.path("loopback");
        for (JsonNode name : loopback.withArray("hosts")) {
            if (value.equals(name.asText().toLowerCase(Locale.ROOT))) return true;
        }
        for (JsonNode prefix : loopback.withArray("ipv4Prefixes")) {
            if (value.startsWith(prefix.asText())) return true;
        }
        return false;
    }

    /** 内网：私有地址段与内网命名。不含本机，公网 IP 与公网域名都不算。 */
    public static boolean isIntranetHost(String host) {
        String value = normalize(host);
        if (value.isEmpty()) return false;
        // 本机不算内网：它是另一类场景，由独立开关管。"无点主机名"这条规则本身会把
        // localhost 也算进来，所以这里显式排除，两边口径才一致。
        if (isLoopback(value)) return false;
        for (JsonNode prefix : RULES.withArray("ipv6Prefixes")) {
            if (value.startsWith(prefix.asText())) return true;
        }
        for (JsonNode suffix : RULES.withArray("hostSuffixes")) {
            if (value.endsWith(suffix.asText())) return true;
        }
        // 无点主机名（k8s 里的服务名）只在内网可达；含冒号的是裸 IPv6，走前缀判定已经处理
        if (RULES.path("bareHostnameIsIntranet").asBoolean(false)
            && !value.contains(".") && !value.contains(":")) {
            return true;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        int first = parseInt(parts[0], Integer.MIN_VALUE);
        int second = parseInt(parts[1], Integer.MIN_VALUE);
        if (first == Integer.MIN_VALUE || second == Integer.MIN_VALUE) return false;
        for (JsonNode range : RULES.withArray("ipv4Ranges")) {
            if (first != range.path("first").asInt(Integer.MIN_VALUE)) continue;
            if (range.hasNonNull("second")) {
                if (second != range.get("second").asInt(Integer.MIN_VALUE)) continue;
            } else {
                int from = range.path("secondFrom").asInt(Integer.MIN_VALUE);
                int to = range.path("secondTo").asInt(Integer.MAX_VALUE);
                if (second < from || second > to) continue;
            }
            return true;
        }
        return false;
    }

    private static String normalize(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        // URI 里的 IPv6 带方括号，取内容判定
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static JsonNode load() {
        try (InputStream in = IntranetAddressRules.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 " + RESOURCE + "（明文 HTTP 的判定规则）");
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException ex) {
            throw new IllegalStateException(RESOURCE + " 无法解析", ex);
        }
    }
}
