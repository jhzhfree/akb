package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Short-lived, self-contained credentials for the embeddable chat widget.
 *
 * <p>An embedded widget lives in somebody else's browser, so anything it holds is public:
 * handing it the API key itself would publish a credential that outlives the page view.
 * Instead the page exchanges an API key for a token that is bound to one key, one knowledge
 * base and one origin, expires in hours, and can be revoked simply by revoking the key it
 * was minted from. The token is signed rather than stored, so no session table is needed and
 * every backend instance can validate it.</p>
 */
@Service
public class EmbedTokenService {

    public static final String TOKEN_PREFIX = "kos_et_";

    /**
     * v2 carries a token id (jti) so a single leaked token can be revoked without touching the
     * key behind it; v1 tokens stay valid but can only be revoked through their key.
     */
    private static final String VERSION = "v2";

    private static final String LEGACY_VERSION = "v1";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final KnowledgeProperties.External properties;

    private final String secret;

    public EmbedTokenService(KnowledgeProperties properties) {
        this.properties = properties.external();
        String configured = this.properties.getEmbedTokenSecret();
        // A random secret per process would invalidate every outstanding token on restart, and
        // would make tokens from one instance unusable on another. So: an explicitly configured
        // value wins, otherwise one is generated once and kept in the user's home. Multi-instance
        // deployments must configure the same value on every node (or share the file).
        this.secret = configured == null || configured.trim().isEmpty() ? persistedSecret() : configured.trim();
    }

    private static String persistedSecret() {
        File file = new File(System.getProperty("user.home", "."), ".knowledge-os/embed-token.secret");
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (file.exists() && file.length() > 0) {
                String stored = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                if (!stored.isEmpty()) {
                    return stored;
                }
            }
            String generated = generateSecret();
            Files.write(file.toPath(), generated.getBytes(StandardCharsets.UTF_8));
            return generated;
        } catch (IOException ex) {
            // A read-only home is not a reason to refuse starting; tokens just do not survive a restart.
            return generateSecret();
        }
    }

    /**
     * Mints a token for one key and (optionally) one knowledge base and origin, using the
     * deployment default lifetime.
     */
    public EmbedToken issue(ApiKey key, Long knowledgeBaseId, String origin) {
        return issue(key, knowledgeBaseId, origin, null);
    }

    /**
     * Mints a token with an explicit lifetime.
     *
     * @param expiresInSeconds null keeps the deployment default, {@code <= 0} mints a token that
     *                         never expires, anything else is clamped to the configured window.
     */
    public EmbedToken issue(ApiKey key, Long knowledgeBaseId, String origin, Long expiresInSeconds) {
        long expiresAt = expiresAt(expiresInSeconds);
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String jti = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String payload = key.getId() + "|" + (knowledgeBaseId == null ? "" : String.valueOf(knowledgeBaseId)) + "|" + flatten(origin) + "|"
            + expiresAt + "|" + jti + "|" + nonce;
        return new EmbedToken(key.getId(), knowledgeBaseId, origin == null ? "" : origin, expiresAt, jti,
            TOKEN_PREFIX + VERSION + "." + encode(payload) + "." + sign(payload));
    }

    /**
     * Turns the requested lifetime into an expiry timestamp; {@code 0} stands for "never expires",
     * which keeps the payload format unchanged while letting a token outlive any clock window.
     */
    private long expiresAt(Long expiresInSeconds) {
        if (expiresInSeconds == null) {
            return System.currentTimeMillis() + Math.max(properties.getEmbedTokenHours(), 1) * 3600_000L;
        }
        if (expiresInSeconds <= 0L) {
            return 0L;
        }
        long seconds = expiresInSeconds;
        long maxHours = properties.getEmbedTokenMaxHours();
        if (maxHours > 0) {
            seconds = Math.min(seconds, maxHours * 3600L);
        }
        seconds = Math.max(seconds, 60L);
        return System.currentTimeMillis() + seconds * 1000L;
    }

    /**
     * Validates the signature and expiry; returns null for anything that does not check out.
     */
    public EmbedToken parse(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String raw = token.trim();
        if (raw.startsWith(TOKEN_PREFIX)) {
            raw = raw.substring(TOKEN_PREFIX.length());
        }
        String[] parts = raw.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        boolean current = VERSION.equals(parts[0]);
        if (!current && !LEGACY_VERSION.equals(parts[0])) {
            return null;
        }
        String payload = decode(parts[1]);
        if (payload == null) {
            return null;
        }
        String expected = sign(payload);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length < (current ? 6 : 5)) {
            return null;
        }
        try {
            long expiresAt = Long.parseLong(fields[3]);
            // expiresAt == 0 is the "never expires" marker; anything in the past is refused.
            if (expiresAt != 0L && expiresAt <= System.currentTimeMillis()) {
                return null;
            }
            Long keyId = Long.valueOf(fields[0]);
            Long kb = fields[1].isEmpty() ? null : Long.valueOf(fields[1]);
            String jti = current ? fields[4] : null;
            return new EmbedToken(keyId, kb, fields[2], expiresAt, jti, raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Embed token signing unavailable", ex);
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String flatten(String value) {
        return value == null ? "" : value.replace("|", " ").trim();
    }

    /**
     * A validated embed token: which key minted it, what it is allowed to read and until when.
     */
    public static final class EmbedToken {

        private final Long keyId;

        private final Long knowledgeBaseId;

        private final String origin;

        private final long expiresAt;

        /** Token id used for revocation; null for v1 tokens, which can only die with their key. */
        private final String jti;

        private final String raw;

        EmbedToken(Long keyId, Long knowledgeBaseId, String origin, long expiresAt, String jti, String raw) {
            this.keyId = keyId;
            this.knowledgeBaseId = knowledgeBaseId;
            this.origin = origin;
            this.expiresAt = expiresAt;
            this.jti = jti;
            this.raw = raw;
        }

        public Long getKeyId() {
            return keyId;
        }

        public Long getKnowledgeBaseId() {
            return knowledgeBaseId;
        }

        public String getOrigin() {
            return origin;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        /**
         * A permanent token has no expiry of its own: it dies with the API key it was minted from.
         */
        public boolean isPermanent() {
            return expiresAt == 0L;
        }

        public String getRaw() {
            return raw;
        }

        public String getJti() {
            return jti;
        }

        /** Short form shown in the console: enough to recognise a token, useless to a thief. */
        public String getDisplayId() {
            if (jti != null && jti.length() >= 8) {
                return jti.substring(0, 4) + "…" + jti.substring(jti.length() - 4);
            }
            return jti == null ? "v1（无令牌 ID）" : jti;
        }
    }
}
