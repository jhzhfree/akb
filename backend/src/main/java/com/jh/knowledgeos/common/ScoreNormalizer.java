package com.jh.knowledgeos.common;

/**
 * 把检索分数归一到 {@code [0,1]}（越大越相关）。
 *
 * <p>第三方检索协议（NovaWork standard）要求 {@code score} 落在这个区间，且跨请求可比较——
 * 调用方要按阈值过滤、在多库之间做 RRF 融合，一个"上界不明的分数"在这两处都没法用。</p>
 *
 * <p>本系统的分数有两种来源，值域并不一致：</p>
 *
 * <ul>
 *   <li>启用了重排时，{@code rerankScore} 就是最终分：启发式重排是 0.55×词重合 + 0.20×短语命中
 *       + 0.20×归一召回分 + 0.05×置信度（各项都在 0~1、权重和为 1），BGE cross-encoder 是
 *       sigmoid 输出，两者都天然落在 0~1；</li>
 *   <li>未启用重排时 {@code score} 是融合分（实测十几），没有上界。</li>
 * </ul>
 *
 * <p>所以这里优先用重排分；拿不到（没重排、或值域异常）才退回单调压缩 {@code s/(s+K)}：
 * 压缩只依赖分数自身，同一个分数在任何请求里都得到同一个值，这才谈得上"跨请求可比较"；
 * 用批次内的 min-max 会让同一片段在不同批次里得到不同分数，恰好是协议不建议的做法。</p>
 */
public final class ScoreNormalizer {

    /** 分数直接来自重排，无需换算。 */
    public static final String ORIGIN_RERANK = "rerank";

    /** 分数由融合分压缩而来。 */
    public static final String ORIGIN_COMPRESSED = "compressed";

    /** 没有可用分数。 */
    public static final String ORIGIN_NONE = "none";

    /**
     * 压缩系数。取 1：0.38 的融合分对应 0.28、12 分对应 0.92，与重排分的量级对得上。
     *
     * <p>取更大的值（比如 5）会把中小分数压得很低（0.38 → 0.07），调用方按 0.5 卡阈值时
     * 就会把本来相关的片段滤掉——归一化不该改变"这条够不够格"的判断。</p>
     */
    public static final double DEFAULT_K = 1.0;

    private ScoreNormalizer() {
    }

    /**
     * 归一化分数，恒定返回 {@code [0,1]}。
     *
     * @param rerankScore 重排分，可能为 null
     * @param score 最终分（未重排时是融合分），可能为 null
     */
    public static double normalize(Double rerankScore, Double score) {
        Double rerank = unitRange(rerankScore);
        if (rerank != null) {
            return rerank;
        }
        double raw = positive(score);
        return raw <= 0.0 ? 0.0 : raw / (raw + DEFAULT_K);
    }

    /** 这个归一化值是怎么来的：调用方据此判断它有多可信。 */
    public static String originOf(Double rerankScore, Double score) {
        if (unitRange(rerankScore) != null) {
            return ORIGIN_RERANK;
        }
        return positive(score) > 0.0 ? ORIGIN_COMPRESSED : ORIGIN_NONE;
    }

    /** 只接受 0~1 的重排分：越界或非法值一律当作"没有重排分"，走压缩路径。 */
    private static Double unitRange(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        if (value < 0.0 || value > 1.0) {
            return null;
        }
        return value;
    }

    private static double positive(Object value) {
        double raw = value instanceof Number ? ((Number)value).doubleValue() : 0.0;
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return 0.0;
        }
        return Math.max(raw, 0.0);
    }
}
