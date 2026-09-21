package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * User group: the sharing boundary for GROUP-visible knowledge bases.
 */
public class UserGroup {

    private Long id;

    private String groupCode;

    private String name;

    private String description;

    private String status;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    /**
     * 成员数量与成员列表，仅用于 API 出参。
     */
    private Integer memberCount;

    private List<Long> memberIds = new ArrayList<Long>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGroupCode() {
        return groupCode;
    }

    public void setGroupCode(String v) {
        groupCode = v;
    }

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime v) {
        createdTime = v;
    }

    public LocalDateTime getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(LocalDateTime v) {
        updatedTime = v;
    }

    public Integer getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(Integer v) {
        memberCount = v;
    }

    public List<Long> getMemberIds() {
        return memberIds;
    }

    public void setMemberIds(List<Long> v) {
        memberIds = v == null ? new ArrayList<Long>() : v;
    }
}
