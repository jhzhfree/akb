package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.service.EmbedTokenService;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.ExternalKnowledgeFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handshake endpoints of the embeddable chat widget.
 *
 * <p>The widget never sees the API key: a page exchanges it once for a short-lived token that
 * is bound to this key, this knowledge base and this site, and then streams answers with that
 * token. Revoking the key invalidates every token minted from it, and a token stolen from one
 * site cannot be replayed from another.</p>
 */
@RestController
@RequestMapping("/api/v1/external/embed")
@Tag(name = "Embed Widget", description = "网站嵌入聊天组件：短期令牌签发与组件配置")
public class EmbedController {

    /**
     * Pipeline stages the widget renders as "what the server just did"; the keys are the ones
     * the AI service reports, the labels are what a reader sees.
     */
    private static final String[][] STAGES = {
        { "rewrite", "查询改写", "把口语化问题改写为可检索的表达" },
        { "route", "意图路由", "判定问题类型并选择检索通道" },
        { "recall", "多通道召回", "关键词、向量、卡片、断言、图谱并行召回" },
        { "acl_filter", "权限过滤", "按调用方权限标签过滤，先于排序生效" },
        { "fusion", "结果融合", "加权 RRF 融合各通道排名" },
        { "rerank", "重排序", "精排模型重新打分" },
        { "context", "上下文装配", "挑选进入提示词的证据片段" },
        { "llm", "答案生成", "大模型依据证据生成答案" },
        { "citation", "引用校验", "校验每条结论是否有证据支撑" }
    };

    private final ExternalAccessService accessService;

    private final EmbedTokenService embedTokens;

    private final ExternalAuditService auditService;

    private final ExternalKnowledgeFacade facade;

    private final KnowledgeProperties.External properties;

    public EmbedController(ExternalAccessService accessService, EmbedTokenService embedTokens, ExternalAuditService auditService,
        ExternalKnowledgeFacade facade, KnowledgeProperties properties) {
        this.accessService = accessService;
        this.embedTokens = embedTokens;
        this.auditService = auditService;
        this.facade = facade;
        this.properties = properties.external();
    }

    @PostMapping("/token")
    @Operation(summary = "用 API Key 换取嵌入组件令牌（可指定有效期，令牌绑定知识库与来源站点）")
    public ApiResponse<Object> issueToken(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId,
        @RequestParam(value = "origin", required = false) String origin,
        @RequestParam(value = "expiresInSeconds", required = false) Long expiresInSeconds,
        @RequestParam(value = "permanent", required = false) Boolean permanent) {
        return ApiResponse.ok(token(request, knowledgeBaseId, origin, expiresInSeconds, permanent));
    }

    @GetMapping("/token")
    @Operation(summary = "同上的 GET 形式，便于页面直接以 URL 方式获取")
    public ApiResponse<Object> issueTokenGet(HttpServletRequest request,
        @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId,
        @RequestParam(value = "origin", required = false) String origin,
        @RequestParam(value = "expiresInSeconds", required = false) Long expiresInSeconds,
        @RequestParam(value = "permanent", required = false) Boolean permanent) {
        return ApiResponse.ok(token(request, knowledgeBaseId, origin, expiresInSeconds, permanent));
    }

    @GetMapping("/config")
    @Operation(summary = "组件配置：知识库名称、可展示的检索阶段定义与参数上限")
    public ApiResponse<Object> config(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId) {
        ApiKey key = accessService.authenticateEmbed(request);
        Long kb = accessService.resolveKnowledgeBase(key, knowledgeBaseId);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("knowledgeBaseId", kb);
        data.put("knowledgeBaseName", knowledgeBaseName(key, kb));
        data.put("knowledgeBaseDefaultQuery", knowledgeBaseDefaultQuery(key, kb));
        data.put("stages", stages());
        data.put("answerFormats", Arrays.asList("default", "markdown", "text", "html"));
        data.put("maxTopK", 50);
        data.put("embedTokenDefaultHours", properties.getEmbedTokenHours());
        data.put("embedTokenMaxHours", properties.getEmbedTokenMaxHours());
        data.put("embedTokenPermanentEnabled", properties.isEmbedTokenPermanent());
        data.put("streamPath", "/api/v1/external/stream/query");
        data.put("sseEnabled", properties.isSseEnabled());
        return ApiResponse.ok(data);
    }

    private Map<String, Object> token(HttpServletRequest request, Long knowledgeBaseId, String origin, Long expiresInSeconds, Boolean permanent) {
        long start = System.currentTimeMillis();
        if (!properties.isEnabled()) {
            throw new IllegalStateException("对外接口服务未启用");
        }
        // Only an API key can mint a token: a token must never be able to extend itself.
        ApiKey key = accessService.authenticate(request);
        accessService.assertScope(key, ApiKey.SCOPE_READ);
        accessService.assertOrigin(key, request);
        Long kb = accessService.resolveKnowledgeBase(key, knowledgeBaseId);
        String site = origin == null || origin.trim().isEmpty() ? ExternalAccessService.originOf(request) : origin.trim();
        boolean never = Boolean.TRUE.equals(permanent) || (expiresInSeconds != null && expiresInSeconds <= 0L);
        if (never && !properties.isEmbedTokenPermanent()) {
            throw new IllegalStateException("当前部署已禁用长期有效的嵌入令牌");
        }
        EmbedTokenService.EmbedToken issued = embedTokens.issue(key, kb, site, never ? 0L : expiresInSeconds);
        // Minting is audited under the new token's own id, so the call log shows where a token came
        // from before it shows what it did.
        auditService.record(key, ApiCallAudit.CHANNEL_REST, "POST /embed/token", kb, 200, System.currentTimeMillis() - start,
            "签发嵌入令牌 " + issued.getDisplayId() + (issued.isPermanent() ? "（长期有效）" : ""), null, null, issued);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("displayId", issued.getDisplayId());
        data.put("jti", issued.getJti());
        data.put("token", issued.getRaw());
        data.put("permanent", issued.isPermanent());
        data.put("expiresAt", issued.isPermanent() ? null : issued.getExpiresAt());
        data.put("expiresInSeconds", issued.isPermanent() ? null : Math.max(0L, (issued.getExpiresAt() - System.currentTimeMillis()) / 1000L));
        data.put("knowledgeBaseId", kb);
        data.put("origin", site);
        data.put("scope", ApiKey.SCOPE_READ);
        return data;
    }

    private String knowledgeBaseName(ApiKey key, Long kb) {
        try {
            for (Map<String, Object> base : facade.bases(key.getKnowledgeBaseId())) {
                Object id = base.get("id");
                if (id != null && String.valueOf(id).equals(String.valueOf(kb))) {
                    return String.valueOf(base.get("name"));
                }
            }
        } catch (Exception ignored) {
            // A missing display name must not break the handshake.
        }
        return "";
    }

    /**
     * 知识库预置的示例问句：宿主页面没有指定 data-placeholder 时，组件拿它当输入框提示。
     */
    private String knowledgeBaseDefaultQuery(ApiKey key, Long kb) {
        try {
            for (Map<String, Object> base : facade.bases(key.getKnowledgeBaseId())) {
                Object id = base.get("id");
                if (id != null && String.valueOf(id).equals(String.valueOf(kb))) {
                    Object query = base.get("defaultQuery");
                    return query == null ? "" : String.valueOf(query);
                }
            }
        } catch (Exception ignored) {
            // A missing sample sentence must not break the handshake.
        }
        return "";
    }

    private List<Map<String, Object>> stages() {
        List<Map<String, Object>> items = new java.util.ArrayList<Map<String, Object>>();
        for (String[] stage : STAGES) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", stage[0]);
            item.put("label", stage[1]);
            item.put("description", stage[2]);
            items.add(item);
        }
        return items;
    }
}
