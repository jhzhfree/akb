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
     *   <li>{@code minimal}：{@code queryId / question / topK / returned / answer}</li>
     *   <li>{@code basic}：上面这些 + {@code candidates} + {@code selectedContext}</li>
     *   <li>{@code full}：原样返回</li>
     * </ul>
     */
    public static Map<String, Object> query(Map<String, Object> full, String mode) {
        String normalized = ResponseMode.normalize(mode);
        if (ResponseMode.FULL.equals(normalized)) {
            return full;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("responseMode", normalized);
        copy(out, full, "queryId");
        copy(out, full, "question");
        copy(out, full, "topK");
        copy(out, full, "returned");
        copy(out, full, "answer");
        if (ResponseMode.BASIC.equals(normalized)) {
            copy(out, full, "candidates");
            copy(out, full, "selectedContext");
        }
        return out;
    }

    /**
     * 裁剪 {@code POST /search} 的结果。
     *
     * <ul>
     *   <li>{@code minimal}：{@code queryId / question / topK / evidence[]}</li>
     *   <li>{@code basic}：上面这些 + {@code candidates}</li>
     *   <li>{@code full}：原样返回</li>
     * </ul>
     */
    public static Map<String, Object> search(Map<String, Object> full, String mode) {
        String normalized = ResponseMode.normalize(mode);
        if (ResponseMode.FULL.equals(normalized)) {
            return full;
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("responseMode", normalized);
        copy(out, full, "queryId");
        copy(out, full, "question");
        copy(out, full, "topK");
        // search 没有答案，调用方要的是"用哪些证据回答"：给精简后的证据而不是原始 citations。
        out.put("evidence", evidence(full.get("citations")));
        if (ResponseMode.BASIC.equals(normalized)) {
            copy(out, full, "candidates");
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
            rows.add(row);
        }
        return rows;
    }

    private static void copy(Map<String, Object> target, Map<?, ?> source, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
