package com.jh.knowledgeos.service;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 and a per-password random salt.
 *
 * <p>Plain or reversibly encrypted passwords are never stored: the stored value is
 * {@code pbkdf2_sha256$iterations$salt$hash}, and verification runs in constant time so
 * it does not leak how much of a guess matched.</p>
 */
@Component
public class PasswordHasher {
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 120000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS, HASH_BYTES);
        return PREFIX + "$" + ITERATIONS + "$" + base64(salt) + "$" + base64(derived);
    }

    public boolean verify(String password, String stored) {
        if (password == null || stored == null) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations, int length) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt, iterations, length * 8);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] derived = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return derived;
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        }
    }

    private String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
