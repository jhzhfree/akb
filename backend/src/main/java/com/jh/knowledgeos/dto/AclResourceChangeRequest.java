package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request payload for an authoritative ACL change on a knowledge resource.
 */
public class AclResourceChangeRequest {

    @NotBlank
    private String resourceType;

    @NotBlank
    private String resourceCode;

    private List<String> permissionTags;

    private String reason;

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String v) {
        resourceType = v;
    }

    public String getResourceCode() {
        return resourceCode;
    }

    public void setResourceCode(String v) {
        resourceCode = v;
    }

    public List<String> getPermissionTags() {
        return permissionTags;
    }

    public void setPermissionTags(List<String> v) {
        permissionTags = v;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        reason = v;
    }
}
