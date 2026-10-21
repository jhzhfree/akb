package com.jh.knowledgeos.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 分数归一化的边界：协议要求 {@code score ∈ [0,1]} 且跨请求可比较，这两条一旦破了，
 * 调用方那边的阈值过滤与多库融合就会静默失效——所以边界值得单独钉住。
 */
class ScoreNormalizerTest {

    /** 有重排分就用重排分：它本来就在 0~1，换算反而失真。 */
    @Test
    void rerankScoreIsUsedAsIs() {
        assertEquals(0.73, ScoreNormalizer.normalize(Double.valueOf(0.73), Double.valueOf(12.17)), 1e-9);
        assertEquals(ScoreNormalizer.ORIGIN_RERANK, ScoreNormalizer.originOf(Double.valueOf(0.73), Double.valueOf(12.17)));
    }

    /** 没有重排分时压缩融合分：十几分的融合分落在 0.7 上下，与重排分量级相当。 */
    @Test
    void fusionScoreIsCompressedIntoUnitRange() {
        double normalized = ScoreNormalizer.normalize(null, Double.valueOf(12.17));
        assertTrue(normalized > 0.0 && normalized < 1.0, "压缩后必须落在 (0,1)，实际 " + normalized);
        assertEquals(12.17 / (12.17 + ScoreNormalizer.DEFAULT_K), normalized, 1e-9);
        assertEquals(ScoreNormalizer.ORIGIN_COMPRESSED, ScoreNormalizer.originOf(null, Double.valueOf(12.17)));
    }

    /** 跨请求可比较：同一个分数在任何请求里都得同一个值，否则调用方的阈值就是摆设。 */
    @Test
    void sameScoreAlwaysYieldsSameNormalizedValue() {
        double first = ScoreNormalizer.normalize(null, Double.valueOf(8.0));
        double second = ScoreNormalizer.normalize(null, Double.valueOf(8.0));
        assertEquals(first, second, 0.0);
    }

    /** 单调：排序不能因为归一化而反掉，否则协议要求的"按 score 降序"就守不住。 */
    @Test
    void normalizationIsMonotonic() {
        double low = ScoreNormalizer.normalize(null, Double.valueOf(1.0));
        double high = ScoreNormalizer.normalize(null, Double.valueOf(30.0));
        assertTrue(high > low, "高分必须归一得更高");
    }

    /** 越界的重排分不能信：照搬会直接违反 [0,1]，退回压缩路径。 */
    @Test
    void outOfRangeRerankScoreFallsBackToCompression() {
        assertEquals(ScoreNormalizer.ORIGIN_COMPRESSED, ScoreNormalizer.originOf(Double.valueOf(1.5), Double.valueOf(12.0)));
        assertTrue(ScoreNormalizer.normalize(Double.valueOf(1.5), Double.valueOf(12.0)) <= 1.0);
        assertEquals(ScoreNormalizer.ORIGIN_COMPRESSED, ScoreNormalizer.originOf(Double.valueOf(-0.2), Double.valueOf(12.0)));
    }

    /** 没有分数时给 0 而不是报错：空结果是正常响应，不该因为缺一个数字变成 500。 */
    @Test
    void missingScoresGiveZero() {
        assertEquals(0.0, ScoreNormalizer.normalize(null, null), 0.0);
        assertEquals(ScoreNormalizer.ORIGIN_NONE, ScoreNormalizer.originOf(null, null));
        assertEquals(0.0, ScoreNormalizer.normalize(null, Double.valueOf(0.0)), 0.0);
    }

    /** 脏数据不炸：NaN / 无穷 / 负数一律夹回区间内。 */
    @Test
    void dirtyValuesStayInRange() {
        assertTrue(ScoreNormalizer.normalize(Double.valueOf(Double.NaN), Double.valueOf(Double.NaN)) >= 0.0);
        assertTrue(ScoreNormalizer.normalize(null, Double.valueOf(Double.POSITIVE_INFINITY)) <= 1.0);
        assertEquals(0.0, ScoreNormalizer.normalize(null, Double.valueOf(-5.0)), 0.0);
    }

    /** 重排分在原接口里与最终分同源（重排后 score 就是 rerankScore），归一不该改变它。 */
    @Test
    void identicalRerankAndScorePassThrough() {
        assertEquals(0.42, ScoreNormalizer.normalize(Double.valueOf(0.42), Double.valueOf(0.42)), 1e-9);
    }
}
