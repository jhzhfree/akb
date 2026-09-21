package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Platform account: authentication principal and knowledge-base owner.
 */
public class User {

    private Long id;

    private String userCode;

    private String username;

    private String displayName;

    private String email;

    private String passwordHash;

    private String role;

    private String status;

    private LocalDateTime lastLoginTime;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;

    /**
     * 所属用户组，仅用于 API 出参，落库时忽略。
     */
    private List<Long> groupIds = new ArrayList<Long>();

    private List<String> groupNames = new ArrayList<String>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String v) {
        userCode = v;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String v) {
        username = v;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String v) {
        displayName = v;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String v) {
        email = v;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String v) {
        passwordHash = v;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String v) {
        role = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime v) {
        lastLoginTime = v;
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

    public List<Long> getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(List<Long> v) {
        groupIds = v == null ? new ArrayList<Long>() : v;
    }

    public List<String> getGroupNames() {
        return groupNames;
    }

    public void setGroupNames(List<String> v) {
        groupNames = v == null ? new ArrayList<String>() : v;
    }
}
