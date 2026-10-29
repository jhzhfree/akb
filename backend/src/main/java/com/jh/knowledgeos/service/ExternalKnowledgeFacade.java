package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.AnswerFormat;
import com.jh.knowledgeos.common.KnowledgeBaseVisibility;
import com.jh.knowledgeos.common.ResponseMode;
import com.jh.knowledgeos.common.ResponseReducer;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.KnowledgeCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External contract over the platform's own capabilities.
 *
 * <p>Everything here delegates to the same application services the console uses, so an
 * external caller can never reach behaviour the UI cannot reach, and vice versa.</p>
 */
@Service
public class ExternalKnowledgeFacade {

    private final KnowledgeService knowledgeService;

    private final KnowledgeBaseService knowledgeBaseService;

    private final ObjectMapper objectMapper;

    /** 检索授权：外部请求标签只能收窄服务端授权范围，不能扩大。 */
    private final RetrievalAuthorizationService authorization;

    private final AgentMemoryService memories;

    /** 注入答案的记忆条数上限：够覆盖长期偏好，又不至于把提示词撑满。 */
    private static final int MEMORY_CONTEXT_LIMIT = 8;

    private static final Logger log = LoggerFactory.getLogger(ExternalKnowledgeFacade.class);

    public ExternalKnowledgeFacade(KnowledgeService knowledgeService, KnowledgeBaseService knowledgeBaseService,
        ObjectMapper objectMapper, RetrievalAuthorizationService authorization, AgentMemoryService memories) {
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.memories = memories;
    }

    /**
     * Visible workspaces for the caller: bound key sees its own, otherwise the public ones.
     */
    public List<String> tagsFor(ApiKey key, Long kb) { return authorization.effectiveTags(key, kb, null, "REST"); }

    public List<Map<String, Object>> bases(Long boundKb) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (boundKb != null) {
            KnowledgeBase base = knowledgeBaseService.get(boundKb);
            if (base != null) {
                rows.add(toBaseRow(base));
            }
            return rows;
        }
        for (KnowledgeBase base : knowledgeBaseService.list()) {
            rows.add(toBaseRow(base));
        }
        return rows;
    }

    /** Applies principal grants before returning any workspace metadata to an external caller. */
    public List<Map<String, Object>> basesFor(ApiKey key, List<Long> grantedIds) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (grantedIds != null && !grantedIds.isEmpty()) {
            for (Long id : grantedIds) {
                KnowledgeBase base = knowledgeBaseService.get(id);
                if (base != null) {
                    rows.add(toBaseRow(base));
                }
            }
            return rows;
        }
        if (key != null && (key.isWorkloadCredential() || (key.getPrincipalId() != null && key.getKnowledgeBaseId() != null))) {
            return rows;
        }
        if (key != null && key.getKnowledgeBaseId() != null) {
            return bases(key.getKnowledgeBaseId());
        }
        for (KnowledgeBase base : knowledgeBaseService.list()) {
            if (base.visibility() == KnowledgeBaseVisibility.PUBLIC) {
                rows.add(toBaseRow(base));
            }
        }
        return rows;
    }

    /**
     * Query on behalf of an external credential.
     *
     * <p>The credential and the channel tag travel with the request so the RETRIEVAL_EXECUTED event
     * published downstream names its origin — otherwise an operator watching the stream could not
     * tell a console search from an MCP-driven one.</p>
     */
    public Map<String, Object> query(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source)
        throws Exception {
        return query(key, kb, question, topK, permissionTags, source, AnswerFormat.DEFAULT);
    }

    /**
     * 同上，带答案交付格式：一个把答案粘进工单的系统要纯文本，一个导出到文档的系统要 HTML，
     * 而只想要模型原文的系统什么都不用传。
     */
    public Map<String, Object> query(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        String answerFormat) throws Exception {
        return query(key, kb, question, topK, permissionTags, source, answerFormat, null);
    }

    /**
     * 同上，带最低分数：调用方用一个阈值决定"够格"的候选，而不是先取回一堆再自己筛。
     */
    public Map<String, Object> query(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        String answerFormat, Double minScore) throws Exception {
        return query(key, kb, question, topK, permissionTags, source, answerFormat, minScore, ResponseMode.FULL);
    }

    /**
     * 同上，带返回详略：{@code minimal} 只留答案与数量口径，{@code basic} 补回候选与进入
     * 上下文的候选，{@code full} 全部照给。
     *
     * <p>裁剪只在这一层做，且发生在授权判定与事件发布之后：检索链路看到的仍是完整结果，
     * 少给字段纯粹是"这次响应带多少"的交付决定。</p>
     */
    public Map<String, Object> query(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        String answerFormat, Double minScore, String responseMode) throws Exception {
        return query(key, kb, question, topK, permissionTags, source, answerFormat, minScore, responseMode, false);
    }

    /**
     * 同上，带记忆注入：把该凭证身份下的长期记忆并入生成上下文。
     *
     * <p>默认不开。记忆会改变答案的措辞与侧重，而调用方未必预期一次检索会带上历史——
     * 这个效果必须是要了才有。</p>
     */
    public Map<String, Object> query(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        String answerFormat, Double minScore, String responseMode, boolean includeMemory) throws Exception {
        // 标签不再是调用方可声明的输入：这里用服务端授权范围与请求标签求交集（请求只能收窄）。
        List<String> effectiveTags = authorization.effectiveTags(key, kb, permissionTags, source);
        Map<String, Object> payload = payload(key, kb, question, topK, effectiveTags, source, answerFormat, minScore);
        if (includeMemory) {
            attachMemory(payload, key, question);
        }
        Map<String, Object> result = knowledgeService.query(payload);
        // 回传实际生效的格式：调用方传了个认不出来的取值时，这里如实说 default，而不是让他以为生效了。
        result.put("answerFormat", AnswerFormat.normalize(result.get("answerFormat") == null ? null
            : String.valueOf(result.get("answerFormat"))));
        // 同理回传实际生效的 topK——但**不能**写回 `topK`：那是 AI 服务自己的字段，语义是
        // "本次生效的召回上限"（即请求值夹到 1~50 之后的结果，通常与本值相同）；实际给出的
        // 条数是 AI 服务返回的 `returned`（融合重排与 minScore 过滤之后的数量，可能大于也可能
        // 小于 topK）。用独立名字并存，两个口径都保留。
        result.put("effectiveTopK", effectiveTopK(topK));
        // 出口裁剪：full 原样返回，minimal / basic 少给字段。
        return ResponseReducer.query(result, responseMode);
    }

    /**
     * 第三方检索协议（NovaWork standard）的召回入口：与 {@link #query} 走同一套授权判定、
     * 同一份审计与事件，差别只在是否生成答案。
     *
     * <p>{@code withAnswer=false} 时给 AI 服务带上 {@code answerMode=none}，引擎跳过生成。
     * 这类调用方拿到片段后自己生成答案，它们有端到端超时预算，不该为一次用不上的模型调用
     * 付延迟——撞上超时在协议侧是"整库降级为空结果"，比少给答案严重得多。</p>
     *
     * <p>返回的是完整结果，不做裁剪：协议形状的转换（chunks / score 归一）由
     * {@code KbRetrievalService} 负责，这里保持"一次检索一个结果"的职责。</p>
     */
    public Map<String, Object> retrieval(ApiKey key, Long kb, String question, Integer topK,
        List<String> permissionTags, String source, Double minScore, boolean withAnswer, boolean includeMemory)
        throws Exception {
        List<String> effectiveTags = authorization.effectiveTags(key, kb, permissionTags, source);
        Map<String, Object> request = payload(key, kb, question, topK, effectiveTags, source,
            AnswerFormat.DEFAULT, minScore);
        if (!withAnswer) {
            request.put("answerMode", "none");
        }
        if (includeMemory) {
            attachMemory(request, key, question);
        }
        return knowledgeService.query(request);
    }

    /**
     * 无 source 的带凭证入口：控制器手里有 {@code ApiKey}，而记忆身份正是从它来的——
     * 走不带凭证的那组重载会丢掉身份，记忆注入就无从谈起。
     */
    public Map<String, Object> queryWithMemory(ApiKey key, Long kb, String question, Integer topK,
        List<String> permissionTags, String answerFormat, Double minScore, String responseMode) throws Exception {
        return query(key, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST,
            answerFormat, minScore, responseMode, true);
    }

    /**
     * 把该凭证身份下的长期记忆并入生成上下文。
     *
     * <p>记忆只进 {@code memoryContext}（AI 服务在装配提示词时并入），不参与召回：它是"这个
     * 调用方以前留下过什么"，不是知识库里的证据。拿它去检索会把召回带偏，也会让证据链出现
     * 库里并不存在的来源。</p>
     *
     * <p>失败就当没有记忆。记忆是增强而不是主链路：一次记忆读取出错不该让整次问答失败，
     * 调用方拿到的是"没有记忆的答案"，而不是一个 500。</p>
     */
    private void attachMemory(Map<String, Object> request, ApiKey key, String question) {
        String text = memoryContext(key, question);
        if (!text.isEmpty()) {
            request.put("memoryContext", text);
        }
    }

    /**
     * 该凭证身份下的记忆，渲染成可直接并入生成上下文的文本；拿不到就是空串。
     *
     * <p>失败不抛：记忆是增强而不是主链路，一次记忆读取出错不该让整次问答失败，调用方拿到
     * 的是"没有记忆的答案"，而不是一个 500。</p>
     */
    public String memoryContext(ApiKey key, String question) {
        if (key == null) {
            return "";
        }
        try {
            List<Map<String, Object>> rows = memories.recallByIdentity(memories.namespace(key),
                memories.agentCode(key), question, MEMORY_CONTEXT_LIMIT);
            return memories.renderContext(rows);
        } catch (RuntimeException e) {
            log.warn("记忆注入失败，本次问答按无记忆处理：{}", e.getMessage());
            return "";
        }
    }

    /**
     * Streaming counterpart of {@link #query}: stages and answer chunks are handed to the
     * listener as the pipeline produces them.
     *
     * <p>The credential is put on the payload exactly as it is for a blocking call, so a streamed
     * answer is attributed to the same key, and the ACL tags resolved for it are the same ones
     * the engine filters with — there is no second, looser path for streaming.</p>
     */
    public void streamQuery(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        String answerFormat, Double minScore, KnowledgeQueryService.QueryStreamListener listener) throws Exception {
        streamQuery(key, kb, question, topK, permissionTags, source, answerFormat, minScore, null, listener);
    }

    /**
     * 同上，带已渲染好的记忆上下文。
     *
     * <p>流式链路的记忆在入口处取一次再传进来，而不是在这里召回：一次问答只该有一次记忆
     * 读取，而流式与降级（{@code replay}）是同一问答的两条路，各读一次就会出现
     * "降级前后答案依据不同"。</p>
     */
    public void streamQuery(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        String answerFormat, Double minScore, String memoryContext, KnowledgeQueryService.QueryStreamListener listener)
        throws Exception {
        // 流式与阻塞调用共用同一份授权判定：不存在「流式走一条更宽松的路径」。
        List<String> effectiveTags = authorization.effectiveTags(key, kb, permissionTags, source);
        Map<String, Object> payload = payload(key, kb, question, topK, effectiveTags, source, answerFormat, minScore);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            payload.put("memoryContext", memoryContext);
        }
        knowledgeService.streamQuery(payload, listener);
    }

    /**
     * 带已渲染记忆上下文的阻塞式问答：流式降级（{@code replay}）走这条路，
     * 用的必须是入口处取到的同一份记忆——重新召回一次就可能给出不同的依据。
     */
    public Map<String, Object> queryWithContext(ApiKey key, Long kb, String question, Integer topK,
        List<String> permissionTags, String source, String answerFormat, Double minScore, String memoryContext)
        throws Exception {
        List<String> effectiveTags = authorization.effectiveTags(key, kb, permissionTags, source);
        Map<String, Object> payload = payload(key, kb, question, topK, effectiveTags, source, answerFormat, minScore);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            payload.put("memoryContext", memoryContext);
        }
        return knowledgeService.query(payload);
    }

    /**
     * 实际生效的提问内容：留空时回退到知识库建库时配的「默认问题」。
     *
     * <p>那句问句是建库时专门配的示例，控制台里看得见；对外接口若强制必填，调用方就只能
     * 自己想一句，同一份配置在两个入口表现不一致。两者都没有才算参数错误，且错误要说清
     * 缺的是哪一个，而不是笼统的一句"参数不合法"。</p>
     */
    private String resolveQuestion(Long kb, String question) {
        String text = question == null ? "" : question.trim();
        if (!text.isEmpty()) {
            return text;
        }
        KnowledgeBase base = kb == null ? null : knowledgeBaseService.get(kb);
        String fallback = base == null ? null : base.getDefaultQuery();
        text = fallback == null ? "" : fallback.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("缺少提问内容，且该知识库未配置默认问题");
        }
        return text;
    }

    private Map<String, Object> payload(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags,
        String source, String answerFormat) {
        return payload(key, kb, question, topK, permissionTags, source, answerFormat, null);
    }

    /**
     * 实际生效的 topK：请求里的取值会被夹到 1~50，不传或小于 1 取默认 10。
     *
     * <p>夹取只在这一处做，且夹完的值要回传给调用方（见 {@link #query}）——否则
     * "我要 200 条、回来 50 条"看起来像是库里只有 50 条，真实原因却是上限。</p>
     */
    public static int effectiveTopK(Integer topK) {
        return topK == null || topK < 1 ? 10 : Math.min(topK, 50);
    }

    private Map<String, Object> payload(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags,
        String source, String answerFormat, Double minScore) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("query", resolveQuestion(kb, question));
        payload.put("topK", effectiveTopK(topK));
        payload.put("source", source == null ? PlatformEventPublisher.SOURCE_REST : source);
        payload.put("answerFormat", AnswerFormat.normalize(answerFormat));
        // 只在给了阈值时下发：不传时 AI 服务侧是 None，与以前的行为逐字一致。
        if (minScore != null) {
            payload.put("minScore", minScore);
        }
        if (key != null) {
            payload.put("externalKeyCode", key.getKeyCode());
            payload.put("externalKeyName", key.getName());
        }
        if (permissionTags != null && !permissionTags.isEmpty()) {
            payload.put("permissionTags", permissionTags);
        }
        return payload;
    }

    /**
     * Legacy signature kept for internal callers that have no credential to report.
     */
    public Map<String, Object> query(Long kb, String question, Integer topK, List<String> permissionTags) throws Exception {
        return query(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST, AnswerFormat.DEFAULT);
    }

    /**
     * 同上，带答案交付格式：不传即默认格式。
     */
    public Map<String, Object> query(Long kb, String question, Integer topK, List<String> permissionTags, String answerFormat)
        throws Exception {
        return query(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST, answerFormat, null);
    }

    public Map<String, Object> query(Long kb, String question, Integer topK, List<String> permissionTags, String answerFormat,
        Double minScore) throws Exception {
        return query(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST, answerFormat, minScore);
    }

    /**
     * 同上，带返回详略（外部 REST 接口用这条：它手上没有凭证对象）。
     */
    public Map<String, Object> query(Long kb, String question, Integer topK, List<String> permissionTags, String answerFormat,
        Double minScore, String responseMode) throws Exception {
        return query(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST, answerFormat, minScore,
            responseMode);
    }

    /**
     * Retrieval-only surface: the same pipeline as query minus answer generation detail, so
     * systems that already own their own prompt can assemble context themselves.
     */
    public Map<String, Object> search(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source)
        throws Exception {
        return search(key, kb, question, topK, permissionTags, source, null);
    }

    /**
     * 同上，带最低分数：阈值在服务端生效，返回的 {@code returned} 是过滤后真正给出的条数。
     */
    public Map<String, Object> search(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        Double minScore) throws Exception {
        return search(key, kb, question, topK, permissionTags, source, minScore, ResponseMode.FULL);
    }

    /**
     * 同上，带返回详略：{@code minimal} 只给证据，{@code basic} 额外给候选。
     *
     * <p>组装用的是完整结果（内部那条 {@code query} 不裁），裁完再交给调用方——
     * 否则 {@code evidence} 会拿不到它需要的 {@code citations}。</p>
     */
    public Map<String, Object> search(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        Double minScore, String responseMode) throws Exception {
        // 这里要的是完整结果：裁完再组装的话 citations 已经不在了。
        Map<String, Object> result = query(key, kb, question, topK, permissionTags, source, AnswerFormat.DEFAULT, minScore);
        Map<String, Object> search = new LinkedHashMap<String, Object>();
        search.put("queryId", result.get("queryId"));
        search.put("knowledgeBaseId", kb);
        search.put("question", question);
        // `topK` 沿用 AI 服务的语义（本次生效的召回上限），夹取后的请求值另放在 effectiveTopK。
        search.put("topK", result.get("topK"));
        search.put("effectiveTopK", result.get("effectiveTopK"));
        search.put("returned", result.get("returned"));
        search.put("minScore", result.get("minScore"));
        search.put("strategies", result.get("strategies"));
        search.put("router", result.get("router"));
        search.put("rewrite", result.get("rewrite"));
        search.put("recall", result.get("recall"));
        search.put("reranker", result.get("reranker"));
        search.put("candidates", result.get("candidates"));
        search.put("citations", result.get("citations"));
        return ResponseReducer.search(search, responseMode);
    }

    /**
     * Legacy signature kept for internal callers that have no credential to report.
     */
    public Map<String, Object> search(Long kb, String question, Integer topK, List<String> permissionTags) throws Exception {
        return search(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST, null);
    }

    /**
     * Legacy signature with a score floor: same as above but only candidates at or above
     * `minScore` come back.
     */
    public Map<String, Object> search(Long kb, String question, Integer topK, List<String> permissionTags, Double minScore)
        throws Exception {
        return search(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST, minScore);
    }

    /**
     * 同上，带返回详略（外部 REST 接口用这条）。
     */
    public Map<String, Object> search(Long kb, String question, Integer topK, List<String> permissionTags, Double minScore,
        String responseMode) throws Exception {
        return search(null, kb, question, topK, permissionTags, PlatformEventPublisher.SOURCE_REST, minScore, responseMode);
    }

    /**
     * Cards for external callers, each carrying a plain `code`.
     *
     * <p>The list endpoint and the detail endpoint have to agree on the identifier: a caller that
     * lists cards and then wants one of them should be able to take the field it just received and
     * put it into `/cards/{code}`. Internally the object is addressed by `knowledgeCode`, and
     * leaving only that in the payload means every consumer has to guess which of the three
     * spellings applies to which object type — which is exactly how an integration ends up
     * calling `/cards/` with an empty code and getting the list back instead of a card.</p>
     */
    public List<Map<String, Object>> cards(Long kb, List<String> permissionTags) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (KnowledgeCard card : knowledgeService.cards(kb, tags(permissionTags))) {
            Map<String, Object> row = toRow(card);
            row.put("code", card.getKnowledgeCode());
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> claims(Long kb, List<String> permissionTags) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Claim claim : knowledgeService.claims(kb, tags(permissionTags))) {
            Map<String, Object> row = toRow(claim);
            row.put("code", claim.getClaimCode());
            rows.add(row);
        }
        return rows;
    }

    /**
     * One object, with the evidence rows it rests on carrying a `code` of their own for the same
     * reason the lists above do: the next call is `/evidence/{code}`.
     */
    @SuppressWarnings("unchecked")
    public Object objectDetail(Long kb, String type, String code, List<String> permissionTags) {
        Object detail = knowledgeService.knowledgeDetail(kb, type.toUpperCase(), code, tags(permissionTags));
        if (detail instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) detail;
            Object evidence = map.get("evidence");
            if (evidence instanceof List) {
                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                for (Object item : (List<Object>) evidence) {
                    Map<String, Object> row = toRow(item);
                    if (row.get("code") == null) {
                        row.put("code", row.get("evidenceCode"));
                    }
                    rows.add(row);
                }
                map.put("evidence", rows);
            }
        }
        return detail;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toRow(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    public Object graph(Long kb, String code, Integer depth, Integer limit, List<String> permissionTags) {
        return knowledgeService.graph(kb, code, tags(permissionTags), depth, limit);
    }

    public Object trace(String queryId) {
        return knowledgeService.trace(queryId);
    }

    public Object overview(Long kb, List<String> permissionTags) {
        return knowledgeService.overview(kb, tags(permissionTags));
    }

    /**
     * Ingests a text payload by presenting it to the console's own upload pipeline.
     */
    public Map<String, Object> ingestText(Long kb, String title, String text, String permissionTags) throws Exception {
        String name = (title == null || title.trim().isEmpty() ? "external-ingest" : title.trim());
        if (!name.toLowerCase().endsWith(".txt") && !name.toLowerCase().endsWith(".md")) {
            name = name + ".txt";
        }
        MultipartFile file = new BytesMultipartFile(name, text == null ? "" : text);
        return knowledgeService.upload(kb, file, null, permissionTags);
    }

    private List<String> tags(List<String> permissionTags) {
        return permissionTags == null ? new ArrayList<String>() : permissionTags;
    }

    private Map<String, Object> toBaseRow(KnowledgeBase base) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", base.getId());
        row.put("baseCode", base.getBaseCode());
        row.put("name", base.getName());
        row.put("description", base.getDescription());
        row.put("status", base.getStatus());
        row.put("visibility", base.getVisibility());
        // 示例问句随库一起给出去：调用方（对外 API、MCP、嵌入挂件）读到的是同一句提示，
        // 不必各自维护一份“这个库该怎么问”的文案。
        row.put("defaultQuery", base.getDefaultQuery());
        row.put("createdByName", base.getCreatedByName());
        return row;
    }

    /**
     * Minimal in-memory upload so text ingestion reuses the console pipeline unchanged.
     */
    private static class BytesMultipartFile implements MultipartFile {

        private final String name;

        private final byte[] content;

        BytesMultipartFile(String name, String text) {
            this.name = name;
            this.content = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "text/plain";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
