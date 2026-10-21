package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.AnswerFormat;
import com.jh.knowledgeos.common.ResponseMode;
import com.jh.knowledgeos.common.ResponseReducer;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.KnowledgeCard;
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

    public ExternalKnowledgeFacade(KnowledgeService knowledgeService, KnowledgeBaseService knowledgeBaseService,
        ObjectMapper objectMapper, RetrievalAuthorizationService authorization) {
        this.knowledgeService = knowledgeService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
    }

    /**
     * Visible workspaces for the caller: bound key sees its own, otherwise the public ones.
     */
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
        // 标签不再是调用方可声明的输入：这里用服务端授权范围与请求标签求交集（请求只能收窄）。
        List<String> effectiveTags = authorization.effectiveTags(key, permissionTags, source);
        Map<String, Object> payload = payload(key, kb, question, topK, effectiveTags, source, answerFormat, minScore);
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
     * Streaming counterpart of {@link #query}: stages and answer chunks are handed to the
     * listener as the pipeline produces them.
     *
     * <p>The credential is put on the payload exactly as it is for a blocking call, so a streamed
     * answer is attributed to the same key, and the ACL tags resolved for it are the same ones
     * the engine filters with — there is no second, looser path for streaming.</p>
     */
    public void streamQuery(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags, String source,
        String answerFormat, Double minScore, KnowledgeQueryService.QueryStreamListener listener) throws Exception {
        // 流式与阻塞调用共用同一份授权判定：不存在「流式走一条更宽松的路径」。
        List<String> effectiveTags = authorization.effectiveTags(key, permissionTags, source);
        knowledgeService.streamQuery(payload(key, kb, question, topK, effectiveTags, source, answerFormat, minScore), listener);
    }

    private static Map<String, Object> payload(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags,
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

    private static Map<String, Object> payload(ApiKey key, Long kb, String question, Integer topK, List<String> permissionTags,
        String source, String answerFormat, Double minScore) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("query", question);
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
