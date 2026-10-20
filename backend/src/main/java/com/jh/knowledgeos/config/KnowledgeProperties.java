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

    private Auth auth = new Auth();

    private External external = new External();

    private boolean startupFailFast = false;

    private boolean opsRbacEnabled = false;

    private String opsViewerToken = "";

    private String opsOperatorToken = "";

    private String opsAdminToken = "";

    /**
     * 内网数据源常挂在自签名证书或 IP 直连上，默认严格校验会直接握手失败。
     * 打开后所有连接器都不校验证书；只对单个数据源放开通行，用连接器配置 insecureSsl。
     */
    private boolean connectorTrustAllSsl = false;

    public boolean isConnectorTrustAllSsl() {
        return connectorTrustAllSsl;
    }

    public void setConnectorTrustAllSsl(boolean connectorTrustAllSsl) {
        this.connectorTrustAllSsl = connectorTrustAllSsl;
    }

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

    public Auth auth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth == null ? new Auth() : auth;
    }

    public External external() {
        return external;
    }

    public void setExternal(External external) {
        this.external = external == null ? new External() : external;
    }

    /**
     * Console authentication settings.
     *
     * <p>{@code enabled=false} keeps the historical open backend for scripted environments;
     * {@code allowAnonymous=true} lets unauthenticated callers keep working in read-only
     * fashion while the console still requires a real login.</p>
     */
    public static class Auth {

        private boolean enabled = true;

        private boolean allowAnonymous = false;

        private int sessionHours = 12;

        private String bootstrapAdminUsername = "admin";

        private String bootstrapAdminPassword = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public boolean isAllowAnonymous() {
            return allowAnonymous;
        }

        public void setAllowAnonymous(boolean value) {
            allowAnonymous = value;
        }

        public int getSessionHours() {
            return sessionHours;
        }

        public void setSessionHours(int value) {
            sessionHours = value < 1 ? 12 : value;
        }

        public String getBootstrapAdminUsername() {
            return bootstrapAdminUsername;
        }

        public void setBootstrapAdminUsername(String value) {
            bootstrapAdminUsername = value;
        }

        public String getBootstrapAdminPassword() {
            return bootstrapAdminPassword;
        }

        public void setBootstrapAdminPassword(String value) {
            bootstrapAdminPassword = value == null ? "" : value;
        }
    }

    /**
     * External interface-service layer: RESTful, MCP Server and SSE share one credential,
     * one quota and one audit trail, but each channel can be switched off on its own.
     */
    public static class External {

        private boolean enabled = true;

        private boolean mcpEnabled = true;

        private boolean sseEnabled = true;

        /** Emergency deployment-wide switch for the Agent memory APIs and MCP tools. */
        private boolean agentMemoryEnabled = true;

        private int sseHeartbeatSeconds = 15;

        private int sseMaxMinutes = 30;

        /**
         * Answer is replayed to SSE clients in slices until the LLM itself streams tokens.
         */
        private int answerChunkChars = 40;

        private int answerChunkDelayMillis = 40;

        private int defaultRateLimitPerMinute = 60;

        private String allowedOrigins = "";

        private int embedTokenHours = 12;

        /**
         * Upper bound accepted when a caller asks for a specific lifetime (hours). 0 means no cap.
         */
        private int embedTokenMaxHours = 8760;

        /**
         * Whether a caller may mint a token that never expires; such a token is still revoked
         * the moment its API key is revoked or disabled.
         */
        private boolean embedTokenPermanent = true;

        private String embedTokenSecret = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean value) {
            enabled = value;
        }

        public boolean isMcpEnabled() {
            return mcpEnabled;
        }

        public void setMcpEnabled(boolean value) {
            mcpEnabled = value;
        }

        public boolean isSseEnabled() {
            return sseEnabled;
        }

        public void setSseEnabled(boolean value) {
            sseEnabled = value;
        }

        public boolean isAgentMemoryEnabled() {
            return agentMemoryEnabled;
        }

        public void setAgentMemoryEnabled(boolean value) {
            agentMemoryEnabled = value;
        }

        public int getSseHeartbeatSeconds() {
            return sseHeartbeatSeconds;
        }

        public void setSseHeartbeatSeconds(int value) {
            sseHeartbeatSeconds = Math.max(value, 3);
        }

        public int getSseMaxMinutes() {
            return sseMaxMinutes;
        }

        public void setSseMaxMinutes(int value) {
            sseMaxMinutes = value < 1 ? 30 : Math.min(value, 240);
        }

        public int getAnswerChunkChars() {
            return answerChunkChars;
        }

        public void setAnswerChunkChars(int value) {
            answerChunkChars = value < 8 ? 40 : value;
        }

        public int getAnswerChunkDelayMillis() {
            return answerChunkDelayMillis;
        }

        public void setAnswerChunkDelayMillis(int value) {
            answerChunkDelayMillis = Math.min(Math.max(value, 0), 500);
        }

        public int getDefaultRateLimitPerMinute() {
            return defaultRateLimitPerMinute;
        }

        public void setDefaultRateLimitPerMinute(int value) {
            defaultRateLimitPerMinute = value < 1 ? 60 : value;
        }

        /**
         * Origins allowed to embed the chat widget when a key carries no origin of its own.
         */
        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String value) {
            allowedOrigins = value == null ? "" : value.trim();
        }

        public int getEmbedTokenHours() {
            return embedTokenHours;
        }

        public void setEmbedTokenHours(int value) {
            embedTokenHours = Math.min(Math.max(value, 1), 720);
        }

        public int getEmbedTokenMaxHours() {
            return embedTokenMaxHours;
        }

        public void setEmbedTokenMaxHours(int value) {
            embedTokenMaxHours = value < 1 ? 0 : value;
        }

        public boolean isEmbedTokenPermanent() {
            return embedTokenPermanent;
        }

        public void setEmbedTokenPermanent(boolean value) {
            embedTokenPermanent = value;
        }

        /**
         * Signing secret of short-lived embed tokens; empty means "generate one at startup".
         */
        public String getEmbedTokenSecret() {
            return embedTokenSecret;
        }

        public void setEmbedTokenSecret(String value) {
            embedTokenSecret = value == null ? "" : value.trim();
        }
    }
}
