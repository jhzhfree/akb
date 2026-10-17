package com.jh.knowledgeos.dto;

import com.jh.knowledgeos.common.AnswerFormat;
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
}
