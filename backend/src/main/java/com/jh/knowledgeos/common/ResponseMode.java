package com.jh.knowledgeos.common;

import java.util.Locale;

/**
 * 外部接口的返回详略：同一份结果的三种"给多少"。
 *
 * <p>同一个问答接口今天有两类调用方：一类只要答案本身（嵌进工单、对话气泡里，多了没人看），
 * 一类要把检索过程一起展示（可追溯证据、调试为什么召回这些）。只给一种形状的结果，
 * 就只能让其中一类自己裁——而服务端裁比客户端挑更可靠：少传的字段不会在日志、
 * 抓包与前端状态里留副本，也避免了"某个调用方忘了裁，整条链路的数据都出去了"。</p>
 *
 * <p>与 {@link AnswerFormat} 正交：那个决定答案正文的<b>形状</b>（原文 / Markdown / 纯文本 /
 * HTML），这个决定整个响应的<b>字段多少</b>。</p>
 */
public final class ResponseMode {

    /** 极简：只给答案主体与数量口径。 */
    public static final String MINIMAL = "minimal";

    /** 基础：在极简之上补回检索结果（候选与进入上下文的候选）。 */
    public static final String BASIC = "basic";

    /** 完整：当前返回，字段一个不少。默认值——老调用方不受影响。 */
    public static final String FULL = "full";

    private ResponseMode() {
    }

    /**
     * 把调用方给的取值收敛到受支持的三种：不传、留空或认不出来的一律退回 {@code full}。
     *
     * <p>认不出来时退回"最全"而不是报错，理由是：一个拼错的参数不该让本来能拿到答案的
     * 调用变成 400，而多给的字段调用方本来就会忽略。</p>
     */
    public static String normalize(String value) {
        if (value == null) {
            return FULL;
        }
        String raw = value.trim().toLowerCase(Locale.ROOT);
        if (MINIMAL.equals(raw) || BASIC.equals(raw) || FULL.equals(raw)) {
            return raw;
        }
        return FULL;
    }
}
