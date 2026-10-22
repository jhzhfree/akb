package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.dto.MemoryCaptureRequest;
import com.jh.knowledgeos.dto.MemoryRecallRequest;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final AgentMemoryService memoryService;

    @Autowired
    public McpServerService(ExternalAccessService accessService, ExternalKnowledgeFacade facade, ObjectMapper objectMapper,
        AgentMemoryService memoryService) {
        this.accessService = accessService;
        this.facade = facade;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
    }

    public McpServerService(ExternalAccessService accessService, ExternalKnowledgeFacade facade, ObjectMapper objectMapper) {
        this(accessService, facade, objectMapper, null);
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
                return result(id, toolsList(key));
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
        payload.put("instructions", "用 knowledge_query 提问会返回带引用的答案；knowledge_search 只做检索。"
            + "平台启用记忆服务且凭证允许 MEMORY_READ / MEMORY_WRITE 时，会显示对应记忆工具。"
            + "知识库范围、Agent 身份和记忆命名空间授权始终由服务端强制执行。");
        return payload;
    }

    public Map<String, Object> toolsList() {
        return toolsList(null);
    }

    public Map<String, Object> toolsList(ApiKey key) {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        if (key == null || key.hasScope(ApiKey.SCOPE_QUERY)) {
        tools.add(tool("knowledge_query",
            "可追溯知识问答：返回答案、引用与 queryId，可用于后续溯源；答案可按 answerFormat 交付成 Markdown / 纯文本 / HTML",
            schema(properties(
                prop("knowledgeBaseId", "integer", "知识库 ID，兼容旧调用"),
                prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略此项"),
                prop("question", "string", "要提问的问题"), prop("topK", "integer", "召回数量，默认 10"),
                arrayProp("permissionTags", "可选的检索标签过滤；知识库 ACL 仍由服务端强制执行"),
                prop("minScore", "number", "最低相关度分数（0~1）：低于该分数的候选不参与答案生成；不传即不过滤"),
                prop("answerFormat", "string", "答案返回格式：default（不传即此项）/ markdown / text / html；返回值里的 answerFormat 是实际生效的那个")),
                Arrays.asList("question"))));
        }
        if (key == null || key.hasScope(ApiKey.SCOPE_SEARCH)) {
        tools.add(tool("knowledge_search",
            "只做检索不生成答案：返回召回候选、重排结果与引用，便于自行装配上下文",
            schema(properties(
                prop("knowledgeBaseId", "integer", "知识库 ID，兼容旧调用"),
                prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略此项"),
                prop("query", "string", "检索语句"), prop("topK", "integer", "召回数量，默认 10"),
                arrayProp("permissionTags", "可选的检索标签过滤；知识库 ACL 仍由服务端强制执行"),
                prop("minScore", "number", "最低相关度分数（0~1）：低于该分数的候选不返回；不传即不过滤。返回里的 returned 是过滤后实际给出的条数")),
                Arrays.asList("query"))));
        }
        if (key == null || key.hasScope(ApiKey.SCOPE_LIST)) {
        tools.add(tool("list_knowledge_bases", "列出当前 API Key 可见的知识库", schema(new LinkedHashMap<String, Object>(), new ArrayList<String>())));
        }
        if (key == null || key.hasScope(ApiKey.SCOPE_READ_OBJECT)) {
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
        }
        if (isMemoryEnabled() && key != null && key.hasScope(ApiKey.SCOPE_MEMORY_READ)) {
            tools.add(tool("memory_recall", "按当前 Agent 或已授权团队/项目/任务/工作流/会话作用域召回记忆",
                schema(properties(prop("query", "string", "待召回的关键词或问题"), prop("scopeType", "string", "AGENT（默认）/ TEAM / PROJECT / TASK / WORKFLOW / SESSION"),
                    prop("scopeCode", "string", "共享空间或会话编码；AGENT 身份由 API Key 确定"), prop("limit", "integer", "返回数量，默认 10，最多 50")),
                    Arrays.asList("query"))));
        }
        if (isMemoryEnabled() && key != null && key.hasScope(ApiKey.SCOPE_MEMORY_WRITE)) {
            tools.add(tool("memory_capture", "保存有来源、作用域和有效期的 Agent 记忆",
                schema(properties(prop("memoryLevel", "string", "L0 会话片段 / L1 原子记忆 / L2 场景记忆 / L3 Agent 画像"),
                    prop("scopeType", "string", "AGENT / TEAM / PROJECT / TASK / WORKFLOW / SESSION"), prop("scopeCode", "string", "共享空间或会话编码"),
                    prop("content", "string", "需要持久化的经验或上下文"), arrayProp("tags", "可选标签"),
                    prop("sourceType", "string", "TASK_RESULT / USER_CONFIRMED / WORKFLOW / MANUAL / OTHER"),
                    prop("sourceRef", "string", "来源任务、流程或对象编码"), prop("idempotencyKey", "string", "调用重试时保持不变，避免重复写入"),
                    prop("confidence", "number", "可信度 0-1"),
                    prop("importance", "integer", "重要度 1-5"), prop("expiresInDays", "integer", "有效期 1-3650 天")),
                    Arrays.asList("memoryLevel", "scopeType", "content"))));
            tools.add(tool("memory_forget", "删除当前 Agent 私有记忆或已授权命名空间中的共享记忆",
                schema(properties(prop("memoryCode", "string", "记忆编码")), Arrays.asList("memoryCode"))));
        }
        if (key != null && key.hasScope(ApiKey.SCOPE_INGEST)) {
            tools.add(tool("ingest_text", "把一段文本接入知识库（需要 INGEST 权限）",
                schema(properties(
                    prop("knowledgeBaseId", "integer", "知识库 ID"),
                    prop("title", "string", "标题，作为来源名"),
                    prop("text", "string", "正文内容"),
                    prop("permissionTags", "string", "权限标签，逗号分隔")),
                    Arrays.asList("knowledgeBaseId", "text"))));
        }
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
            accessService.assertScope(key, ApiKey.SCOPE_QUERY);
            Long kb = resolve(key, args);
            value = facade.query(key, kb, text(args, "question"), integer(args, "topK"), strings(args, "permissionTags"),
                PlatformEventPublisher.SOURCE_MCP, text(args, "answerFormat"), resolveMinScore(args));
        } else if ("knowledge_search".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_SEARCH);
            Long kb = resolve(key, args);
            value = facade.search(key, kb, text(args, "query"), integer(args, "topK"), strings(args, "permissionTags"),
                PlatformEventPublisher.SOURCE_MCP, resolveMinScore(args));
        } else if ("list_knowledge_bases".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_LIST);
            value = facade.basesFor(key, accessService.grantedKnowledgeBases(key));
        } else if ("list_knowledge_cards".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.cards(kb, null);
        } else if ("get_knowledge_card".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.objectDetail(kb, "CARD", text(args, "code"), null);
        } else if ("list_claims".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.claims(kb, null);
        } else if ("get_claim".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.objectDetail(kb, "CLAIM", text(args, "code"), null);
        } else if ("get_evidence".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.objectDetail(kb, "EVIDENCE", text(args, "evidenceCode"), null);
        } else if ("graph_neighbors".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.graph(kb, text(args, "code"), integer(args, "depth"), integer(args, "limit"), null);
        } else if ("ingest_text".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_INGEST);
            Long kb = resolve(key, args);
            value = facade.ingestText(kb, text(args, "title"), text(args, "text"), text(args, "permissionTags"));
        } else if ("memory_capture".equals(name)) {
            requireMemoryService();
            MemoryCaptureRequest request = new MemoryCaptureRequest();
            request.setMemoryLevel(text(args, "memoryLevel"));
            request.setScopeType(text(args, "scopeType"));
            request.setScopeCode(text(args, "scopeCode"));
            request.setContent(text(args, "content"));
            request.setTags(strings(args, "tags"));
            request.setSourceType(text(args, "sourceType"));
            request.setSourceRef(text(args, "sourceRef"));
            request.setIdempotencyKey(text(args, "idempotencyKey"));
            request.setConfidence(decimal(args, "confidence"));
            request.setImportance(integer(args, "importance"));
            request.setExpiresInDays(integer(args, "expiresInDays"));
            value = memoryService.capture(key, request);
        } else if ("memory_recall".equals(name)) {
            requireMemoryService();
            MemoryRecallRequest request = new MemoryRecallRequest();
            request.setQuery(text(args, "query"));
            request.setScopeType(text(args, "scopeType"));
            request.setScopeCode(text(args, "scopeCode"));
            request.setLimit(integer(args, "limit"));
            value = memoryService.recall(key, request);
        } else if ("memory_forget".equals(name)) {
            requireMemoryService();
            value = memoryService.forget(key, text(args, "memoryCode"));
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
        accessService.assertScope(key, ApiKey.SCOPE_LIST);
        List<Map<String, Object>> resources = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> base : facade.basesFor(key, accessService.grantedKnowledgeBases(key))) {
            Map<String, Object> resource = new LinkedHashMap<String, Object>();
            resource.put("uri", "knowledge://kb/" + base.get("id"));
            resource.put("name", String.valueOf(base.get("name")));
            String description = "知识库概览：" + base.get("baseCode") + "（可见范围 " + base.get("visibility") + "）";
            Object sample = base.get("defaultQuery");
            if (sample != null && !String.valueOf(sample).trim().isEmpty()) {
                description = description + "，示例问句：" + String.valueOf(sample).trim();
            }
            resource.put("description", description);
            resource.put("mimeType", "application/json");
            resources.add(resource);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("resources", resources);
        return payload;
    }

    public Map<String, Object> resourcesRead(ApiKey key, Map<String, Object> params) {
        accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
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
        Object rawCode = args.get("knowledgeBaseCode");
        if (rawCode != null && !String.valueOf(rawCode).trim().isEmpty()) {
            String code = String.valueOf(rawCode).trim();
            if (code.length() > 128) {
                throw new IllegalArgumentException("knowledgeBaseCode 超出长度限制");
            }
            Long codeMatch = null;
            for (Map<String, Object> base : facade.basesFor(key, accessService.grantedKnowledgeBases(key))) {
                if (code.equals(String.valueOf(base.get("baseCode")))) {
                    Object id = base.get("id");
                    if (id instanceof Number) {
                        codeMatch = ((Number) id).longValue();
                    }
                    break;
                }
            }
            if (codeMatch == null) {
                throw new IllegalArgumentException("knowledgeBaseCode 不存在或当前 API Key 不可访问");
            }
            if (requested != null && !requested.equals(codeMatch)) {
                throw new IllegalArgumentException("knowledgeBaseId 与 knowledgeBaseCode 不匹配");
            }
            requested = codeMatch;
        }
        return accessService.resolveKnowledgeBase(key, requested);
    }

    private void requireMemoryService() {
        if (memoryService == null) {
            throw new IllegalStateException("Agent 记忆服务不可用");
        }
    }

    private boolean isMemoryEnabled() {
        // A null service occurs only in legacy lightweight unit fixtures; the production Spring
        // graph always supplies AgentMemoryService, whose deployment switch is authoritative.
        return memoryService == null || memoryService.isEnabled();
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

    private List<String> strings(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List)) {
            return new ArrayList<String>();
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (item != null && !String.valueOf(item).trim().isEmpty()) {
                result.add(String.valueOf(item).trim());
            }
        }
        return result;
    }

    /**
     * A score floor arrives as a JSON number and has to survive being handed over as a string by
     * a client that stringifies every argument; an unusable value is dropped rather than turned
     * into 0.0, because 0.0 means "accept everything" and that is not what a caller who typed
     * something wrong asked for.
     */
    private Double decimal(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析最低相关度 {@code minScore}（0~1）。与 REST / SSE 同一语义：不传即不过滤。
     *
     * <p>数值解析沿用 {@link #decimal} 的宽松策略（数字或字符串都收，解析不了的丢掉而不是
     * 变成 0.0——0.0 等于"全收"，那不是输错值的调用方想要的结果）。范围则与 REST 的
     * {@code @Min(0) @Max(1)}、SSE 的显式校验一致：越界直接报 400，不让它静默生效。</p>
     */
    private Double resolveMinScore(Map<String, Object> args) {
        Double minScore = decimal(args, "minScore");
        if (minScore != null && (minScore < 0.0d || minScore > 1.0d)) {
            throw new IllegalArgumentException("minScore 必须在 0~1 之间");
        }
        return minScore;
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

    private Map<String, Object> arrayProp(String name, String description) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("type", "string");
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", "array");
        property.put("description", description);
        property.put("items", item);
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put(name, property);
        return wrapper;
    }
}
