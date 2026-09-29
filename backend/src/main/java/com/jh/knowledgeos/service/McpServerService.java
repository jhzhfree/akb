package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model Context Protocol server exposing knowledge capabilities as MCP tools and resources.
 *
 * <p>The platform acts as an <em>MCP Server</em> here: external assistants, IDEs and agents
 * connect as MCP clients and call the same application services the console uses. This is
 * deliberately separate from the platform's own outbound MCP-client capability, which belongs
 * to the adaptation layer.</p>
 */
@Service
public class McpServerService {

    public static final String PROTOCOL_VERSION = "2024-11-05";

    public static final String SERVER_NAME = "knowledge-os";

    public static final String SERVER_VERSION = "1.2.0";

    private final ExternalAccessService accessService;

    private final ExternalKnowledgeFacade facade;

    private final ObjectMapper objectMapper;

    public McpServerService(ExternalAccessService accessService, ExternalKnowledgeFacade facade, ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.facade = facade;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles one JSON-RPC 2.0 message; notifications (no id) produce no response.
     */
    public Map<String, Object> handle(Map<String, Object> request, ApiKey key) {
        Object id = request == null ? null : request.get("id");
        String method = request == null ? null : String.valueOf(request.get("method"));
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (request != null && request.get("params") instanceof Map) {
            params = (Map<String, Object>) request.get("params");
        }
        if (method == null || method.isEmpty()) {
            return error(id, -32600, "缺少 method 字段");
        }
        try {
            if ("initialize".equals(method)) {
                return result(id, initialize());
            }
            if ("ping".equals(method)) {
                return result(id, new LinkedHashMap<String, Object>());
            }
            if ("tools/list".equals(method)) {
                return result(id, toolsList());
            }
            if ("tools/call".equals(method)) {
                return result(id, toolsCall(key, params));
            }
            if ("resources/list".equals(method)) {
                return result(id, resourcesList(key));
            }
            if ("resources/read".equals(method)) {
                return result(id, resourcesRead(key, params));
            }
            if ("notifications/initialized".equals(method)) {
                return null;
            }
            return error(id, -32601, "不支持的方法：" + method);
        } catch (SecurityException e) {
            return error(id, -32001, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (Exception e) {
            return error(id, -32603, String.valueOf(e.getMessage()));
        }
    }

    public Map<String, Object> initialize() {
        Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
        Map<String, Object> tools = new LinkedHashMap<String, Object>();
        tools.put("listChanged", Boolean.FALSE);
        Map<String, Object> resources = new LinkedHashMap<String, Object>();
        resources.put("subscribe", Boolean.FALSE);
        resources.put("listChanged", Boolean.FALSE);
        capabilities.put("tools", tools);
        capabilities.put("resources", resources);
        Map<String, Object> serverInfo = new LinkedHashMap<String, Object>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("protocolVersion", PROTOCOL_VERSION);
        payload.put("capabilities", capabilities);
        payload.put("serverInfo", serverInfo);
        payload.put("instructions", "用 knowledge_query 提问会返回带引用的答案；knowledge_search 只做检索。" + "所有工具都要求 knowledgeBaseId 在 API Key 授权范围内。");
        return payload;
    }

    public Map<String, Object> toolsList() {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        tools.add(tool("knowledge_query", "可追溯知识问答：返回答案、引用与 queryId，可用于后续溯源", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID"),
            prop("question", "string", "要提问的问题"), prop("topK", "integer", "召回数量，默认 10")), Arrays.asList("knowledgeBaseId", "question"))));
        tools.add(tool("knowledge_search", "只做检索不生成答案：返回召回候选、重排结果与引用，便于自行装配上下文", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID"),
            prop("query", "string", "检索语句"), prop("topK", "integer", "召回数量，默认 10")), Arrays.asList("knowledgeBaseId", "query"))));
        tools.add(tool("list_knowledge_bases", "列出当前 API Key 可见的知识库", schema(new LinkedHashMap<String, Object>(), new ArrayList<String>())));
        tools.add(tool("list_knowledge_cards", "列出知识卡片（结构化标准答案，如 SOP）", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID")),
            Arrays.asList("knowledgeBaseId"))));
        tools.add(tool("get_knowledge_card", "按编码读取一张知识卡片的详情与证据链", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID"), prop("code",
            "string", "知识卡片编码")), Arrays.asList("knowledgeBaseId", "code"))));
        tools.add(tool("list_claims", "列出断言（可被判断真伪的结论性陈述）", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID")),
            Arrays.asList("knowledgeBaseId"))));
        tools.add(tool("get_claim", "按编码读取一条断言的详情", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID"), prop("code", "string", "断言编码")),
            Arrays.asList("knowledgeBaseId", "code"))));
        tools.add(tool("get_evidence", "按编码读取一条证据的原文与出处", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID"), prop("evidenceCode",
            "string", "证据编码")), Arrays.asList("knowledgeBaseId", "evidenceCode"))));
        tools.add(tool("graph_neighbors", "读取实体/知识对象的图谱邻域", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID"), prop("code", "string",
            "对象编码"), prop("depth", "integer", "跳数，默认 2"), prop("limit", "integer", "返回上限，默认 50")), Arrays.asList("knowledgeBaseId", "code"))));
        tools.add(tool("ingest_text", "把一段文本接入知识库（需要 INGEST 权限）", schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID"), prop("title",
            "string", "标题，作为来源名"), prop("text", "string", "正文内容"), prop("permissionTags", "string", "权限标签，逗号分隔")), Arrays.asList("knowledgeBaseId", "text"))));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tools", tools);
        return payload;
    }

    public Map<String, Object> toolsCall(ApiKey key, Map<String, Object> params) throws Exception {
        String name = params == null ? null : String.valueOf(params.get("name"));
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        if (params != null && params.get("arguments") instanceof Map) {
            args = (Map<String, Object>) params.get("arguments");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("缺少工具名称");
        }
        Object value;
        if ("knowledge_query".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.query(kb, text(args, "question"), integer(args, "topK"), null);
        } else if ("knowledge_search".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.search(kb, text(args, "query"), integer(args, "topK"), null);
        } else if ("list_knowledge_bases".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.bases(key.getKnowledgeBaseId());
        } else if ("list_knowledge_cards".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.cards(kb, null);
        } else if ("get_knowledge_card".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.objectDetail(kb, "CARD", text(args, "code"), null);
        } else if ("list_claims".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.claims(kb, null);
        } else if ("get_claim".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.objectDetail(kb, "CLAIM", text(args, "code"), null);
        } else if ("get_evidence".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.objectDetail(kb, "EVIDENCE", text(args, "evidenceCode"), null);
        } else if ("graph_neighbors".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_READ);
            value = facade.graph(kb, text(args, "code"), integer(args, "depth"), integer(args, "limit"), null);
        } else if ("ingest_text".equals(name)) {
            Long kb = resolve(key, args);
            accessService.assertScope(key, ApiKey.SCOPE_INGEST);
            value = facade.ingestText(kb, text(args, "title"), text(args, "text"), text(args, "permissionTags"));
        } else {
            throw new IllegalArgumentException("未知工具：" + name);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("content", Arrays.asList(textContent(value)));
        payload.put("structuredContent", value);
        payload.put("isError", Boolean.FALSE);
        return payload;
    }

    public Map<String, Object> resourcesList(ApiKey key) {
        accessService.assertScope(key, ApiKey.SCOPE_READ);
        List<Map<String, Object>> resources = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> base : facade.bases(key.getKnowledgeBaseId())) {
            Map<String, Object> resource = new LinkedHashMap<String, Object>();
            resource.put("uri", "knowledge://kb/" + base.get("id"));
            resource.put("name", String.valueOf(base.get("name")));
            resource.put("description", "知识库概览：" + base.get("baseCode") + "（可见范围 " + base.get("visibility") + "）");
            resource.put("mimeType", "application/json");
            resources.add(resource);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("resources", resources);
        return payload;
    }

    public Map<String, Object> resourcesRead(ApiKey key, Map<String, Object> params) {
        accessService.assertScope(key, ApiKey.SCOPE_READ);
        String uri = params == null ? null : String.valueOf(params.get("uri"));
        if (uri == null || !uri.startsWith("knowledge://")) {
            throw new IllegalArgumentException("不支持的资源 URI：" + uri);
        }
        String[] parts = uri.substring("knowledge://".length()).split("/");
        // knowledge://kb/{id}[/cards/{code}|/claims/{code}]
        Long kb = parts.length > 1 ? Long.valueOf(parts[1]) : null;
        Long resolved = accessService.resolveKnowledgeBase(key, kb);
        Object value;
        String type = parts.length > 2 ? parts[2] : "";
        String code = parts.length > 3 ? parts[3] : "";
        if ("cards".equalsIgnoreCase(type)) {
            value = facade.objectDetail(resolved, "CARD", code, null);
        } else if ("claims".equalsIgnoreCase(type)) {
            value = facade.objectDetail(resolved, "CLAIM", code, null);
        } else {
            value = facade.overview(resolved, null);
        }
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("uri", uri);
        content.put("mimeType", "application/json");
        content.put("text", toJson(value));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("contents", Arrays.asList(content));
        return payload;
    }

    private Long resolve(ApiKey key, Map<String, Object> args) {
        Long requested = null;
        Object raw = args.get("knowledgeBaseId");
        if (raw instanceof Number) {
            requested = ((Number) raw).longValue();
        } else if (raw != null && !String.valueOf(raw).isEmpty()) {
            requested = Long.valueOf(String.valueOf(raw).trim());
        }
        return accessService.resolveKnowledgeBase(key, requested);
    }

    private Map<String, Object> textContent(Object value) {
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("type", "text");
        content.put("text", toJson(value));
        return content;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> result(Object id, Map<String, Object> payload) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", payload);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        return response;
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> properties(Map<String, Object>... entries) {
        Map<String, Object> merged = new LinkedHashMap<String, Object>();
        for (Map<String, Object> entry : entries) {
            merged.putAll(entry);
        }
        return merged;
    }

    private Map<String, Object> prop(String name, String type, String description) {
        Map<String, Object> prop = new LinkedHashMap<String, Object>();
        prop.put("type", type);
        prop.put("description", description);
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put(name, prop);
        return wrapper;
    }
}
