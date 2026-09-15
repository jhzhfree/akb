package com.jh.knowledgeos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

/**
 * Typed backend configuration for AI integration and operational security.
 *
 * <p>Keep configuration access centralized here instead of reading environment variables
 * directly from services. This makes production validation and test overrides predictable.</p>
 */
@Validated
@ConfigurationProperties(prefix = "knowledge")
public class KnowledgeProperties {
    @NotBlank
    private String aiServiceBaseUrl;

    @Min(100)
    private int aiConnectTimeoutMs = 10000;

    @Min(1000)
    private int aiReadTimeoutMs = 180000;

    private boolean startupFailFast = false;
    private boolean opsRbacEnabled = false;
    private String opsViewerToken = "";
    private String opsOperatorToken = "";
    private String opsAdminToken = "";

    public String getAiServiceBaseUrl() {
        return aiServiceBaseUrl;
    }

    public void setAiServiceBaseUrl(String aiServiceBaseUrl) {
        this.aiServiceBaseUrl = aiServiceBaseUrl;
    }

    public int getAiConnectTimeoutMs() {
        return aiConnectTimeoutMs;
    }

    public void setAiConnectTimeoutMs(int aiConnectTimeoutMs) {
        this.aiConnectTimeoutMs = aiConnectTimeoutMs;
    }

    public int getAiReadTimeoutMs() {
        return aiReadTimeoutMs;
    }

    public void setAiReadTimeoutMs(int aiReadTimeoutMs) {
        this.aiReadTimeoutMs = aiReadTimeoutMs;
    }

    public boolean isStartupFailFast() {
        return startupFailFast;
    }

    public void setStartupFailFast(boolean startupFailFast) {
        this.startupFailFast = startupFailFast;
    }

    public boolean isOpsRbacEnabled() {
        return opsRbacEnabled;
    }

    public void setOpsRbacEnabled(boolean opsRbacEnabled) {
        this.opsRbacEnabled = opsRbacEnabled;
    }

    public String getOpsViewerToken() {
        return opsViewerToken;
    }

    public void setOpsViewerToken(String value) {
        this.opsViewerToken = value == null ? "" : value;
    }

    public String getOpsOperatorToken() {
        return opsOperatorToken;
    }

    public void setOpsOperatorToken(String value) {
        this.opsOperatorToken = value == null ? "" : value;
    }

    public String getOpsAdminToken() {
        return opsAdminToken;
    }

    public void setOpsAdminToken(String value) {
        this.opsAdminToken = value == null ? "" : value;
    }
}
