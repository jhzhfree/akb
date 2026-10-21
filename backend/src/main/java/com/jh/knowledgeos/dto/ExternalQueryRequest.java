package com.jh.knowledgeos.dto;

import com.jh.knowledgeos.common.AnswerFormat;
import com.jh.knowledgeos.common.ResponseMode;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * External query contract: same capabilities as the console, one versioned payload.
 */
public class ExternalQueryRequest {

    private Long knowledgeBaseId;

    @NotBlank
    @Size(max = 4000)
    private String question;

    @Min(1)
    @Max(50)
    private Integer topK = 10;

    /**
     * Optional caller-side permission tags; the platform still enforces its own ACL.
     */
    private List<String> permissionTags;

    /**
     * 答案返回格式：{@code default}（模型原文）/ {@code markdown} / {@code text} / {@code html}。
     *
     * <p>不传即返回默认格式；认不出来的取值同样退回默认，而不是报错。响应里的
     * {@code answerFormat} 是实际生效的那个，调用方可以据此判断自己的取值有没有被接受。</p>
     */
    private String answerFormat = AnswerFormat.DEFAULT;

    /**
     * 返回详略：{@code minimal} / {@code basic} / {@code full}，默认 {@code full}（当前返回）。
     *
     * <p>{@code minimal} 只给答案主体与数量口径；{@code basic} 补回检索结果；
     * {@code full} 是全部字段。认不出来的取值退回 {@code full}，不会报错。
     * 响应里的 {@code responseMode} 是实际生效的那个（{@code full} 时不额外回显）。</p>
     */
    private String responseMode = ResponseMode.FULL;

    /**
     * 最低相关度分数（0~1）：低于该分数的候选不返回。
     *
     * <p>不传即不过滤，与以前完全一致。检索类接口靠它把"要多少条"（topK）和
     * "哪些条够格"（分数）分开：返回的 {@code returned} 是过滤后真的给出的条数，
     * 与 {@code topK} 不一致时说明是阈值筛掉的，而不是库里没有。</p>
     */
    @Min(0)
    @Max(1)
    private Double minScore;

    public Double getMinScore() {
        return minScore;
    }

    public void setMinScore(Double minScore) {
        this.minScore = minScore;
    }

    /**
     * 是否把该凭证身份下的长期记忆并入生成上下文，默认 {@code false}。
     *
     * <p>记忆只参与生成答案，不参与召回：它是"这个调用方以前留下过什么"，不是知识库里的
     * 证据，拿它去检索会把召回带偏。开了之后答案的措辞与侧重会受历史记忆影响，
     * 所以必须要了才生效——一次普通的检索不该悄悄带上历史。</p>
     */
    private Boolean includeMemory = Boolean.FALSE;

    public Boolean getIncludeMemory() {
        return includeMemory;
    }

    public void setIncludeMemory(Boolean includeMemory) {
        this.includeMemory = includeMemory;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public List<String> getPermissionTags() {
        return permissionTags;
    }

    public void setPermissionTags(List<String> permissionTags) {
        this.permissionTags = permissionTags;
    }

    public String getAnswerFormat() {
        return answerFormat;
    }

    public void setAnswerFormat(String answerFormat) {
        this.answerFormat = answerFormat;
    }

    public String getResponseMode() {
        return responseMode;
    }

    public void setResponseMode(String responseMode) {
        this.responseMode = responseMode;
    }
}
