package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.AuthSession;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.AuthMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * Login, logout, and session verification.
 *
 * <p>Sessions are stored server-side so disabling an account or logging out takes effect
 * immediately; the client only ever holds an opaque random token.</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int TOKEN_BYTES = 32;

    private static final int TOUCH_INTERVAL_MINUTES = 5;

    private final AuthMapper mapper;

    private final UserService users;

    private final PasswordHasher hasher;

    private final KnowledgeProperties.Auth properties;

    private final SecureRandom random = new SecureRandom();

    public AuthService(AuthMapper mapper, UserService users, PasswordHasher hasher, KnowledgeProperties properties) {
        this.mapper = mapper;
        this.users = users;
        this.hasher = hasher;
        this.properties = properties.auth();
    }

    public Map<String, Object> login(String username, String password, String userAgent) {
        User user = mapper.getUserByUsername(username == null ? "" : username.trim());
        // 不区分"用户不存在"与"密码错误"，避免账号枚举。
        if (user == null || !hasher.verify(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(user.getStatus()))) {
            throw new IllegalArgumentException("账号已停用，请联系管理员");
        }
        String token = newToken();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(properties.getSessionHours());
        AuthSession session = new AuthSession();
        session.setToken(token);
        session.setUserId(user.getId());
        session.setIssuedAt(now);
        session.setExpiresAt(expiresAt);
        session.setLastAccessedAt(now);
        session.setStatus("ACTIVE");
        session.setUserAgent(userAgent == null ? "" : userAgent.substring(0, Math.min(userAgent.length(), 500)));
        mapper.insertSession(session);
        mapper.updateUserLoginTime(user.getId());
        log.info("user signed in username={} userId={}", user.getUsername(), user.getId());
        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("token", token);
        result.put("expiresAt", String.valueOf(expiresAt));
        result.put("user", users.profile(user));
        return result;
    }

    public void logout(String token) {
        if (token == null || token.trim().isEmpty()) {
            return;
        }
        mapper.revokeSession(token.trim());
        AuthContext.clear();
    }

    /**
     * Returns the session owner, or null when the token is unknown, revoked, or expired.
     */
    public User authenticate(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        AuthSession session = mapper.getSession(token.trim());
        if (session == null || !"ACTIVE".equalsIgnoreCase(String.valueOf(session.getStatus()))) {
            return null;
        }
        if (session.getExpiresAt() == null || session.getExpiresAt().isBefore(LocalDateTime.now())) {
            if (session.getId() != null) {
                mapper.revokeSession(session.getToken());
            }
            return null;
        }
        User user = mapper.getUserById(session.getUserId());
        if (user == null || !"ACTIVE".equalsIgnoreCase(String.valueOf(user.getStatus()))) {
            return null;
        }
        if (session.getLastAccessedAt() == null || session.getLastAccessedAt().isBefore(LocalDateTime.now().minusMinutes(TOUCH_INTERVAL_MINUTES))) {
            mapper.touchSession(session.getToken());
        }
        return user;
    }

    public void changePassword(User user, String oldPassword, String newPassword) {
        if (!hasher.verify(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        mapper.updateUserPassword(user.getId(), hasher.hash(requirePassword(newPassword)));
        mapper.revokeUserSessions(user.getId());
    }

    public String requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码至少 8 位");
        }
        return password;
    }

    public int purgeExpiredSessions() {
        return mapper.deleteExpiredSessions();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
