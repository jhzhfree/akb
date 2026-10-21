package com.jh.knowledgeos.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.jh.knowledgeos.common.KbRetrievalException;
import com.jh.knowledgeos.common.ScoreNormalizer;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.KnowledgeBase;

/**
 * NovaWork 外部知识库检索协议（standard）的适配层。
 *
 * <p>职责只有一件：把本系统一次检索的结果翻译成协议要的形状。授权、配额、审计都不在这里——
 * 检索走 {@link ExternalKnowledgeFacade#retrieval}，与 {@code /query} 是同一道门，
 * 不存在"第三方协议走一条更宽松的路"。</p>
 *
 * <p>协议只认 {@code chunks[]}（且忽略未知字段），所以本系统生成的答案挂在 {@code answer}
 * 上返回：NovaWork 自己会忽略它，而我们自己的调用方能拿到。</p>
 */
@Service
public class KbRetrievalService {

    private final ExternalKnowledgeFacade facade;

    private final KnowledgeBaseService bases;

    public KbRetrievalService(ExternalKnowledgeFacade facade, KnowledgeBaseService bases) {
        this.facade = facade;
        this.bases = bases;
    }

    /**
     * 定位目标知识库：请求头优先，其次请求体；纯数字按 id，其余按 baseCode。
     *
     * <p>NovaWork 侧配置的是"外部知识库 id"原文，数字 id 传十进制文本，但也可能配成库的编码，
     * 所以两条路都支持。绑定过库的 Key 只能访问自己那个库——与 {@code /query} 同一条规则，
     * 这里不另开例外。</p>
     */
    public Long resolveKnowledgeBase(ApiKey key, String headerKbId, Object bodyKbId) {
        String raw = text(headerKbId);
        if (raw == null) {
            raw = text(bodyKbId);
        }
        Long bound = key == null ? null : key.getKnowledgeBaseId();
        if (raw == null) {
            // 协议把这个头列为必填，但绑过库的 Key 本来就只能访问那一个库，此时不传不算错。
            if (bound != null) {
                return bound;
            }
            throw KbRetrievalException.invalidArgument("缺少 X-Knowledge-Base-Id 请求头，也没有 knowledgeBaseId 参数");
        }
        // 指定了就得认这个库：配错 id 要按协议回 404 让调用方看见，而不是悄悄退回绑定库——
        // 那样对方拿到的是"能查通但查的是别的库"，比报错难排查得多。
        if (raw.matches("\\d+")) {
            Long id = Long.valueOf(raw);
            if (bases.get(id) == null) {
                throw KbRetrievalException.kbNotFound(raw);
            }
            return id;
        }
        for (KnowledgeBase base : bases.list()) {
            if (raw.equalsIgnoreCase(base.getBaseCode())) {
                return base.getId();
            }
        }
        throw KbRetrievalException.kbNotFound(raw);
    }

    /**
     * 执行一次检索并按协议返回。
     *
     * @param withAnswer 是否连答案一起给：false 时告诉引擎跳过生成，只做召回
     */
    public Map<String, Object> retrieve(ApiKey key, Long kb, String question, Integer topK, boolean withAnswer,
        boolean includeMemory) throws Exception {
        Map<String, Object> result = facade.retrieval(key, kb, question, topK, null,
            PlatformEventPublisher.SOURCE_REST, null, withAnswer, includeMemory);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("chunks", chunks(result.get("candidates"), topK));
        if (withAnswer) {
            out.put("answer", answer(result));
        }
        return out;
    }

    /**
     * 候选转协议片段：条数不超过 topK，按归一化分数降序。
     *
     * <p>{@code content} 用候选原文而不是 citations 的摘录——协议那一项的语义是"召回文本"，
     * 给摘录会让调用方拿到的片段比它预期短，且它无法知道还有多少被截掉了。</p>
     */
    private List<Map<String, Object>> chunks(Object candidates, Integer topK) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (!(candidates instanceof List<?>)) {
            return rows;
        }
        int limit = topK == null || topK < 1 ? 10 : Math.min(topK, 50);
        for (Object item : (List<?>)candidates) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> candidate = (Map<?, ?>)item;
            String content = firstText(candidate, "content", "title");
            if (content == null) {
                // 协议要求 content 非空：空内容的片段对调用方没有意义，宁可少给一条。
                continue;
            }
            Map<?, ?> nested = candidate.get("metadata") instanceof Map
                ? (Map<?, ?>)candidate.get("metadata") : new LinkedHashMap<String, Object>();
            Double rerank = number(candidate.get("rerankScore"));
            Double score = number(candidate.get("score"));
            Map<String, Object> chunk = new LinkedHashMap<String, Object>();
            // id 必须跨请求稳定：NovaWork 用它做多库融合去重，所以用证据码而不是行号。
            chunk.put("id", value(candidate, nested, "evidenceCode", "evidence_code", "contentUnitId",
                "content_unit_id", "id"));
            chunk.put("content", content);
            chunk.put("score", ScoreNormalizer.normalize(rerank, score));
            // 来源在 metadata 里（候选顶层没有 sourceName）：拿不到就退回标题——协议那一项是可选的，
            // 但空着调用方就无从溯源了。
            String source = value(candidate, nested, "sourceName", "source_name");
            chunk.put("source", source != null ? source : text(candidate.get("title")));
            chunk.put("metadata", metadata(candidate, nested, rerank, score));
            rows.add(chunk);
            if (rows.size() >= limit) {
                break;
            }
        }
        // 归一化是单调的，顺序本不会变；但重排分与压缩分混用时仍排一次，免得依赖上游顺序。
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                return Double.compare(number(right.get("score")), number(left.get("score")));
            }
        });
        return rows;
    }

    private Map<String, Object> metadata(Map<?, ?> candidate, Map<?, ?> nested, Double rerank, Double score) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        put(meta, "docName", text(candidate.get("title")));
        put(meta, "sourceUri", value(candidate, nested, "sourceUri", "source_uri"));
        Object page = candidate.get("pageNo") != null ? candidate.get("pageNo") : nested.get("page_no");
        if (page != null) {
            put(meta, "page", page);
        }
        put(meta, "objectType", value(candidate, nested, "objectType", "object_type"));
        put(meta, "contentUnitId", value(candidate, nested, "contentUnitId", "content_unit_id"));
        put(meta, "evidenceCode", value(candidate, nested, "evidenceCode", "evidence_code"));
        put(meta, "channel", text(candidate.get("channel")));
        // 归一值是怎么来的、原始分是多少：调用方要判断这个分数能不能拿来卡阈值。
        put(meta, "scoreOrigin", ScoreNormalizer.originOf(rerank, score));
        if (score != null) {
            put(meta, "rawScore", score);
        }
        return meta;
    }

    private Map<String, Object> answer(Map<String, Object> result) {
        String body = text(result.get("answer"));
        Map<String, Object> answer = new LinkedHashMap<String, Object>();
        // 没有答案正文是正常结果（门控拦下、库里没东西），不是错误——调用方据此决定要不要回退到只用 chunks。
        put(answer, "status", body == null || body.isEmpty() ? "empty" : "ok");
        put(answer, "queryId", text(result.get("queryId")));
        put(answer, "text", body == null ? "" : body);
        put(answer, "format", text(result.get("answerFormat")));
        put(answer, "confidence", number(result.get("confidence")));
        put(answer, "conclusionSource", text(result.get("conclusionSource")));
        answer.put("citations", citations(result.get("citations")));
        return answer;
    }

    /** 引用按原样给出：要核实答案出处时，调用方需要的是可回查原文的 evidenceCode 与摘录。 */
    private List<Map<String, Object>> citations(Object raw) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List<?>)) {
            return rows;
        }
        for (Object item : (List<?>)raw) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> citation = (Map<?, ?>)item;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            put(row, "index", citation.get("index"));
            put(row, "evidenceCode", text(citation.get("evidenceCode")));
            put(row, "title", text(citation.get("title")));
            put(row, "sourceName", text(citation.get("sourceName")));
            put(row, "excerpt", text(citation.get("excerpt")));
            put(row, "score", number(citation.get("score")));
            rows.add(row);
        }
        return rows;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String firstText(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            String value = text(source.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    /**
     * 同一个字段在候选顶层是驼峰、在 metadata 里是下划线（evidenceCode / evidence_code），
     * 两边都找一遍——调用方不该关心这个差别。
     */
    private static String value(Map<?, ?> candidate, Map<?, ?> nested, String... names) {
        for (String name : names) {
            String direct = text(candidate.get(name));
            if (direct != null) {
                return direct;
            }
            String fromMeta = text(nested.get(name));
            if (fromMeta != null) {
                return fromMeta;
            }
        }
        return null;
    }

    private static Double number(Object value) {
        return value instanceof Number ? Double.valueOf(((Number)value).doubleValue()) : null;
    }
}
