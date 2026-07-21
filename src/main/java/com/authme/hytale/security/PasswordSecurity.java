package com.authme.hytale.security;

import com.authme.hytale.config.AuthMeConfig;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * Password hashing and verification.
 *
 * <p>New passwords are hashed with PBKDF2-HMAC-SHA256 (stored as
 * {@code $PBKDF2$iterations$saltHex$hashHex}). Verification also supports the
 * legacy AuthMeReloaded SHA-256 format ({@code $SHA$salt$hash}) so existing
 * Minecraft databases can be imported as-is.</p>
 */
public final class PasswordSecurity {

    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom random = new SecureRandom();

    public PasswordSecurity(AuthMeConfig config) {
        // config reserved for future algorithm selection
    }

    public String computeHash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        return "$PBKDF2$" + PBKDF2_ITERATIONS + "$" + HEX.formatHex(salt) + "$" + HEX.formatHex(hash);
    }

    public boolean comparePassword(String password, String storedHash) {
        if (storedHash == null || password == null) {
            return false;
        }
        if (storedHash.startsWith("$PBKDF2$")) {
            return checkPbkdf2(password, storedHash);
        }
        if (storedHash.startsWith("$SHA$")) {
            return checkLegacyAuthMeSha256(password, storedHash);
        }
        return false;
    }

    private boolean checkPbkdf2(String password, String storedHash) {
        String[] parts = storedHash.split("\\$");
        // ["", "PBKDF2", iterations, saltHex, hashHex]
        if (parts.length != 5) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = HEX.parseHex(parts[3]);
            byte[] expected = HEX.parseHex(parts[4]);
            byte[] actual = pbkdf2(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Legacy AuthMeReloaded format: {@code $SHA$salt$sha256(sha256(password) + salt)}. */
    private boolean checkLegacyAuthMeSha256(String password, String storedHash) {
        String[] parts = storedHash.split("\\$");
        // ["", "SHA", salt, hash]
        if (parts.length != 4) {
            return false;
        }
        String salt = parts[2];
        String expected = parts[3];
        String actual = sha256(sha256(password) + salt);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
