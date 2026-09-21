package com.jh.knowledgeos.config;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.AuthMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.service.AuthService;
import com.jh.knowledgeos.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Creates the first administrator when the platform has no account yet.
 *
 * <p>Without this step a freshly installed system would be unreachable: every endpoint
 * requires a login, but nobody could log in. The generated password is only printed once,
 * and deployments should override it with {@code AUTH_BOOTSTRAP_ADMIN_PASSWORD}.</p>
 */
@Component
public class AuthBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrap.class);

    private final AuthMapper mapper;

    private final KnowledgeMapper knowledgeMapper;

    private final UserService users;

    private final AuthService authService;

    private final KnowledgeProperties.Auth properties;

    private final SecureRandom random = new SecureRandom();

    public AuthBootstrap(AuthMapper mapper, KnowledgeMapper knowledgeMapper, UserService users, AuthService authService, KnowledgeProperties properties) {
        this.mapper = mapper;
        this.knowledgeMapper = knowledgeMapper;
        this.users = users;
        this.authService = authService;
        this.properties = properties.auth();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.warn("console authentication is disabled; API accepts unauthenticated calls");
            return;
        }
        if (mapper.countUsers() > 0) {
            return;
        }
        String password = properties.getBootstrapAdminPassword();
        boolean generated = password == null || password.trim().isEmpty();
        if (generated) {
            byte[] bytes = new byte[12];
            random.nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        users.create(properties.getBootstrapAdminUsername(), password, "系统管理员", "", "ADMIN", null);
        // 默认知识库归属初始管理员，避免无人能治理它。
        User admin = mapper.getUserByUsername(properties.getBootstrapAdminUsername());
        if (admin != null && admin.getId() != null) {
            knowledgeMapper.updateKnowledgeBaseOwner(1L, admin.getId());
        }
        log.warn("=================================================================");
        log.warn("已创建初始管理员账号：{}", properties.getBootstrapAdminUsername());
        log.warn("初始密码：{}（{}）", password, generated ? "随机生成，请首次登录后立即修改" : "来自配置");
        log.warn("=================================================================");
    }

    /**
     * Expired sessions are useless rows; deleting them keeps the table small and audits clean.
     */
    @Scheduled(fixedRateString = "${knowledge.auth.session-purge-interval-ms:3600000}")
    public void purgeExpiredSessions() {
        try {
            int removed = authService.purgeExpiredSessions();
            if (removed > 0) {
                log.info("purged expired auth sessions count={}", removed);
            }
        } catch (Exception e) {
            log.warn("failed to purge expired auth sessions: {}", e.getMessage());
        }
    }
}
