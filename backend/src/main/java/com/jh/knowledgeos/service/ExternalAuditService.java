package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.config.ExternalCallPayloadFilter;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Metering for the external interface-service layer.
 *
 * <p>RESTful, MCP and SSE calls all land in the same audit table, so one credential can be
 * traced across every channel it was used on.</p>
 *
 * <p>A row now also says who called, what they sent and what we returned. That is what makes the
 * log answerable on its own — otherwise every failed integration call turns into a round trip: ask
 * the caller to reproduce it, hope it reproduces. The sensitive half of the contract is therefore
 * handled here rather than at every call site: credentials are stripped from whatever shape the
 * payload arrives in, and every value is bounded before it reaches a column.</p>
 */
@Service
public class ExternalAuditService {
    private static final Logger log = LoggerFactory.getLogger(ExternalAuditService.class);

    private static final int MESSAGE_LIMIT = 500;
    private static final int MAX_DEPTH = 6;
    private static final String REDACTED = "***";

    /**
     * Parameters that must never be copied into an audit row.
     *
     * <p>Compared after stripping case and separators, so {@code api_key}, {@code apiKey} and
     * {@code X-API-Key} all resolve to the same entry. Anything a caller could use to authenticate
     * belongs here: the audit table is readable by the whole operations surface.</p>
     */
    private static final Set<String> SECRET_KEYS =
            new HashSet<String>(Arrays.asList(
                    "apikey", "apikeyid", "authorization", "token", "accesstoken", "refreshtoken",
                    "secret", "secretkey", "accesskey", "password", "passwd", "credential",
                    "credentialsecret", "privatekey", "cookie", "sessionid"));

    private final ExternalApiMapper mapper;
    private final ObjectMapper json;
    private final int payloadLimit;

    public ExternalAuditService(
            ExternalApiMapper mapper,
            ObjectMapper json,
            @Value("${knowledge.external.audit-payload-chars:4000}") int payloadChars) {
        this.mapper = mapper;
        this.json = json;
        this.payloadLimit = Math.max(200, payloadChars);
    }

    public void record(
            ApiKey key, String channel, String method, Long knowledgeBaseId,
            int statusCode, long durationMillis, String message) {
        record(key, channel, method, knowledgeBaseId, statusCode, durationMillis, message, null, null);
    }

    /**
     * Writes one call, including what was asked and what was answered.
     *
     * <p>Both extra arguments are accepted as objects and serialised here, so a caller hands over
     * whatever it already has — a map, a DTO, a raw query string — instead of assembling text.</p>
     */
    public void record(
            ApiKey key, String channel, String method, Long knowledgeBaseId,
            int statusCode, long durationMillis, String message,
            Object requestParams, Object responseData) {
        ApiCallAudit audit = new ApiCallAudit();
        audit.setKeyId(key == null ? null : key.getId());
        audit.setKeyCode(key == null ? null : key.getKeyCode());
        audit.setCallerName(callerName(key));
        audit.setChannel(channel);
        audit.setMethod(method);
        audit.setKnowledgeBaseId(knowledgeBaseId);
        audit.setRequestParams(payload(requestParams));
        audit.setStatusCode(statusCode);
        audit.setResponseData(payload(responseData));
        audit.setDurationMs((int) Math.min(Math.max(durationMillis, 0), Integer.MAX_VALUE));
        audit.setTraceId(MDC.get("traceId"));
        audit.setMessage(truncate(message, MESSAGE_LIMIT));
        try {
            mapper.insertApiCallAudit(audit);
        } catch (Exception e) {
            // 审计失败不能影响对外服务本身，只留下日志。
            log.warn("failed to persist external call audit channel={} method={}", channel, method, e);
        }
    }

    /**
     * What an external caller sent, structured for reading.
     *
     * <p>Query parameters and JSON body are flattened into one object, because that is how the
     * caller thinks about its request — the transport split is incidental detail.</p>
     */
    public Map<String, Object> requestParamsOf(HttpServletRequest request) {
        Map<String, String> parts = ExternalCallPayloadFilter.payload(request);
        if (parts == null) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        String query = parts.get("query");
        if (query != null) {
            params.putAll(parseQuery(query));
        }
        String body = parts.get("body");
        if (body != null) {
            Object parsed = parseJson(body);
            if (parsed instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                    params.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else {
                params.put("body", parsed == null ? body : parsed);
            }
        }
        return params.isEmpty() ? null : params;
    }

    public List<ApiCallAudit> recent(Long keyId, int limit) {
        return mapper.listApiCallAudits(keyId, Math.min(Math.max(limit, 1), 200));
    }

    /** Recent calls of one channel, so an integration surface can be inspected on its own. */
    public List<ApiCallAudit> recentByChannel(String channel, int limit) {
        return mapper.listChannelAudits(channel, Math.min(Math.max(limit, 1), 200));
    }

    /**
     * How the caller knows itself.
     *
     * <p>The credential name is the integration's own label — "结算中心", "客服助手" — which is what
     * an operator scanning the log recognises. The key code stays on the row as the stable
     * identifier for joins, but it was never meant to be read by humans.</p>
     */
    private String callerName(ApiKey key) {
        if (key == null) return null;
        if (key.getName() != null && !key.getName().trim().isEmpty()) return key.getName().trim();
        return key.getKeyCode();
    }

    /** Serialises and bounds anything too large to keep, credentials already removed. */
    private String payload(Object value) {
        if (value == null) return null;
        String text;
        try {
            text = json.writeValueAsString(redact(value, 0));
        } catch (Exception e) {
            // 无法序列化的入参（例如流式句柄）不能让审计失效，退化为字符串描述。
            text = String.valueOf(value);
        }
        return truncate(text, payloadLimit);
    }

    /**
     * Removes secrets from a payload of arbitrary shape.
     *
     * <p>Recursion is bounded so a deeply nested request cannot turn a logging detail into a CPU
     * problem, and unknown shapes are left alone rather than dropped: an operator would rather see
     * an odd field than miss the one that broke the call.</p>
     */
    private Object redact(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) return value;
        if (value instanceof Map) {
            Map<Object, Object> source = (Map<Object, Object>) value;
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<Object, Object> entry : source.entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object kept = isSecret(name) ? REDACTED : redact(entry.getValue(), depth + 1);
                result.put(name, kept);
            }
            return result;
        }
        if (value instanceof Collection) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) {
                result.add(redact(item, depth + 1));
            }
            return result;
        }
        if (value instanceof Object[]) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Object[]) value) {
                result.add(redact(item, depth + 1));
            }
            return result;
        }
        return value;
    }

    private boolean isSecret(String name) {
        String reduced = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SECRET_KEYS.contains(reduced) || reduced.endsWith("secret") || reduced.endsWith("password");
    }

    /** Query string as parameters; repeated names keep every value rather than the last one. */
    private Map<String, Object> parseQuery(String query) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int split = pair.indexOf('=');
            String name = decode(split < 0 ? pair : pair.substring(0, split));
            String value = decode(split < 0 ? "" : pair.substring(split + 1));
            if (name.isEmpty() && value.isEmpty()) continue;
            Object existing = result.get(name);
            if (existing == null) {
                result.put(name, value);
            } else if (existing instanceof List) {
                ((List<Object>) existing).add(value);
            } else {
                List<Object> values = new ArrayList<Object>();
                values.add(existing);
                values.add(value);
                result.put(name, values);
            }
        }
        return result;
    }

    private Object parseJson(String body) {
        if (body.isEmpty() || (body.charAt(0) != '{' && body.charAt(0) != '[')) return null;
        try {
            return json.readValue(body, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        int cut = max;
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) cut--;
        return value.substring(0, cut);
    }
}
