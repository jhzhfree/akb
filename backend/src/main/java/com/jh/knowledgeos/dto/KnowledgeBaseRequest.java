package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;

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
}
