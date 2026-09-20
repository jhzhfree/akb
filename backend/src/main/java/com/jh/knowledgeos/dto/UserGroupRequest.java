package com.jh.knowledgeos.dto;

import java.util.List;

/** User-group payload; {@code memberIds} replaces the whole membership when provided. */
public class UserGroupRequest {
    private String name;
    private String description;
    private List<Long> memberIds;

    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public List<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(List<Long> v) { memberIds = v; }
}
