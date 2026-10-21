package com.jh.knowledgeos.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link ResponseMode} 裁剪外部接口的返回。
 *
 * <p>裁剪只发生在出口：检索链路、授权判定、事件与审计看到的永远是完整结果，
 * 少给字段纯粹是"这次响应带多少"的交付决定，不影响任何内部口径。</p>
 *
 * <p>{@code full} 直接返回原对象、不做任何写入——默认值必须逐字等于改造前，
 * 否则老调用方会看到一个多出来的字段。</p>
 */
public final class ResponseReducer {

    private ResponseReducer() {
    }

    /**
     * 裁剪 {@code POST /query} 的结果。
     *
     * <ul>
     *   <li>{@code minimal}：{@code queryId / question / topK / answer}</li>
     *   <li>{@code basic}：上面这些 + {@code citations}</li>
     *   <li>{@code full}：原样返回</li>
     * </ul>
     */
    public static Map<String, Object> query(Map<String, Object> full, String mode) {
        String normalized = ResponseMode.normalize(mode);
        if (ResponseMode.FULL.equals(normalized)) {
            return full;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        copy(out, full, "queryId");
        copy(out, full, "question");
        copy(out, full, "topK");
        copy(out, full, "answer");
        if (ResponseMode.BASIC.equals(normalized) && full.containsKey("citations")) {
            // 基本档只补引用：调用方要核实答案出处时，需要的是可回查原文的 evidenceCode 与摘录。
            out.put("citations", withNormalizedScores(full.get("citations")));
        }
        return out;
    }

    /**
     * 裁剪 {@code POST /search} 的结果。
     *
     * <ul>
     *   <li>{@code minimal}：{@code queryId / question / topK / evidence[]}</li>
     *   <li>{@code basic}：上面这些 + {@code citations}</li>
     *   <li>{@code full}：原样返回</li>
     * </ul>
     */
    public static Map<String, Object> search(Map<String, Object> full, String mode) {
        String normalized = ResponseMode.normalize(mode);
        if (ResponseMode.FULL.equals(normalized)) {
            return full;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        copy(out, full, "queryId");
        copy(out, full, "question");
        copy(out, full, "topK");
        // search 没有答案，调用方要的是"用哪些证据回答"：给精简后的证据而不是原始 citations。
        out.put("evidence", evidence(full.get("citations")));
        if (ResponseMode.BASIC.equals(normalized) && full.containsKey("citations")) {
            // 基本档给完整引用（含定位与审计字段），而不是候选原文：这里要核实的是出处，不是召回池。
            out.put("citations", withNormalizedScores(full.get("citations")));
        }
        return out;
    }

    /**
     * 证据清单：从 {@code citations} 里只留"展示一条引用"必需的字段。
     *
     * <p>{@code excerpt} 是被引用原文摘录、{@code evidenceCode} 可回查权威原文，
     * 这两个是调用方真正会用到的；其余定位与审计字段在 {@code full} 模式下照给。</p>
     */
    static List<Map<String, Object>> evidence(Object citations) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (!(citations instanceof List<?>)) {
            return rows;
        }
        for (Object item : (List<?>)citations) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> source = (Map<?, ?>)item;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            copy(row, source, "index");
            copy(row, source, "title");
            copy(row, source, "excerpt");
            copy(row, source, "sourceName");
            copy(row, source, "sourceUri");
            copy(row, source, "objectType");
            copy(row, source, "evidenceCode");
            copy(row, source, "score");
            // 原始分可能是重排分（0~1），也可能是没有上界的融合分，调用方没法直接卡阈值。
            row.put("normalizedScore", ScoreNormalizer.normalize(number(source.get("rerankScore")),
                number(source.get("score"))));
            row.put("scoreOrigin", ScoreNormalizer.originOf(number(source.get("rerankScore")),
                number(source.get("score"))));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 给候选或引用补上归一化分数。
     *
     * <p>{@code score} 有两种来源：启用重排时是 0~1 的重排分，没启用时是没有上界的融合分
     * （实测十几）。调用方想按阈值过滤，得先知道它落在哪个区间——所以额外给一个恒定在
     * [0,1] 的 {@code normalizedScore}，并用 {@code scoreOrigin} 说明它是怎么来的。
     * 原字段不动，老调用方不受影响。</p>
     *
     * <p>只用于裁剪档：{@code full} 必须逐字等于改造前，多一个字段都不行。</p>
     */
    static List<Map<String, Object>> withNormalizedScores(Object items) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (!(items instanceof List<?>)) {
            return rows;
        }
        for (Object item : (List<?>)items) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> source = (Map<?, ?>)item;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                row.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            Double rerank = number(source.get("rerankScore"));
            Double score = number(source.get("score"));
            row.put("normalizedScore", Double.valueOf(ScoreNormalizer.normalize(rerank, score)));
            row.put("scoreOrigin", ScoreNormalizer.originOf(rerank, score));
            rows.add(row);
        }
        return rows;
    }

    private static Double number(Object value) {
        return value instanceof Number ? Double.valueOf(((Number)value).doubleValue()) : null;
    }

    private static void copy(Map<String, Object> target, Map<?, ?> source, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
