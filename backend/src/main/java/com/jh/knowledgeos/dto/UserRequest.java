package com.jh.knowledgeos.dto;

import java.util.List;

/**
 * Account payload. {@code username} and {@code password} are mandatory on creation and
 * ignored when absent on update, so the same contract serves both operations.
 */
public class UserRequest {
    private String username;
    private String password;
    private String displayName;
    private String email;
    private String role;
    private List<Long> groupIds;

    public String getUsername() { return username; }
    public void setUsername(String v) { username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { password = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { displayName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { email = v; }
    public String getRole() { return role; }
    public void setRole(String v) { role = v; }
    public List<Long> getGroupIds() { return groupIds; }
    public void setGroupIds(List<Long> v) { groupIds = v; }
}
