package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;

/** Credentials submitted by the console login form. */
public class LoginRequest {
    @NotBlank private String username;
    @NotBlank private String password;

    public String getUsername() { return username; }
    public void setUsername(String v) { username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { password = v; }
}
