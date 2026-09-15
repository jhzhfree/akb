package com.jh.knowledgeos.config;

import com.jh.knowledgeos.service.AiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;

/**
 * Fail-fast startup validation for security-sensitive and production-critical configuration.
 */
@Component
public class StartupDiagnostics implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupDiagnostics.class);

    private final KnowledgeProperties properties;
    private final DataSource dataSource;
    private final AiClient aiClient;

    public StartupDiagnostics(
            KnowledgeProperties properties,
            DataSource dataSource,
            AiClient aiClient) {
        this.properties = properties;
        this.dataSource = dataSource;
        this.aiClient = aiClient;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        validateAiServiceUrl();
        validateOpsRbac();
        verifyDatabase();
        verifyAiService();
    }

    private void validateAiServiceUrl() {
        URI uri = URI.create(properties.getAiServiceBaseUrl());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalStateException(
                    "knowledge.ai-service-base-url 非法: " + properties.getAiServiceBaseUrl());
        }
    }

    private void validateOpsRbac() {
        if (!properties.isOpsRbacEnabled()) {
            return;
        }
        String viewer = properties.getOpsViewerToken();
        String operator = properties.getOpsOperatorToken();
        String admin = properties.getOpsAdminToken();
        if (weak(viewer) || weak(operator) || weak(admin)) {
            throw new IllegalStateException("OPS RBAC enabled but tokens are missing or use change-me defaults");
        }
        if (viewer.equals(operator) || viewer.equals(admin) || operator.equals(admin)) {
            throw new IllegalStateException("OPS RBAC tokens must be distinct");
        }
    }

    private boolean weak(String token) {
        return token == null || token.length() < 24 || token.toLowerCase().contains("change-me");
    }

    private void verifyDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            log.info(
                    "RC startup check database={} valid={}",
                    connection.getMetaData().getDatabaseProductVersion(),
                    connection.isValid(2));
        }
    }

    private void verifyAiService() throws Exception {
        try {
            log.info("RC startup check aiCapabilities={}", aiClient.get("/ai/capabilities"));
        } catch (Exception ex) {
            if (properties.isStartupFailFast()) {
                throw ex;
            }
            log.warn("RC startup degraded AI service unavailable: {}", ex.getMessage());
        }
    }
}
