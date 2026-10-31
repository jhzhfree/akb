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

    public static final String PROTOCOL_VERSION = "2025-06-18";

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
        if (request == null || !"2.0".equals(request.get("jsonrpc"))) {
            return error(id, -32600, "jsonrpc must be 2.0");
        }
        Object rawMethod = request.get("method");
        if (!(rawMethod instanceof String) || ((String) rawMethod).trim().isEmpty()) {
            return error(id, -32600, "缺少 method 字段");
        }
        String method = (String) rawMethod;
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (request != null && request.get("params") instanceof Map) {
            params = (Map<String, Object>) request.get("params");
        }
        if (method.startsWith("notifications/")) {
            // JSON-RPC notifications never receive a response. The HTTP adapter returns 202.
            return request.containsKey("id") ? error(id, -32600, "Notification must not include id") : null;
        }
        if (!request.containsKey("id") || id == null) {
            return error(id, -32600, "A JSON-RPC request requires a non-null id");
        }
        try {
            if ("initialize".equals(method)) {
                return result(id, initialize(params));
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
        // instructions 是模型决定「用不用工具、用哪个」的第一道输入：只写工具能做什么不够，
        // 必须把路由规则写出来——前者是接口说明，后者才是选择依据。
        payload.put("instructions", "这里是 AKB 企业知识库服务。"
            + "凡是涉及公司制度、流程 SOP、产品文档、故障处理、项目资料、技术规范的问题，"
            + "或用户要求「查一下 / 查文档 / 有没有规定 / 依据是什么」，"
            + "优先用 knowledge_query 作答，并把返回的引用一并给出。"
            + "只要原文片段、或要确认库里有没有某份资料时，用 knowledge_search。"
            + "不知道该查哪个知识库时，先用 list_knowledge_bases 拿到 ID 与稳定编码。"
            + "不要用这些工具做通用闲聊、计算或实时数据查询——库里没有答案就直说。"
            + "平台启用记忆服务且凭证允许 MEMORY_READ / MEMORY_WRITE 时，会显示对应记忆工具。"
            + "知识库范围、Agent 身份和记忆命名空间授权始终由服务端强制执行。");
        return payload;
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        if (params == null || !(params.get("protocolVersion") instanceof String)
            || !(params.get("capabilities") instanceof Map) || !(params.get("clientInfo") instanceof Map)) {
            throw new IllegalArgumentException("initialize requires protocolVersion, capabilities and clientInfo");
        }
        String requested = ((String) params.get("protocolVersion")).trim();
        Map<?, ?> clientInfo = (Map<?, ?>) params.get("clientInfo");
        if (requested.isEmpty() || blank(clientInfo.get("name")) || blank(clientInfo.get("version"))) {
            throw new IllegalArgumentException("initialize requires a protocolVersion and clientInfo name/version");
        }
        Map<String, Object> payload = initialize();
        // This stateless server implements the JSON request/response feature set introduced
        // through 2025-06-18. Echo a supported request; otherwise negotiate our supported level.
        payload.put("protocolVersion", PROTOCOL_VERSION.equals(String.valueOf(requested)) ? requested : PROTOCOL_VERSION);
        return payload;
    }

    private boolean blank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    public Map<String, Object> toolsList() {
        return toolsList(null);
    }

    public Map<String, Object> toolsList(ApiKey key) {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        if (key != null && key.getWorkloadIdentity() != null && AgentProofCrypto.V2.equals(key.getWorkloadIdentity().getProofProfile()) && key.hasScope(ApiKey.SCOPE_LIST)) {
            tools.add(tool("get_agent_identity", "当前终端身份",
                "返回服务端验签后的当前 Agent 身份，用于检查终端到 MCP 工具的身份传递；不接受客户端身份参数。",
                schema(new LinkedHashMap<String, Object>(), new ArrayList<String>()), true, false));
        }
        if (key == null || key.hasScope(ApiKey.SCOPE_QUERY)) {
        // 描述按「能做什么 / 什么时候用 / 什么时候别用 / 例子」写：平台选工具靠的是语义匹配，
        // 只写返回值格式的话，模型判断不出「用户问报销流程」该不该选它。
        tools.add(tool("knowledge_query", "知识库问答",
            "回答知识库中的问题，并返回可点击的引用来源（RAG 问答）。"
                + "当用户询问公司制度、流程 SOP、产品文档、故障处理、项目资料、技术规范等"
                + "「库里应该有答案」的问题时使用，也可用于事实查证与溯源。"
                + "不要用于通用闲聊、计算或实时数据查询。"
                + "例：knowledgeBaseCode=\"hr-policy\", question=\"年假怎么申请？\"",
            schema(properties(
                prop("knowledgeBaseId", "integer", "知识库 ID，兼容旧调用；不知道就先调 list_knowledge_bases"),
                prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略此项"),
                prop("question", "string", "要提问的问题，写成完整的自然语言问句"), prop("topK", "integer", "召回数量，默认 10"),
                arrayProp("permissionTags", "可选的检索标签过滤；知识库 ACL 仍由服务端强制执行"),
                prop("minScore", "number", "最低相关度分数（0~1）：低于该分数的候选不参与答案生成；不传即不过滤"),
                prop("answerFormat", "string", "答案返回格式：default（不传即此项）/ markdown / text / html；返回值里的 answerFormat 是实际生效的那个")),
                Arrays.asList("question")), true, false));
        }
        if (key == null || key.hasScope(ApiKey.SCOPE_SEARCH)) {
        tools.add(tool("knowledge_search", "知识库检索",
            "只在知识库里检索原文片段，不生成答案。"
                + "当你需要引用原文、要自己组织答案，或想确认库里有没有某份资料时使用；"
                + "要直接回答用户的问题则用 knowledge_query。检索语句用关键词比用整句命中率更高。"
                + "例：knowledgeBaseCode=\"hr-policy\", query=\"年假 申请 流程\"",
            schema(properties(
                prop("knowledgeBaseId", "integer", "知识库 ID，兼容旧调用；不知道就先调 list_knowledge_bases"),
                prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略此项"),
                prop("query", "string", "检索语句，关键词或问题均可"), prop("topK", "integer", "召回数量，默认 10"),
                arrayProp("permissionTags", "可选的检索标签过滤；知识库 ACL 仍由服务端强制执行"),
                prop("minScore", "number", "最低相关度分数（0~1）：低于该分数的候选不返回；不传即不过滤。返回里的 returned 是过滤后实际给出的条数")),
                Arrays.asList("query")), true, false));
        }
        if (key == null || key.hasScope(ApiKey.SCOPE_LIST)) {
        tools.add(tool("list_knowledge_bases", "列出知识库",
            "列出当前凭证可见的知识库（含 ID 与稳定编码）。"
                + "当你不知道该查哪个知识库、或需要 knowledgeBaseId / knowledgeBaseCode 时先调它，"
                + "它是其他知识工具的前置步骤。",
            schema(new LinkedHashMap<String, Object>(), new ArrayList<String>()), true, false));
        }
        if (key == null || key.hasScope(ApiKey.SCOPE_READ_OBJECT)) {
        // 卡片 / 断言 / 证据原本是六个工具，职责高度重叠（都是「按编码读对象」），模型在它们
        // 之间选错比选不中还常见。合成两个，用 objectType 区分；旧工具名在 tools/call 里继续
        // 支持，硬编码的老调用方不受影响。
        tools.add(tool("list_knowledge_objects", "列出知识对象",
            "列出知识库中的结构化知识对象：CARD 知识卡片（标准答案、SOP）、CLAIM 断言（可判真伪的结论）。"
                + "想浏览这类对象、或要拿到某个对象的编码时用；已经知道编码就用 get_knowledge_object 直接读"
                + "（它还能按编码读 EVIDENCE 证据原文，证据没有列表）。"
                + "例：objectType=\"CLAIM\"",
            schema(properties(
                prop("knowledgeBaseId", "integer", "知识库 ID；不知道就先调 list_knowledge_bases"),
                prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略"),
                enumProp("objectType", "对象类型，默认 CARD", "CARD", "CLAIM")),
                new ArrayList<String>()), true, false));
        tools.add(tool("get_knowledge_object", "读取知识对象",
            "按编码读取一个知识对象的详情与证据链，类型由 objectType 指定：CARD 知识卡片 / CLAIM 断言 / EVIDENCE 证据。"
                + "拿到引用里的编码后想看原文时用；不知道编码就先用 list_knowledge_objects 列出来。"
                + "例：objectType=\"CARD\", code=\"SOP-001\"",
            schema(properties(
                prop("knowledgeBaseId", "integer", "知识库 ID；不知道就先调 list_knowledge_bases"),
                prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略"),
                enumProp("objectType", "对象类型，默认 CARD", "CARD", "CLAIM", "EVIDENCE"),
                prop("code", "string", "对象编码；旧工具里的 evidenceCode 也接受")),
                Arrays.asList("code")), true, false));
        tools.add(tool("graph_neighbors", "图谱邻域",
            "读取某个实体或知识对象的图谱邻域（多跳关系）。"
                + "适合回答「A 影响了哪些服务」「X 依赖什么」这类关系型问题；"
                + "纯文本型问题用 knowledge_query 更合适。",
            schema(properties(prop("knowledgeBaseId", "integer", "知识库 ID；不知道就先调 list_knowledge_bases"),
                prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略"),
                prop("code", "string", "对象编码"), prop("depth", "integer", "跳数，默认 2"), prop("limit", "integer", "返回上限，默认 50")),
                Arrays.asList("code")), true, false));
        }
        if (isMemoryEnabled() && key != null && key.hasScope(ApiKey.SCOPE_MEMORY_READ)) {
            tools.add(tool("memory_recall", "召回记忆",
                "按当前 Agent 或已授权团队 / 项目 / 任务 / 工作流 / 会话作用域召回记忆。"
                    + "当用户提到「上次说过」「之前定的」「你记得吗」，或需要跨会话延续上下文时使用；"
                    + "问的是知识库里的资料则用 knowledge_query。",
                schema(properties(prop("query", "string", "待召回的关键词或问题"), prop("scopeType", "string", "AGENT（默认）/ TEAM / PROJECT / TASK / WORKFLOW / SESSION"),
                    prop("scopeCode", "string", "共享空间或会话编码；AGENT 身份由 API Key 确定"), prop("limit", "integer", "返回数量，默认 10，最多 50")),
                    Arrays.asList("query")), true, false));
        }
        if (isMemoryEnabled() && key != null && key.hasScope(ApiKey.SCOPE_MEMORY_WRITE)) {
            tools.add(tool("memory_capture", "保存记忆",
                "保存一条有来源、作用域和有效期的 Agent 记忆。"
                    + "当用户说「记住这个」「以后都按这个来」，或任务产出了值得延续的结论时使用；"
                    + "不要把知识库里已有的资料重复存进来。",
                schema(properties(prop("memoryLevel", "string", "L0 会话片段 / L1 原子记忆 / L2 场景记忆 / L3 Agent 画像"),
                    prop("scopeType", "string", "AGENT / TEAM / PROJECT / TASK / WORKFLOW / SESSION"), prop("scopeCode", "string", "共享空间或会话编码"),
                    prop("content", "string", "需要持久化的经验或上下文"), arrayProp("tags", "可选标签"),
                    prop("sourceType", "string", "TASK_RESULT / USER_CONFIRMED / WORKFLOW / MANUAL / OTHER"),
                    prop("sourceRef", "string", "来源任务、流程或对象编码"), prop("idempotencyKey", "string", "调用重试时保持不变，避免重复写入"),
                    prop("confidence", "number", "可信度 0-1"),
                    prop("importance", "integer", "重要度 1-5"), prop("expiresInDays", "integer", "有效期 1-3650 天")),
                    Arrays.asList("memoryLevel", "scopeType", "content")), false, false));
            tools.add(tool("memory_forget", "删除记忆",
                "删除当前 Agent 私有记忆或已授权命名空间中的共享记忆。这是不可撤销的删除，"
                    + "只在用户明确要求「忘掉 / 删掉这条记忆」时使用。",
                schema(properties(prop("memoryCode", "string", "记忆编码")), Arrays.asList("memoryCode")), false, true));
        }
        if (key != null && key.hasScope(ApiKey.SCOPE_INGEST)) {
            tools.add(tool("ingest_text", "写入知识",
                "把一段文本接入知识库，之后就能被检索和问答命中（需要 INGEST 权限）。"
                    + "当用户要求「把这段存进知识库 / 记到文档里」时使用；"
                    + "只是希望这次对话记住，用 memory_capture。",
                schema(properties(
                    prop("knowledgeBaseId", "integer", "知识库 ID；不知道就先调 list_knowledge_bases"),
                    prop("knowledgeBaseCode", "string", "知识库稳定编码；绑定式 API Key 可省略"),
                    prop("title", "string", "标题，作为来源名"),
                    prop("text", "string", "正文内容"),
                    prop("permissionTags", "string", "权限标签，逗号分隔")),
                    Arrays.asList("text")), false, false));
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
        if ("get_agent_identity".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_LIST);
            if (!args.isEmpty()) throw new IllegalArgumentException("身份工具不接受参数");
            value = VerifiedAgentIdentity.describe(key);
        } else if ("knowledge_query".equals(name)) {
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
        } else if ("list_knowledge_objects".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = listObjects(key, kb, objectType(args, false));
        } else if ("get_knowledge_object".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.objectDetail(kb, objectType(args, true), objectCode(args), facade.tagsFor(key, kb));
        } else if ("list_knowledge_cards".equals(name)) {
            // 以下五个是合并成 list/get_knowledge_objects 之前的旧工具名：工具清单里已经不列出，
            // 但硬编码过的调用方还在发这些名字，继续支持，免得一次改名把已经接好的客户端打挂。
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.cards(kb, facade.tagsFor(key, kb));
        } else if ("get_knowledge_card".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.objectDetail(kb, "CARD", text(args, "code"), facade.tagsFor(key, kb));
        } else if ("list_claims".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.claims(kb, facade.tagsFor(key, kb));
        } else if ("get_claim".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.objectDetail(kb, "CLAIM", text(args, "code"), facade.tagsFor(key, kb));
        } else if ("get_evidence".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.objectDetail(kb, "EVIDENCE", text(args, "evidenceCode"), facade.tagsFor(key, kb));
        } else if ("graph_neighbors".equals(name)) {
            accessService.assertScope(key, ApiKey.SCOPE_READ_OBJECT);
            Long kb = resolve(key, args);
            value = facade.graph(kb, text(args, "code"), integer(args, "depth"), integer(args, "limit"), facade.tagsFor(key, kb));
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
            value = facade.objectDetail(resolved, "CARD", code, facade.tagsFor(key, resolved));
        } else if ("claims".equalsIgnoreCase(type)) {
            value = facade.objectDetail(resolved, "CLAIM", code, facade.tagsFor(key, resolved));
        } else {
            value = facade.overview(resolved, facade.tagsFor(key, resolved));
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

    /**
     * 对象类型：只接受契约里的取值，默认 CARD。
     *
     * @param allowEvidence 是否允许 EVIDENCE——证据没有列表接口，所以只有读取允许
     */
    private String objectType(Map<String, Object> args, boolean allowEvidence) {
        String raw = text(args, "objectType");
        if (raw == null || raw.trim().isEmpty()) {
            return "CARD";
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("CARD".equals(value) || "CLAIM".equals(value)) {
            return value;
        }
        if (allowEvidence && "EVIDENCE".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("objectType 只能是 CARD / CLAIM" + (allowEvidence ? " / EVIDENCE" : ""));
    }

    /** 对象编码：旧工具用的 evidenceCode 也认，老调用方不用跟着改名。 */
    private String objectCode(Map<String, Object> args) {
        String code = text(args, "code");
        if (code == null || code.trim().isEmpty()) {
            code = text(args, "evidenceCode");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少对象编码 code");
        }
        return code.trim();
    }

    private Object listObjects(ApiKey key, Long kb, String type) {
        List<String> tags = facade.tagsFor(key, kb);
        return "CLAIM".equals(type) ? facade.claims(kb, tags) : facade.cards(kb, tags);
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

    /**
     * 工具声明。title 与 annotations 不是装饰：客户端常拿 title 做展示名、拿 readOnlyHint
     * 判断这个工具能不能自动执行，缺了它们有些平台会把工具排得很低，甚至不给自动调用。
     */
    private Map<String, Object> tool(String name, String title, String description, Map<String, Object> inputSchema,
        boolean readOnly, boolean destructive) {
        Map<String, Object> tool = new LinkedHashMap<String, Object>();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        Map<String, Object> annotations = new LinkedHashMap<String, Object>();
        annotations.put("readOnlyHint", Boolean.valueOf(readOnly));
        annotations.put("destructiveHint", Boolean.valueOf(destructive));
        // 只访问本平台知识库，不是开放互联网：声明出来，免得客户端按"会联网"处理
        annotations.put("openWorldHint", Boolean.FALSE);
        tool.put("annotations", annotations);
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

    /** 枚举参数：给模型的是有限选项而不是让它猜字符串，选择错误率明显更低。 */
    private Map<String, Object> enumProp(String name, String description, String... values) {
        Map<String, Object> prop = new LinkedHashMap<String, Object>();
        prop.put("type", "string");
        prop.put("description", description);
        prop.put("enum", Arrays.asList(values));
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
