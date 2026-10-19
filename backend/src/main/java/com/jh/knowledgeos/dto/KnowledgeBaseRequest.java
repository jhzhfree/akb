package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * Request for creating a knowledge-base workspace.
 *
 * <p>{@code visibility} is PUBLIC, PRIVATE, or GROUP; GROUP additionally requires
 * {@code ownerGroupId}, which is the group whose members may open the workspace.</p>
 */
public class KnowledgeBaseRequest {

    @NotBlank
    private String name;

    private String description;

    private String visibility;

    private Long ownerGroupId;

    /**
     * Scenario template: GENERAL, OPS, POLICY, BUSINESS or VULN. Blank means the generic
     * template, so an older caller or a script that never heard of scenarios keeps working.
     */
    private String scenario;

    /**
     * 默认检索语句：知识库预置的示例问句，只作提问输入框的提示与一键发起，不参与召回。
     */
    private String defaultQuery;

    public String getName() {
        return name;
    }

    public void setName(String v) {
        name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String v) {
        visibility = v;
    }

    public Long getOwnerGroupId() {
        return ownerGroupId;
    }

    public void setOwnerGroupId(Long v) {
        ownerGroupId = v;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String v) {
        scenario = v;
    }

    public String getDefaultQuery() {
        return defaultQuery;
    }

    public void setDefaultQuery(String v) {
        defaultQuery = v;
    }
}
