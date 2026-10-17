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
import org.springframework.web.servlet.HandlerMapping;
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
    private static final Set<String> SECRET_KEYS = new HashSet<String>(Arrays.asList("apikey", "apikeyid", "authorization", "token", "accesstoken",
        "refreshtoken", "secret", "secretkey", "accesskey", "password", "passwd", "credential", "credentialsecret", "privatekey", "cookie", "sessionid"));

    private final ExternalApiMapper mapper;

    private final ObjectMapper json;

    private final int payloadLimit;

    public ExternalAuditService(ExternalApiMapper mapper, ObjectMapper json, @Value("${knowledge.external.audit-payload-chars:10000}") int payloadChars) {
        this.mapper = mapper;
        this.json = json;
        this.payloadLimit = Math.max(200, payloadChars);
    }

    public void record(ApiKey key, String channel, String method, Long knowledgeBaseId, int statusCode, long durationMillis, String message) {
        record(key, channel, method, knowledgeBaseId, statusCode, durationMillis, message, null, null);
    }

    /**
     * Writes one call, including what was asked and what was answered.
     *
     * <p>Both extra arguments are accepted as objects and serialised here, so a caller hands over
     * whatever it already has — a map, a DTO, a raw query string — instead of assembling text.</p>
     */
    public void record(ApiKey key, String channel, String method, Long knowledgeBaseId, int statusCode, long durationMillis, String message, Object
        requestParams, Object responseData) {
        record(key, channel, method, knowledgeBaseId, statusCode, durationMillis, message, requestParams, responseData, null);
    }

    /**
     * Same as above, plus the embed token the call was made with.
     *
     * <p>An audit row already named the key; naming the token and the site as well is what makes a
     * bad call actionable — otherwise "some page using key X" is all an operator has, and revoke
     * means taking down every page minted from that key. The token id is not a secret: it only
     * becomes useful to an attacker together with the signed token itself.</p>
     */
    public void record(ApiKey key, String channel, String method, Long knowledgeBaseId, int statusCode, long durationMillis, String message, Object
        requestParams, Object responseData, EmbedTokenService.EmbedToken embedToken) {
        ApiCallAudit audit = new ApiCallAudit();
        audit.setKeyId(key == null ? null : key.getId());
        audit.setKeyCode(key == null ? null : key.getKeyCode());
        audit.setCallerName(callerName(key));
        audit.setTokenJti(embedToken == null ? null : embedToken.getJti());
        audit.setOrigin(embedToken == null ? null : embedToken.getOrigin());
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
     * <p>Query parameters, path variables and JSON body are flattened into one object, because that
     * is how the caller thinks about its request — the transport split is incidental detail. Path
     * variables matter as much as the query string: {@code GET /cards/{code}} carries everything it
     * was asked for in the path, so a log that only read the query string would show an empty
     * request for exactly the calls an operator most wants to inspect.</p>
     */
    public Map<String, Object> requestParamsOf(HttpServletRequest request) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        Map<String, String> parts = ExternalCallPayloadFilter.payload(request);
        if (parts != null) {
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
        }
        Object uriVariables = request == null ? null : request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (uriVariables instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) uriVariables).entrySet()) {
                String name = String.valueOf(entry.getKey());
                if (!params.containsKey(name)) {
                    params.put(name, entry.getValue());
                }
            }
        }
        return params.isEmpty() ? null : params;
    }

    /**
     * What an MCP call asked for, in the terms the caller thinks in.
     *
     * <p>{@code tools/call} is the method that does real work, and its payload is two levels deep:
     * the tool name and its arguments sit inside {@code params}. Storing the raw envelope would make
     * every row read {@code {"jsonrpc":"2.0","params":{...}}} — technically complete, useless when
     * scanning. So a tool call is audited as the tool plus its arguments; anything else keeps its
     * params as they arrived.</p>
     */
    public Object mcpRequestParams(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Map<String, Object> params = asMap(body.get("params"));
        String method = String.valueOf(body.get("method"));
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        if ("tools/call".equals(method)) {
            view.put("tool", params == null ? null : params.get("name"));
            view.put("arguments", params == null ? null : params.get("arguments"));
            return view;
        }
        view.put("method", method);
        view.put("params", params);
        return view;
    }

    /**
     * What an MCP call got back, with the protocol envelope taken off.
     *
     * <p>A {@code tools/call} answer carries the business object in {@code structuredContent} and
     * the same thing again as a text blob in {@code content}; the log keeps the object, which is
     * what anyone reading it is after. A {@code tools/list} answer would swamp the column with ten
     * full tool schemas, so it is reduced to the names. Errors are kept whole: their code and
     * message are the answer.</p>
     */
    public Object mcpResponseData(Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        Object error = response.get("error");
        if (error != null) {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("error", error);
            return view;
        }
        Map<String, Object> result = asMap(response.get("result"));
        if (result == null) {
            // 通知类消息没有返回，不是"丢了返回"。
            return null;
        }
        Object structured = result.get("structuredContent");
        if (structured != null) {
            return structured;
        }
        Object tools = result.get("tools");
        if (tools instanceof List) {
            List<Object> names = new ArrayList<Object>();
            for (Object tool : (List<?>) tools) {
                if (tool instanceof Map) {
                    names.add(((Map<?, ?>) tool).get("name"));
                }
            }
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("toolCount", names.size());
            view.put("tools", names);
            return view;
        }
        return result;
    }

    /**
     * The knowledge base a tool call was aimed at, so the row can be filtered like any other call.
     */
    public Long mcpKnowledgeBaseId(Map<String, Object> body) {
        Map<String, Object> params = body == null ? null : asMap(body.get("params"));
        if (params == null) {
            return null;
        }
        Object arguments = params.get("arguments");
        Object raw = arguments instanceof Map ? ((Map<?, ?>) arguments).get("knowledgeBaseId") : params.get("knowledgeBaseId");
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    public List<ApiCallAudit> recent(Long keyId, int limit) {
        return mapper.listApiCallAudits(keyId, Math.min(Math.max(limit, 1), 200));
    }

    /**
     * Recent calls of one channel, so an integration surface can be inspected on its own.
     */
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
        if (key == null) {
            return null;
        }
        if (key.getName() != null && !key.getName().trim().isEmpty()) {
            return key.getName().trim();
        }
        return key.getKeyCode();
    }

    /**
     * Serialises and bounds anything too large to keep, credentials already removed.
     */
    private String payload(Object value) {
        if (value == null) {
            return null;
        }
        String text;
        try {
            text = json.writeValueAsString(redact(value, 0));
        } catch (Exception e) {
            // 无法序列化的入参（例如流式句柄）不能让审计失效，退化为字符串描述。
            text = String.valueOf(value);
        }
        if (text.length() <= payloadLimit) {
            return text;
        }
        // 截断要说出来：一个被切掉一半的 JSON 看上去只是"乱码"，而标注长度后读者立刻知道
        // 是审计上限截的，不是调用方发的怪东西。
        return truncate(text, payloadLimit) + "…[已截断，完整长度 " + text.length() + " 字符]";
    }

    /**
     * Removes secrets from a payload of arbitrary shape.
     *
     * <p>Recursion is bounded so a deeply nested request cannot turn a logging detail into a CPU
     * problem, and unknown shapes are left alone rather than dropped: an operator would rather see
     * an odd field than miss the one that broke the call.</p>
     */
    private Object redact(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return value;
        }
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

    /**
     * Query string as parameters; repeated names keep every value rather than the last one.
     */
    private Map<String, Object> parseQuery(String query) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int split = pair.indexOf('=');
            String name = decode(split < 0 ? pair : pair.substring(0, split));
            String value = decode(split < 0 ? "" : pair.substring(split + 1));
            if (name.isEmpty() && value.isEmpty()) {
                continue;
            }
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
        if (body.isEmpty() || (body.charAt(0) != '{' && body.charAt(0) != '[')) {
            return null;
        }
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
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        int cut = max;
        if (cut > 0 && Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut);
    }
}
