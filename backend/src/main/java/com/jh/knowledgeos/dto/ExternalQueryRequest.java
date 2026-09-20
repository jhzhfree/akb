package com.jh.knowledgeos.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

/** External query contract: same capabilities as the console, one versioned payload. */
public class ExternalQueryRequest {
    private Long knowledgeBaseId;

    @NotBlank
    @Size(max = 4000)
    private String question;

    @Min(1)
    @Max(50)
    private Integer topK = 10;

    /** Optional caller-side permission tags; the platform still enforces its own ACL. */
    private List<String> permissionTags;

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public List<String> getPermissionTags() { return permissionTags; }
    public void setPermissionTags(List<String> permissionTags) { this.permissionTags = permissionTags; }
}
