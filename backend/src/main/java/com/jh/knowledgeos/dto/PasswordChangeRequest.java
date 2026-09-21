package com.jh.knowledgeos.dto;

import javax.validation.constraints.NotBlank;

/**
 * Self-service password change: the old password must be proven first.
 */
public class PasswordChangeRequest {

    @NotBlank
    private String oldPassword;

    @NotBlank
    private String newPassword;

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String v) {
        oldPassword = v;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String v) {
        newPassword = v;
    }
}
