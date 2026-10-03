package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ConnectorSource;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.ConnectorRequest;
import com.jh.knowledgeos.mapper.ConnectorItemMapper;
import com.jh.knowledgeos.mapper.ConnectorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saved connectors for a knowledge base: Jira projects, Confluence spaces and generic RESTful
 * list endpoints.
 *
 * <p>The console needs three things from a connector — keep the definition, prove it still
 * connects, and show what it currently holds — which is what {@link #list}, {@link #test} and
 * {@link #items} do. Importing those items into documents is deliberately absent; listing first
 * lets a user see whether the JQL or field mapping is right before anything is written into the
 * knowledge base.</p>
 *
 * <p>Secrets never leave this class: they are written from the form and returned as a boolean.
 * Knowledge-base access is asserted here as well as by the interceptor, because most of these
 * endpoints address a connector by its own id and the interceptor can only read a {@code kb}
 * path variable.</p>
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);

    private static final List<String> TYPES = Arrays.asList("JIRA", "CONFLUENCE", "REST");

    private static final List<String> AUTH_TYPES = Arrays.asList("NONE", "BASIC", "BEARER");

    private static final int TEST_LIMIT = 5;

    private static final int MESSAGE_LIMIT = 500;

    private final ConnectorMapper mapper;

    private final ConnectorItemMapper itemMapper;

    private final ConnectorFetchService fetchService;

    private final KnowledgeBaseAccessService access;

    private final ObjectMapper objectMapper;

    public ConnectorService(ConnectorMapper mapper, ConnectorItemMapper itemMapper, ConnectorFetchService fetchService,
        KnowledgeBaseAccessService access, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.itemMapper = itemMapper;
        this.fetchService = fetchService;
        this.access = access;
        this.objectMapper = objectMapper;
    }

    public List<ConnectorSource> list(Long kb) {
        List<ConnectorSource> rows = mapper.list(kb);
        for (ConnectorSource row : rows) {
            mask(row);
        }
        return rows;
    }

    public ConnectorSource create(ConnectorRequest request, User user) {
        ConnectorSource source = build(request);
        access.assertAccess(source.getKnowledgeBaseId(), true, user);
        source.setConnectorCode("CONN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        source.setCreatedBy(user == null ? null : user.getUsername());
        mapper.insert(source);
        return mask(source);
    }

    public ConnectorSource update(Long id, ConnectorRequest request) {
        ConnectorSource existing = require(id);
        access.assertWrite(existing.getKnowledgeBaseId());
        ConnectorSource source = build(request);
        source.setId(id);
        // An empty secret means "keep the saved one": the console never receives the value back,
        // so sending it blank is the normal case when only the JQL or the URL changed.
        if (request.getSecret() == null || request.getSecret().trim().isEmpty()) {
            source.setSecret(null);
        }
        source.setConnectorCode(existing.getConnectorCode());
        mapper.update(source);
        return mask(mapper.get(id));
    }

    public void delete(Long id) {
        ConnectorSource existing = require(id);
        access.assertWrite(existing.getKnowledgeBaseId());
        // 同步下来的条目是连接器的私有副本：连接器没了，副本也该走。
        itemMapper.deleteByConnector(id);
        mapper.delete(id);
    }

    /** Proves the connection still works by reading the smallest useful page. */
    public Map<String, Object> test(Long id) {
        ConnectorSource source = require(id);
        access.assertRead(source.getKnowledgeBaseId());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            ConnectorFetchService.Page page = fetchService.fetch(source, TEST_LIMIT, 0);
            result.put("ok", Boolean.TRUE);
            result.put("itemCount", page.getItems().size());
            result.put("message", "连接成功，读取到 " + page.getItems().size() + " 条"
                + (page.getTotal() == null ? "" : "（共 " + page.getTotal() + " 条）"));
            mapper.updateTestResult(id, "OK", clip((String) result.get("message")));
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? "连接失败" : ex.getMessage();
            log.warn("connector test failed id={} message={}", id, message);
            result.put("ok", Boolean.FALSE);
            result.put("itemCount", 0);
            result.put("message", message);
            mapper.updateTestResult(id, "FAILED", clip(message));
        }
        return result;
    }

    /** Reads a page of items from an unsaved definition, so the form can be checked first. */
    public Map<String, Object> preview(ConnectorRequest request) {
        ConnectorSource source = build(request);
        if (source.getKnowledgeBaseId() != null) {
            access.assertRead(source.getKnowledgeBaseId());
        }
        ConnectorFetchService.Page page = fetchService.fetch(source, ConnectorFetchService.DEFAULT_LIMIT, 0);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", Boolean.TRUE);
        result.put("items", page.getItems());
        result.put("total", page.getTotal());
        result.put("limit", ConnectorFetchService.DEFAULT_LIMIT);
        result.put("offset", 0);
        result.put("message", "读取到 " + page.getItems().size() + " 条");
        return result;
    }

    /** The knowledge list a connector currently exposes. */
    public Map<String, Object> items(Long id, int limit, int offset) {
        ConnectorSource source = require(id);
        access.assertRead(source.getKnowledgeBaseId());
        int size = Math.min(Math.max(limit <= 0 ? ConnectorFetchService.DEFAULT_LIMIT : limit, 1), ConnectorFetchService.MAX_LIMIT);
        ConnectorFetchService.Page page = fetchService.fetch(source, size, offset < 0 ? 0 : offset);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("connector", summary(mask(source)));
        result.put("items", page.getItems());
        result.put("total", page.getTotal());
        result.put("limit", size);
        result.put("offset", offset < 0 ? 0 : offset);
        return result;
    }

    private ConnectorSource require(Long id) {
        ConnectorSource source = mapper.get(id);
        if (source == null) {
            throw new IllegalArgumentException("连接器不存在：" + id);
        }
        return source;
    }

    private ConnectorSource build(ConnectorRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("缺少连接器配置");
        }
        ConnectorSource source = new ConnectorSource();
        source.setKnowledgeBaseId(request.getKnowledgeBaseId());
        String name = trim(request.getName());
        String type = trim(request.getType()).toUpperCase();
        String baseUrl = trim(request.getBaseUrl());
        String authType = trim(request.getAuthType()).toUpperCase();
        if (request.getKnowledgeBaseId() == null) {
            throw new IllegalArgumentException("缺少 knowledgeBaseId");
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("连接器名称不能为空");
        }
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的连接器类型：" + request.getType() + "（支持 JIRA / CONFLUENCE / REST）");
        }
        if (baseUrl.isEmpty() || (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("站点地址必须是以 http:// 或 https:// 开头的完整地址");
        }
        if (authType.isEmpty()) {
            authType = "NONE";
        }
        if (!AUTH_TYPES.contains(authType)) {
            throw new IllegalArgumentException("不支持的认证方式：" + request.getAuthType());
        }
        source.setName(name);
        source.setType(type);
        source.setBaseUrl(baseUrl);
        source.setAuthType(authType);
        source.setUsername(trim(request.getUsername()));
        source.setSecret(request.getSecret() == null || request.getSecret().trim().isEmpty() ? null : request.getSecret());
        source.setStatus("ACTIVE");
        source.setConfigJson(toJson(request.getConfig()));
        source.setConfig(request.getConfig() == null ? Collections.<String, Object>emptyMap() : request.getConfig());
        return source;
    }

    private String toJson(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (IOException ex) {
            throw new IllegalArgumentException("连接器配置无法序列化：" + ex.getMessage());
        }
    }

    /** Replaces the secret with a flag before anything is serialised to the console. */
    private ConnectorSource mask(ConnectorSource source) {
        if (source == null) {
            return null;
        }
        boolean configured = source.getSecret() != null && !source.getSecret().trim().isEmpty();
        if (source.getConfigJson() != null && source.getConfig() == null) {
            try {
                source.setConfig(objectMapper.readValue(source.getConfigJson(), Map.class));
            } catch (IOException ex) {
                source.setConfig(Collections.<String, Object>emptyMap());
            }
        }
        source.setSecret(null);
        source.setSecretConfigured(Boolean.valueOf(configured));
        return source;
    }

    /** Only what the item table needs, so a 500-char message never ships with every read. */
    private Map<String, Object> summary(ConnectorSource source) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", source.getId());
        row.put("connectorCode", source.getConnectorCode());
        row.put("name", source.getName());
        row.put("type", source.getType());
        row.put("baseUrl", source.getBaseUrl());
        row.put("status", source.getStatus());
        row.put("lastTestStatus", source.getLastTestStatus());
        row.put("lastTestMessage", source.getLastTestMessage());
        row.put("lastTestTime", source.getLastTestTime());
        return row;
    }

    private String clip(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MESSAGE_LIMIT ? value : value.substring(0, MESSAGE_LIMIT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
