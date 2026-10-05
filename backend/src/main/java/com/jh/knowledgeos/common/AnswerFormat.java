package com.jh.knowledgeos.common;

import java.util.Locale;

/**
 * 答案交付格式：同一份答案的几种形状。
 *
 * <p>聊天卡片要 Markdown；要粘进工单系统的集成只想要没有脚注的纯文本；文档导出要的是带引用
 * 原文的 HTML——否则读者得自己去找「[证据N]」指的是哪一段，更可能干脆不看。</p>
 *
 * <p>渲染发生在所有闸门之后：证据与蕴含闸门打的分数属于模型原文本身，先把引用章节拼上去，
 * 那些字会看起来像是答案自己说的话。</p>
 */
public final class AnswerFormat {

    /** 模型生成的原文，引用由调用方自己按需展示。 */
    public static final String DEFAULT = "default";

    /** 答案正文 + Markdown 引用原文章节。 */
    public static final String MARKDOWN = "markdown";

    /** 纯文本：去掉 Markdown 标记与证据章节。 */
    public static final String TEXT = "text";

    /** HTML 片段：答案里直接带上引用原文。 */
    public static final String HTML = "html";

    private AnswerFormat() {
    }

    /**
     * 把调用方给的格式收敛到受支持的取值：不传、留空或认不出来的一律退回 default。
     *
     * <p>接受而不是拒绝，是让一个老调用方或一次拼写错误继续沿用今天的行为，
     * 而不是把一个本来能问出答案的问题变成 400。</p>
     */
    public static String normalize(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String raw = value.trim().toLowerCase(Locale.ROOT);
        if (MARKDOWN.equals(raw) || TEXT.equals(raw) || HTML.equals(raw)) {
            return raw;
        }
        return DEFAULT;
    }
}
