package com.jh.knowledgeos.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 明文 HTTP 允许哪类 origin 的判定。
 *
 * <p>规则不写死在这里：它读 classpath 上的 {@code intranet-http.json}，也就是仓库里的
 * {@code contracts/intranet-http.json}（pom 把该目录挂成资源目录）。前端用同一份文件，所以
 * "内网"这个口径只有一处定义。不解析 DNS：解析会阻塞、可能失败，且结果随时可变。
 *
 * <p>读不到文件时**用内置兜底规则继续工作**，只记一条 ERROR。这不是为了掩盖错误，而是因为
 * 静态初始化失败会让这个类在 JVM 里永久不可用（后续一律 NoClassDefFoundError：Could not
 * initialize class），一次打包漏了资源就把整台服务打瘫，且错误信息完全指不到真正的原因。
 * 兜底值与真源一致，由 IntranetAddressRulesTest 用同一份共享用例钉住。
 */
public final class IntranetAddressRules {
    private static final Logger log = LoggerFactory.getLogger(IntranetAddressRules.class);
    private static final String RESOURCE = "intranet-http.json";
    private static volatile Rules rules;

    private IntranetAddressRules() {}

    /** 本机：单独一类场景，由独立开关控制，不算「内网」。 */
    public static boolean isLoopback(String host) {
        return isLoopback(host, current());
    }

    /** 内网：私有地址段与内网命名。不含本机，公网 IP 与公网域名都不算。 */
    public static boolean isIntranetHost(String host) {
        return isIntranetHost(host, current());
    }

    static boolean isLoopback(String host, Rules rules) {
        String value = normalize(host);
        if (value.isEmpty()) return false;
        if (rules.loopbackHosts.contains(value)) return true;
        for (String prefix : rules.loopbackIpv4Prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    static boolean isIntranetHost(String host, Rules rules) {
        String value = normalize(host);
        if (value.isEmpty()) return false;
        // 本机不算内网：它是另一类场景，由独立开关管。"无点主机名"这条规则本身会把
        // localhost 算进来，显式排除才能和前端同口径。
        if (isLoopback(value, rules)) return false;
        for (String prefix : rules.ipv6Prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        for (String suffix : rules.hostSuffixes) {
            if (value.endsWith(suffix)) return true;
        }
        // 无点主机名（k8s 里的服务名）只在内网可达；含冒号的是裸 IPv6，前缀判定已处理
        if (rules.bareHostnameIsIntranet && !value.contains(".") && !value.contains(":")) {
            return true;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        int first = parseInt(parts[0], Integer.MIN_VALUE);
        int second = parseInt(parts[1], Integer.MIN_VALUE);
        if (first == Integer.MIN_VALUE || second == Integer.MIN_VALUE) return false;
        for (Range range : rules.ipv4Ranges) {
            if (range.matches(first, second)) return true;
        }
        return false;
    }

    /** 包级可见：测试用它验证「读不到文件时的兜底」与真源同口径。 */
    static Rules fallback() {
        return Rules.fallback();
    }

    private static Rules current() {
        Rules value = rules;
        if (value == null) {
            value = load();
            rules = value;
        }
        return value;
    }

    private static synchronized Rules load() {
        if (rules != null) return rules;
        InputStream in = IntranetAddressRules.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (in != null) {
            try {
                rules = Rules.fromJson(new ObjectMapper().readTree(in));
                return rules;
            } catch (IOException ex) {
                log.error("{} 无法解析，改用内置兜底规则", RESOURCE, ex);
            } finally {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // 关闭失败不影响已读到的内容
                }
            }
        } else {
            log.error(
                "classpath 缺少 {} —— 部署包里没带上 contracts/intranet-http.json"
                    + "（Docker 构建的上下文必须包含仓库根的 contracts 目录）。"
                    + "现在用内置兜底规则继续提供服务，内网判定仍然有效，但口径可能与真源不同步。",
                RESOURCE);
        }
        rules = Rules.fallback();
        return rules;
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

    static final class Rules {
        final Set<String> loopbackHosts;
        final List<String> loopbackIpv4Prefixes;
        final List<Range> ipv4Ranges;
        final List<String> ipv6Prefixes;
        final List<String> hostSuffixes;
        final boolean bareHostnameIsIntranet;

        private Rules(Set<String> loopbackHosts, List<String> loopbackIpv4Prefixes,
            List<Range> ipv4Ranges, List<String> ipv6Prefixes, List<String> hostSuffixes,
            boolean bareHostnameIsIntranet) {
            this.loopbackHosts = loopbackHosts;
            this.loopbackIpv4Prefixes = loopbackIpv4Prefixes;
            this.ipv4Ranges = ipv4Ranges;
            this.ipv6Prefixes = ipv6Prefixes;
            this.hostSuffixes = hostSuffixes;
            this.bareHostnameIsIntranet = bareHostnameIsIntranet;
        }

        static Rules fromJson(JsonNode node) {
            JsonNode loopback = node.path("loopback");
            List<Range> ranges = new ArrayList<Range>();
            for (JsonNode range : node.withArray("ipv4Ranges")) {
                Integer second = range.hasNonNull("second") ? range.get("second").asInt() : null;
                ranges.add(new Range(range.path("first").asInt(Integer.MIN_VALUE), second,
                    range.path("secondFrom").asInt(Integer.MIN_VALUE),
                    range.path("secondTo").asInt(Integer.MAX_VALUE)));
            }
            return new Rules(
                new HashSet<String>(texts(loopback.withArray("hosts"))),
                texts(loopback.withArray("ipv4Prefixes")),
                ranges,
                texts(node.withArray("ipv6Prefixes")),
                texts(node.withArray("hostSuffixes")),
                node.path("bareHostnameIsIntranet").asBoolean(false));
        }

        /**
         * 与 contracts/intranet-http.json 一致的兜底值。改真源时这里要跟着改——
         * IntranetAddressRulesTest 用共享用例同时校验两者，不一致会红。
         */
        static Rules fallback() {
            List<Range> ranges = Arrays.asList(
                new Range(10, null, Integer.MIN_VALUE, Integer.MAX_VALUE),
                new Range(172, null, 16, 31),
                new Range(192, 168, Integer.MIN_VALUE, Integer.MAX_VALUE),
                new Range(169, 254, Integer.MIN_VALUE, Integer.MAX_VALUE),
                new Range(100, null, 64, 127));
            return new Rules(
                new HashSet<String>(Arrays.asList("localhost", "::1")),
                Arrays.asList("127."),
                ranges,
                Arrays.asList("fd", "fc", "fe80"),
                Arrays.asList(".local", ".internal", ".intranet", ".lan", ".corp", ".home",
                    ".localdomain", ".private"),
                true);
        }

        private static List<String> texts(JsonNode array) {
            List<String> values = new ArrayList<String>();
            for (JsonNode item : array) {
                values.add(item.asText().toLowerCase(Locale.ROOT));
            }
            return values;
        }
    }

    static final class Range {
        private final int first;
        private final Integer second;
        private final int secondFrom;
        private final int secondTo;

        Range(int first, Integer second, int secondFrom, int secondTo) {
            this.first = first;
            this.second = second;
            this.secondFrom = secondFrom;
            this.secondTo = secondTo;
        }

        boolean matches(int first, int second) {
            if (this.first != first) return false;
            if (this.second != null) return this.second.intValue() == second;
            return second >= secondFrom && second <= secondTo;
        }
    }
}
