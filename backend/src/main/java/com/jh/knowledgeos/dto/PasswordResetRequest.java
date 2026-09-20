package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;

/** Administrator-driven password reset; all sessions of the account are revoked. */
public class PasswordResetRequest {
    @NotBlank private String password;

    public String getPassword() { return password; }
    public void setPassword(String v) { password = v; }
}
