package com.hlauth.hytale.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30-second step) as used by
 * Google Authenticator, Aegis, 2FAS, etc.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    public static final int DEFAULT_WINDOW = 1;

    private Totp() {
    }

    public static String generateSecret() {
        byte[] raw = new byte[20];
        new java.security.SecureRandom().nextBytes(raw);
        return Base32.encode(raw);
    }

    public static int currentCode(String base32Secret) {
        return codeAt(base32Secret, System.currentTimeMillis() / 1000L / PERIOD_SECONDS);
    }

    public static boolean matches(String base32Secret, String userCode, int window, long lastCounter) {
        int expected;
        try {
            expected = Integer.parseInt(userCode.replace(" ", "").trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long counter = System.currentTimeMillis() / 1000L / PERIOD_SECONDS;
        for (int i = -window; i <= window; i++) {
            long c = counter + i;
            if (c < 0 || c == lastCounter) {
                continue;
            }
            if (codeAt(base32Secret, c) == expected) {
                return true;
            }
        }
        return false;
    }

    /** Time-step that produced a matching code, or -1. */
    public static long matchingCounter(String base32Secret, String userCode, int window, long lastCounter) {
        int expected;
        try {
            expected = Integer.parseInt(userCode.replace(" ", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        long counter = System.currentTimeMillis() / 1000L / PERIOD_SECONDS;
        for (int i = -window; i <= window; i++) {
            long c = counter + i;
            if (c < 0 || c == lastCounter) {
                continue;
            }
            if (codeAt(base32Secret, c) == expected) {
                return c;
            }
        }
        return -1;
    }

    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String iss = urlEncode(issuer == null || issuer.isBlank() ? "hlAuth" : issuer);
        String acc = urlEncode(account == null ? "" : account);
        return "otpauth://totp/" + iss + ":" + acc
            + "?secret=" + base32Secret
            + "&issuer=" + iss;
    }

    static int codeAt(String base32Secret, long counter) {
        byte[] key = Base32.decode(base32Secret);
        byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] hash = hmacSha1(key, msg);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
            | ((hash[offset + 1] & 0xFF) << 16)
            | ((hash[offset + 2] & 0xFF) << 8)
            | (hash[offset + 3] & 0xFF);
        int mod = 1_000_000;
        return binary % mod;
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    private static String urlEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append("0123456789ABCDEF".charAt(c >> 4));
                sb.append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return sb.toString();
    }
}
