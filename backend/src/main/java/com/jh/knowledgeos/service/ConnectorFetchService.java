package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ConnectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** 单个 REST 条目最多采集多少属性：够看清内容，又不至于把一行几百列的接口全塞进 JSON。 */
    private static final int REST_ATTRIBUTE_LIMIT = 12;

    private static final Set<String> TIME_ATTRIBUTE_KEYS = new LinkedHashSet<String>(Arrays.asList(
        "created", "createdat", "createtime", "created_time", "createdate", "createddate",
        "updated", "updatedat", "updatetime", "updated_time", "lastmodified", "lastmodifiedtime",
        "modified", "timestamp", "createts", "updatets"));

    private static final Pattern ISO_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ].*");

    private static final Pattern SLASH_TIME = Pattern.compile("\\d{4}/\\d{1,2}/\\d{1,2}.*");

    private static final Pattern EPOCH_MILLIS = Pattern.compile("\\d{13}");

    /** Common array locations when a REST endpoint does not say where its items are. */
    private static final List<String> FALLBACK_ARRAY_KEYS = Arrays.asList("data", "results", "items", "list", "content", "records", "rows");

    private final RestTemplate restTemplate;

    private final RestTemplate trustAllRestTemplate;

    private final KnowledgeProperties properties;

    private final ObjectMapper objectMapper;

    public ConnectorFetchService(RestTemplate restTemplate, @Qualifier("connectorTrustAllRestTemplate") RestTemplate trustAllRestTemplate,
        KnowledgeProperties properties, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.trustAllRestTemplate = trustAllRestTemplate;
        this.properties = properties;
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
            + "&fields=summary,updated,description,issuetype,status,priority,assignee,reporter,labels,created,project,resolution,components";
        Map<String, Object> body = getJson(url, connector);
        List<Map<String, Object>> issues = array(body.get("issues"));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> issue : issues) {
            Map<String, Object> fields = mapOf(issue.get("fields"));
            String key = string(issue.get("key"));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("externalId", key);
            item.put("title", first(fields.get("summary"), "(无标题)"));
            item.put("url", base(connector) + "/browse/" + key);
            item.put("updatedAt", fields.get("updated"));
            item.put("excerpt", excerpt(fields.get("description")));
            // 类型是 Jira 自己的问题类型（缺陷 / 任务 / 故事…），属性按类型动态展示，
            // 所以这里不写死列：取得到的属性才进 Map，缺失的字段不占位。
            item.put("type", first(nameOf(fields.get("issuetype")), "ISSUE"));
            Map<String, Object> attributes = new LinkedHashMap<String, Object>();
            put(attributes, "status", nameOf(fields.get("status")));
            put(attributes, "priority", nameOf(fields.get("priority")));
            put(attributes, "assignee", nameOf(fields.get("assignee")));
            put(attributes, "reporter", nameOf(fields.get("reporter")));
            put(attributes, "project", nameOf(fields.get("project")));
            put(attributes, "resolution", nameOf(fields.get("resolution")));
            put(attributes, "labels", join(fields.get("labels")));
            put(attributes, "components", names(fields.get("components")));
            item.put("attributes", attributes);
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
            Map<String, Object> version = mapOf(result.get("version"));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("externalId", id);
            item.put("title", first(string(result.get("title")), "(无标题)"));
            item.put("url", confluenceUrl(base(connector), result.get("_links"), id));
            item.put("updatedAt", version.get("when"));
            item.put("excerpt", excerpt(bodyOf(result)));
            item.put("type", first(string(result.get("type")).toUpperCase(), "PAGE"));
            Map<String, Object> attributes = new LinkedHashMap<String, Object>();
            put(attributes, "space", nameOf(result.get("space")));
            put(attributes, "status", result.get("status"));
            put(attributes, "author", nameOf(version.get("by")));
            put(attributes, "version", version.get("number"));
            item.put("attributes", attributes);
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
        // 通用 REST 没有「类型」这个概念：由配置指定哪个字段是类型（typeField），
        // 没配就归入 ITEM；其余标量字段作为属性，这样不同类型的条目也能各自展示。
        String typePath = string(config.get("typeField"));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("externalId", first(pick(row, mapping(fields, "externalId", "id")), ""));
            item.put("title", first(pick(row, mapping(fields, "title", "title")), "(无标题)"));
            item.put("url", pick(row, mapping(fields, "url", "url")));
            item.put("updatedAt", pick(row, mapping(fields, "updatedAt", "updatedAt")));
            item.put("excerpt", excerpt(pick(row, mapping(fields, "excerpt", "content"))));
            item.put("type", first(typePath.isEmpty() ? null : valueAt(row, typePath), "ITEM"));
            item.put("attributes", restAttributes(row, fields, typePath));
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

    /**
     * REST 条目的属性：映射出去的字段（标识 / 标题 / 链接…）不再重复出现，
     * 剩下的标量字段就是「这个类型的条目还有什么可看的」。上限只是防止一行几百列的接口把页面撑爆。
     */
    private Map<String, Object> restAttributes(Map<String, Object> row, Map<String, Object> fields, String typePath) {
        Set<String> used = new LinkedHashSet<String>();
        for (String name : new String[]{"externalId", "title", "url", "updatedAt", "excerpt"}) {
            for (String candidate : mapping(fields, name, "")) {
                if (!candidate.isEmpty()) {
                    used.add(candidate);
                }
            }
        }
        if (!typePath.isEmpty()) {
            used.add(typePath);
        }
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (attributes.size() >= REST_ATTRIBUTE_LIMIT) {
                break;
            }
            if (used.contains(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            // 时间字段每条都不一样，做成筛选维度只会得到 N 个取值各 1 条，详情里看得到就够了。
            if (isTimeAttribute(entry.getKey(), entry.getValue())) {
                continue;
            }
            if (entry.getValue() instanceof Map || entry.getValue() instanceof List) {
                continue;
            }
            put(attributes, entry.getKey(), entry.getValue());
        }
        return attributes;
    }

    /**
     * 时间类属性不进筛选面板：Jira 的 created、REST 源的 createTime 之类每条一个值，
     * 摆到左侧既点不动也筛不出东西。详情里仍然看得到，所以这里只是不让它成为维度。
     */
    public static boolean isTimeAttribute(String key, Object value) {
        if (TIME_ATTRIBUTE_KEYS.contains(key == null ? "" : key.toLowerCase())) {
            return true;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        return ISO_TIME.matcher(text).matches() || SLASH_TIME.matcher(text).matches() || EPOCH_MILLIS.matcher(text).matches();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    /** Jira / Confluence 的对象字段取值统一走 name：{@code status -> {"name":"进行中"}}。 */
    private Object nameOf(Object value) {
        if (value instanceof Map) {
            Object name = ((Map<?, ?>) value).get("name");
            if (name != null) {
                return name;
            }
            Object key = ((Map<?, ?>) value).get("key");
            if (key != null) {
                return key;
            }
            return ((Map<?, ?>) value).get("displayName");
        }
        return value;
    }

    /** 数组字段（components / labels）压成一个可筛选、可比较的字符串。 */
    private String names(Object value) {
        if (!(value instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<String>();
        for (Object row : (List<?>) value) {
            String name = string(nameOf(row));
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    private String join(Object value) {
        if (!(value instanceof List)) {
            return value == null ? null : string(value);
        }
        List<String> out = new ArrayList<String>();
        for (Object row : (List<?>) value) {
            String text = string(row);
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    /** 属性只在真的有值时落库：空占位会让「按属性筛选」出现一堆空选项。 */
    private void put(Map<String, Object> attributes, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = value instanceof List || value instanceof Map ? join(value) : string(value);
        if (!text.isEmpty()) {
            attributes.put(key, text);
        }
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

    /**
     * 是否跳过证书校验：连接器自己声明（insecureSsl=true / verifySsl=false）优先，
     * 其次是全局开关。两者都关时仍走严格校验，不会因为一个内网源就把整机的校验关掉。
     */
    private boolean trustAll(ConnectorSource connector) {
        if (properties != null && properties.isConnectorTrustAllSsl()) {
            return true;
        }
        Map<String, Object> config = config(connector);
        Object insecure = config.get("insecureSsl");
        if (insecure == null) {
            insecure = config.get("verifySsl");
            if (insecure != null) {
                return !Boolean.parseBoolean(String.valueOf(insecure).trim());
            }
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(insecure).trim());
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
        RestTemplate client = trustAll(connector) ? trustAllRestTemplate : restTemplate;
        ResponseEntity<String> response;
        try {
            // 必须传 URI：把 String 交给 RestTemplate 会被当成 URI 模板再编码一次，
            // JQL 里的 %22 / %25 会变成 %2522 / %2525，对端只解码一次，引号就变成了字面量。
            response = client.exchange(requestUri(url), HttpMethod.GET, new HttpEntity<String>(headers), String.class);
        } catch (HttpStatusCodeException ex) {
            // 上游 4xx/5xx 的正文才是真正的原因（JQL 语法、字段不存在、令牌过期…），
            // 原样丢回去只会让人看到一坨 JSON。
            String detail = describeUpstreamError(ex.getRawStatusCode(), ex.getResponseBodyAsString());
            log.warn("connector rejected type={} url={} status={} message={}", connector.getType(), url, ex.getRawStatusCode(), detail);
            throw new IllegalArgumentException("连接数据源失败：" + detail + jqlHint(connector, detail));
        } catch (RestClientException ex) {
            log.warn("connector request failed type={} url={} trustAll={} message={}", connector.getType(), url,
                client == trustAllRestTemplate, ex.getMessage());
            throw new IllegalArgumentException("连接数据源失败：" + ex.getMessage() + sslHint(ex, client == trustAllRestTemplate));
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

    /**
     * 查询串在拼接时已经编码过一次（见 encode），这里只做解析、不再编码。
     * 地址里若还有没编码的字符（中文路径最直接），退回让 Spring 补一次编码。
     */
    private URI requestUri(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException ex) {
            try {
                return UriComponentsBuilder.fromUriString(url).encode().build().toUri();
            } catch (RuntimeException nested) {
                throw new IllegalArgumentException("数据源地址不合法：" + url);
            }
        }
    }

    /**
     * Jira / Confluence 的错误正文有固定形状（errorMessages 数组、errors 对象），REST 源则五花八门。
     * 统一压成一行人话，再不行才回退到截断的原文。
     */
    private String describeUpstreamError(int status, String body) {
        String text = body == null ? "" : body.trim();
        if (!text.isEmpty()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
                Object messages = parsed.get("errorMessages");
                if (messages instanceof List && !((List<?>) messages).isEmpty()) {
                    return "数据源返回 " + status + "：" + String.join("；", strings((List<?>) messages));
                }
                Object errors = parsed.get("errors");
                if (errors instanceof Map && !((Map<?, ?>) errors).isEmpty()) {
                    return "数据源返回 " + status + "：" + String.valueOf(errors);
                }
                for (String key : new String[]{"message", "errorMessage", "error", "msg"}) {
                    Object value = parsed.get(key);
                    if (value != null && !String.valueOf(value).trim().isEmpty()) {
                        return "数据源返回 " + status + "：" + String.valueOf(value).trim();
                    }
                }
            } catch (IOException ex) {
                // 不是 JSON，退回原文片段
            }
        }
        return "数据源返回 " + status + (text.isEmpty() ? "" : "：" + clip(text, 300));
    }

    private List<String> strings(List<?> values) {
        List<String> out = new ArrayList<String>();
        for (Object value : values) {
            if (value != null) {
                out.add(String.valueOf(value).trim());
            }
        }
        return out;
    }

    private String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    /** JQL 的语法错误（尤其是保留字符）照抄 Jira 的原文看不懂，补一句能照着改的说明。 */
    private String jqlHint(ConnectorSource connector, String detail) {
        if (!"JIRA".equals(upper(connector.getType()))) {
            return "";
        }
        boolean jqlIssue = detail.contains("JQL") || detail.contains("保留字符") || detail.contains("reserved")
            || detail.contains("does not exist") || detail.contains("Field");
        if (!jqlIssue) {
            return "";
        }
        return "（JQL 中 % 等字符是保留字符：把关键词放进英文双引号，如 summary ~ \"%关键词%\"；"
            + "确需字面量时写成 \\u0025。也可先留空 JQL 用默认排序连通，再逐步加条件）";
    }

    /**
     * 证书类失败默认只抛一长串 PKIX 堆栈，看的人不知道下一步该做什么；
     * 这里按是否已经放过证书校验，补一句能照着做的提示。
     */
    private String sslHint(Exception ex, boolean trustAll) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        boolean certIssue = message.contains("PKIX") || message.contains("SSLHandshake")
            || message.contains("CertPath") || message.contains("certificate");
        if (!certIssue) {
            return "";
        }
        return trustAll
            ? "（已跳过证书校验仍失败：请确认站点地址、端口与 https 协议是否正确，以及网络是否可达）"
            : "（目标站点使用了不受信任的证书，常见为自签名证书或 IP 直连：可在该数据源配置里开启 insecureSsl=true，"
                + "或由管理员设置 KNOWLEDGE_CONNECTOR_TRUST_ALL_SSL=true）";
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
