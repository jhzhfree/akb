package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ConnectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads "what knowledge does this system have" from a saved connector.
 *
 * <p>One normalised shape comes out regardless of the source: {@code externalId / title / url /
 * updatedAt / excerpt}. Everything above this class — the console, and later the importer that
 * turns items into documents — works against that shape, so adding a fourth connector type means
 * adding one branch here and nothing else.</p>
 *
 * <p>Only listing is implemented in this step. The paging parameters are offset/limit because
 * that is what Jira and Confluence both expose, and a generic REST endpoint can be configured to
 * match. Failures are rethrown as {@link IllegalArgumentException} so the caller gets the remote
 * system's own error text in the API envelope instead of a generic 500.</p>
 */
@Service
public class ConnectorFetchService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorFetchService.class);

    /** Hard ceiling on one read: the console previews, it does not bulk-export. */
    public static final int MAX_LIMIT = 100;

    public static final int DEFAULT_LIMIT = 20;

    private static final int EXCERPT_LIMIT = 400;

    /** Common array locations when a REST endpoint does not say where its items are. */
    private static final List<String> FALLBACK_ARRAY_KEYS = Arrays.asList("data", "results", "items", "list", "content", "records", "rows");

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    public ConnectorFetchService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** One page of items plus whatever the remote system told us about the total. */
    public static final class Page {

        private final List<Map<String, Object>> items;

        private final Long total;

        public Page(List<Map<String, Object>> items, Long total) {
            this.items = items;
            this.total = total;
        }

        public List<Map<String, Object>> getItems() {
            return items;
        }

        public Long getTotal() {
            return total;
        }
    }

    public Page fetch(ConnectorSource connector, int limit, int offset) {
        String type = upper(connector.getType());
        int size = Math.min(Math.max(limit <= 0 ? DEFAULT_LIMIT : limit, 1), MAX_LIMIT);
        int start = offset < 0 ? 0 : offset;
        if ("JIRA".equals(type)) {
            return fetchJira(connector, size, start);
        }
        if ("CONFLUENCE".equals(type)) {
            return fetchConfluence(connector, size, start);
        }
        if ("REST".equals(type)) {
            return fetchRest(connector, size, start);
        }
        throw new IllegalArgumentException("不支持的连接器类型：" + connector.getType());
    }

    private Page fetchJira(ConnectorSource connector, int limit, int offset) {
        Map<String, Object> config = config(connector);
        String jql = string(config.get("jql"));
        if (jql.isEmpty()) {
            jql = "order by updated DESC";
        }
        String url = base(connector) + "/rest/api/2/search?jql=" + encode(jql) + "&startAt=" + offset + "&maxResults=" + limit
            + "&fields=summary,updated,description";
        Map<String, Object> body = getJson(url, connector);
        List<Map<String, Object>> issues = array(body.get("issues"));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> issue : issues) {
            Object fields = issue.get("fields");
            Map<?, ?> map = fields instanceof Map ? (Map<?, ?>) fields : null;
            String key = string(issue.get("key"));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("externalId", key);
            item.put("title", first(map == null ? null : map.get("summary"), "(无标题)"));
            item.put("url", base(connector) + "/browse/" + key);
            item.put("updatedAt", map == null ? null : map.get("updated"));
            item.put("excerpt", excerpt(map == null ? null : map.get("description")));
            items.add(item);
        }
        return new Page(items, toLong(body.get("total")));
    }

    private Page fetchConfluence(ConnectorSource connector, int limit, int offset) {
        Map<String, Object> config = config(connector);
        String spaceKey = string(config.get("spaceKey"));
        String url = base(connector) + "/rest/api/content?start=" + offset + "&limit=" + limit + "&expand=body.storage";
        if (!spaceKey.isEmpty()) {
            url += "&spaceKey=" + encode(spaceKey);
        }
        Map<String, Object> body = getJson(url, connector);
        List<Map<String, Object>> results = array(body.get("results"));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> result : results) {
            String id = string(result.get("id"));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("externalId", id);
            item.put("title", first(string(result.get("title")), "(无标题)"));
            item.put("url", confluenceUrl(base(connector), result.get("_links"), id));
            Object version = result.get("version");
            item.put("updatedAt", version instanceof Map ? ((Map<?, ?>) version).get("when") : null);
            item.put("excerpt", excerpt(bodyOf(result)));
            items.add(item);
        }
        return new Page(items, toLong(body.get("size")));
    }

    private Page fetchRest(ConnectorSource connector, int limit, int offset) {
        Map<String, Object> config = config(connector);
        String path = string(config.get("path"));
        if (path.isEmpty()) {
            throw new IllegalArgumentException("REST 连接器缺少 path（列表接口路径）");
        }
        String url = base(connector) + (path.startsWith("/") ? path : "/" + path);
        boolean pageMode = "PAGE".equals(upper(string(config.get("pageMode"))));
        String separator = url.contains("?") ? "&" : "?";
        if (pageMode) {
            url += separator + first(string(config.get("pageParam")), "page") + "=" + (offset / limit + 1) + "&"
                + first(string(config.get("sizeParam")), "size") + "=" + limit;
        } else {
            url += separator + first(string(config.get("offsetParam")), "offset") + "=" + offset + "&"
                + first(string(config.get("limitParam")), "limit") + "=" + limit;
        }
        Map<String, Object> body = getJson(url, connector);
        Object raw = resolveArray(body, string(config.get("itemsPath")));
        List<Map<String, Object>> rows = array(raw);
        Map<String, Object> fields = fieldMap(config);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("externalId", first(pick(row, mapping(fields, "externalId", "id")), ""));
            item.put("title", first(pick(row, mapping(fields, "title", "title")), "(无标题)"));
            item.put("url", pick(row, mapping(fields, "url", "url")));
            item.put("updatedAt", pick(row, mapping(fields, "updatedAt", "updatedAt")));
            item.put("excerpt", excerpt(pick(row, mapping(fields, "excerpt", "content"))));
            items.add(item);
        }
        return new Page(items, toLong(body.get("total") != null ? body.get("total") : body.get("totalElements")));
    }

    /**
     * Resolves the item array: an explicit dotted path wins, otherwise the well-known keys are
     * tried, otherwise a root-level array is accepted. Endpoints are inconsistent enough that
     * requiring a path would make most of them unusable.
     */
    private Object resolveArray(Map<String, Object> body, String path) {
        if (!path.isEmpty()) {
            return valueAt(body, path);
        }
        for (String key : FALLBACK_ARRAY_KEYS) {
            Object value = body.get(key);
            if (value instanceof List) {
                return value;
            }
            if (value instanceof Map) {
                Object nested = resolveArray((Map<String, Object>) value, "");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fieldMap(Map<String, Object> config) {
        Object value = config.get("fields");
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    /** Explicit mapping wins; otherwise fall back to the keys most APIs actually use. */
    private String[] mapping(Map<String, Object> fields, String name, String fallback) {
        String configured = string(fields.get(name));
        if (!configured.isEmpty()) {
            return configured.split("[.]");
        }
        // 外部系统对主键的命名没有共识，多试几个常见键比要求用户一定配置更省事。
        if ("externalId".equals(name)) {
            return new String[]{"id", "docId", "documentId", "code", "key", "uuid", "identifier"};
        }
        if ("title".equals(name)) {
            return new String[]{"title", "name", "summary", "subject"};
        }
        if ("url".equals(name)) {
            return new String[]{"url", "link", "href"};
        }
        if ("updatedAt".equals(name)) {
            return new String[]{"updatedAt", "updateTime", "updated", "modifiedTime", "lastModified"};
        }
        if ("excerpt".equals(name)) {
            return new String[]{"content", "summary", "description", "body", "excerpt"};
        }
        return new String[]{fallback};
    }

    private Object pick(Map<String, Object> row, String... candidates) {
        for (String candidate : candidates) {
            Object value = valueAt(row, candidate);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Dotted lookup that also steps one level into nested maps, e.g. {@code fields.summary}. */
    private Object valueAt(Map<String, Object> row, String path) {
        Object current = row;
        for (String segment : path.split("[.]")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(segment);
        }
        return current;
    }

    private Map<String, Object> getJson(String url, ConnectorSource connector) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        String authType = upper(connector.getAuthType());
        String secret = connector.getSecret() == null ? "" : connector.getSecret();
        if ("BASIC".equals(authType)) {
            String raw = (connector.getUsername() == null ? "" : connector.getUsername()) + ":" + secret;
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        } else if ("BEARER".equals(authType)) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
        }
        Object custom = config(connector).get("headers");
        if (custom instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) custom).entrySet()) {
                headers.set(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<String>(headers), String.class);
        } catch (RestClientException ex) {
            log.warn("connector request failed type={} url={} message={}", connector.getType(), url, ex.getMessage());
            throw new IllegalArgumentException("连接数据源失败：" + ex.getMessage());
        }
        String payload = response.getBody();
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalArgumentException("数据源返回空响应");
        }
        try {
            if (payload.trim().startsWith("[")) {
                List<Map<String, Object>> rows = objectMapper.readValue(payload,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
                Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("items", rows);
                return wrapper;
            }
            return objectMapper.readValue(payload, Map.class);
        } catch (IOException ex) {
            throw new IllegalArgumentException("数据源返回的内容不是 JSON：" + ex.getMessage());
        }
    }

    private Map<String, Object> config(ConnectorSource connector) {
        if (connector.getConfig() != null) {
            return connector.getConfig();
        }
        String json = connector.getConfigJson();
        if (json == null || json.trim().isEmpty()) {
            return Collections.<String, Object>emptyMap();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            connector.setConfig(parsed);
            return parsed;
        } catch (IOException ex) {
            log.warn("connector config is not valid json id={} message={}", connector.getId(), ex.getMessage());
            return Collections.<String, Object>emptyMap();
        }
    }

    private String confluenceUrl(String base, Object links, String id) {
        if (links instanceof Map) {
            Object webui = ((Map<?, ?>) links).get("webui");
            String path = string(webui);
            if (!path.isEmpty()) {
                return path.startsWith("http") ? path : base + (path.startsWith("/") ? path : "/" + path);
            }
        }
        return base + "/pages/" + id;
    }

    private Object bodyOf(Map<String, Object> result) {
        Object body = result.get("body");
        if (body instanceof Map) {
            Object storage = ((Map<?, ?>) body).get("storage");
            if (storage instanceof Map) {
                return ((Map<?, ?>) storage).get("value");
            }
            Object view = ((Map<?, ?>) body).get("view");
            if (view instanceof Map) {
                return ((Map<?, ?>) view).get("value");
            }
        }
        return null;
    }

    private Object excerpt(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.isEmpty()) {
            return null;
        }
        String plain = text.replaceAll("(?s)<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= EXCERPT_LIMIT ? plain : plain.substring(0, EXCERPT_LIMIT) + "…";
    }

    private String base(ConnectorSource connector) {
        String url = connector.getBaseUrl() == null ? "" : connector.getBaseUrl().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> array(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Object row : (List<Object>) value) {
            if (row instanceof Map) {
                rows.add((Map<String, Object>) row);
            }
        }
        return rows;
    }

    private String encode(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8.name());
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String first(Object value, String fallback) {
        String text = string(value);
        return text.isEmpty() ? fallback : text;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
