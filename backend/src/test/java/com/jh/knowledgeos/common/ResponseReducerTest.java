package com.jh.knowledgeos.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 出口裁剪的契约测试：每条断言都是"调用方这次能看到哪些字段"。
 */
class ResponseReducerTest {

    // ------------------------------------------------------------------ /query

    /** 极简档只有问题与答案：模式回显、上下文、引用、候选都不给。 */
    @Test
    void queryMinimalKeepsOnlyAnswer() {
        Map<String, Object> full = fullQuery();
        Map<String, Object> reduced = ResponseReducer.query(full, ResponseMode.MINIMAL);
        assertEquals(set("queryId", "question", "topK", "answer"), reduced.keySet());
        assertEquals("答案正文", reduced.get("answer"));
        assertFalse(reduced.containsKey("responseMode"));
        assertFalse(reduced.containsKey("selectedContext"));
        assertFalse(reduced.containsKey("returned"));
    }

    @Test
    void queryBasicAddsCitations() {
        Map<String, Object> full = fullQuery();
        Map<String, Object> reduced = ResponseReducer.query(full, ResponseMode.BASIC);
        assertEquals(set("queryId", "question", "topK", "answer", "citations"), reduced.keySet());
        assertFalse(reduced.containsKey("candidates"));
        // 裁剪档的引用带归一化分数：原始分可能是重排分也可能是融合分，调用方没法直接卡阈值。
        List<?> rows = (List<?>)reduced.get("citations");
        assertEquals(1, rows.size());
        Map<?, ?> first = (Map<?, ?>)rows.get(0);
        assertTrue(first.containsKey("normalizedScore"));
        assertTrue(first.containsKey("scoreOrigin"));
    }

    /**
     * full 必须逐字等于改造前：不新增字段，也不返回副本——否则老调用方会看到
     * 一个多出来的 responseMode，或者发现拿到的不是同一个对象。
     */
    @Test
    void queryFullReturnsTheOriginalUntouched() {
        Map<String, Object> full = fullQuery();
        assertSame(full, ResponseReducer.query(full, ResponseMode.FULL));
        assertFalse(full.containsKey("responseMode"));
    }

    @Test
    void queryUnknownModeFallsBackToFullInsteadOfFailing() {
        Map<String, Object> full = fullQuery();
        assertSame(full, ResponseReducer.query(full, "MINIMAL!!"));
        assertSame(full, ResponseReducer.query(full, null));
        assertSame(full, ResponseReducer.query(full, ""));
    }

    // ------------------------------------------------------------------ /search

    @Test
    void searchMinimalKeepsEvidenceOnly() {
        Map<String, Object> reduced = ResponseReducer.search(fullSearch(), ResponseMode.MINIMAL);
        assertEquals(set("queryId", "question", "topK", "evidence"), reduced.keySet());
        assertFalse(reduced.containsKey("candidates"));
        assertFalse(reduced.containsKey("citations"));
    }

    @Test
    void searchBasicAddsCitations() {
        Map<String, Object> full = fullSearch();
        Map<String, Object> reduced = ResponseReducer.search(full, ResponseMode.BASIC);
        assertEquals(set("queryId", "question", "topK", "evidence", "citations"), reduced.keySet());
        List<?> rows = (List<?>)reduced.get("citations");
        assertEquals(1, rows.size());
        assertTrue(((Map<?, ?>)rows.get(0)).containsKey("normalizedScore"));
        assertFalse(reduced.containsKey("candidates"));
    }

    @Test
    void searchFullKeepsCitationsAndAllDetail() {
        Map<String, Object> full = fullSearch();
        assertSame(full, ResponseReducer.search(full, ResponseMode.FULL));
        assertTrue(full.containsKey("citations"));
        assertTrue(full.containsKey("recall"));
    }

    // ------------------------------------------------------------------ evidence

    /**
     * 证据项只留"展示一条引用"必需的字段，定位与审计字段留给 full。
     *
     * <p>唯独多带归一化分数：这一档已经把原始分给出来了，而原始分可能是融合分（没有上界），
     * 调用方拿着它没法卡阈值。</p>
     */
    @Test
    void evidenceKeepsOnlyDisplayFields() {
        List<Map<String, Object>> rows = ResponseReducer.evidence(fullSearch().get("citations"));
        assertEquals(1, rows.size());
        assertEquals(set("index", "title", "excerpt", "sourceName", "sourceUri", "objectType",
            "evidenceCode", "score", "normalizedScore", "scoreOrigin"), rows.get(0).keySet());
        assertEquals(1, rows.get(0).get("index"));
        assertEquals("被引用原文摘录", rows.get(0).get("excerpt"));
        assertEquals("EVD_1", rows.get(0).get("evidenceCode"));
        // 样例里没有 rerankScore，所以走压缩路径
        assertEquals(ScoreNormalizer.ORIGIN_COMPRESSED, rows.get(0).get("scoreOrigin"));
    }

    @Test
    void evidenceToleratesMissingOrMalformedCitations() {
        assertTrue(ResponseReducer.evidence(null).isEmpty());
        assertTrue(ResponseReducer.evidence("not a list").isEmpty());
        // 数组里混入非对象元素不能让整个接口 500
        assertEquals(1, ResponseReducer.evidence(Arrays.asList("garbage", citation())).size());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> fullQuery() {
        Map<String, Object> query = new LinkedHashMap<String, Object>();
        query.put("queryId", "Q_1");
        query.put("question", "星河项目的交付范围？");
        query.put("answer", "答案正文");
        query.put("topK", 3);
        query.put("returned", 6);
        query.put("candidates", Arrays.asList(candidate()));
        query.put("selectedContext", Arrays.asList(candidate()));
        query.put("citations", Arrays.asList(citation()));
        query.put("recall", new LinkedHashMap<String, Object>());
        query.put("fusion", new LinkedHashMap<String, Object>());
        query.put("trace", new LinkedHashMap<String, Object>());
        return query;
    }

    private static Map<String, Object> fullSearch() {
        Map<String, Object> search = new LinkedHashMap<String, Object>();
        search.put("queryId", "Q_1");
        search.put("knowledgeBaseId", 1L);
        search.put("question", "星河项目的交付范围？");
        search.put("topK", 3);
        search.put("effectiveTopK", 3);
        search.put("returned", 6);
        search.put("strategies", Arrays.asList("bm25", "vector"));
        search.put("recall", new LinkedHashMap<String, Object>());
        search.put("candidates", Arrays.asList(candidate()));
        search.put("citations", Arrays.asList(citation()));
        return search;
    }

    private static Map<String, Object> candidate() {
        Map<String, Object> candidate = new LinkedHashMap<String, Object>();
        candidate.put("id", "605");
        candidate.put("title", "一、适用范围与核心内容");
        candidate.put("content", "原文");
        candidate.put("score", 12.17);
        return candidate;
    }

    private static Map<String, Object> citation() {
        Map<String, Object> citation = new LinkedHashMap<String, Object>();
        citation.put("index", 1);
        citation.put("id", "605");
        citation.put("title", "一、适用范围与核心内容");
        citation.put("excerpt", "被引用原文摘录");
        citation.put("sourceName", "02_项目星河交付说明_V1.0.docx");
        citation.put("sourceUri", null);
        citation.put("objectType", "CONTENT_UNIT");
        citation.put("evidenceCode", "EVD_1");
        citation.put("score", 12.17);
        // 以下字段在 minimal / basic 里不给
        citation.put("evidenceHash", "hash");
        citation.put("generationId", "vdata3");
        citation.put("contentUnitId", 605);
        return citation;
    }

    private static Set<String> set(String... keys) {
        return new LinkedHashSet<String>(Arrays.asList(keys));
    }
}
