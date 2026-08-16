package com.hlauth.hytale.security;

import com.hlauth.hytale.config.HlAuthConfig;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Password hashing and verification.
 *
 * <p>New passwords are hashed with bcrypt ({@code $2a$cost$salt+hash}).
 * Cost is either set in {@code config.yml} or chosen at startup by a short
 * benchmark (highest cost that stays under {@code bcryptTargetMs}).</p>
 *
 * <p>Successful logins with a leftover PBKDF2 hash from older hlAuth builds
 * are rewritten to bcrypt automatically.</p>
 */
public final class PasswordSecurity {

    static final int MIN_COST = 10;
    static final int MAX_COST = 14;
    private static final int DEFAULT_TARGET_MS = 250;
    private static final HexFormat HEX = HexFormat.of();

    private final int cost;
    private final boolean calibrated;

    public PasswordSecurity(HlAuthConfig config, HytaleLogger logger) {
        int configured = config.bcryptCost;
        boolean auto = configured < MIN_COST || configured > MAX_COST;
        if (auto) {
            int target = config.bcryptTargetMs > 0 ? config.bcryptTargetMs : DEFAULT_TARGET_MS;
            this.cost = calibrate(logger, target);
            config.bcryptCost = this.cost;
            this.calibrated = true;
        } else {
            this.cost = configured;
            this.calibrated = false;
        }
        logger.atInfo().log("Password hashing: bcrypt cost %d%s",
            this.cost, this.calibrated ? " (benchmarked)" : "");
    }

    /** True when this instance wrote a newly measured cost into the config object. */
    public boolean didCalibrate() {
        return calibrated;
    }

    public int getCost() {
        return cost;
    }

    public String computeHash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(cost));
    }

    public boolean comparePassword(String password, String storedHash) {
        if (storedHash == null || password == null) {
            return false;
        }
        if (isBcrypt(storedHash)) {
            return checkBcrypt(password, storedHash);
        }
        if (storedHash.startsWith("$PBKDF2$")) {
            return checkPbkdf2(password, storedHash);
        }
        return false;
    }

    /**
     * True when the stored hash should be rewritten with the current bcrypt cost.
     * Covers leftover PBKDF2 hashes and bcrypt hashes with a different cost.
     */
    public boolean needsRehash(String storedHash) {
        if (storedHash == null || !isBcrypt(storedHash)) {
            return true;
        }
        int storedCost = readBcryptCost(storedHash);
        return storedCost != cost;
    }

    private static boolean isBcrypt(String hash) {
        return hash.startsWith("$2a$")
            || hash.startsWith("$2b$")
            || hash.startsWith("$2y$")
            || hash.startsWith("$2$");
    }

    private static int readBcryptCost(String hash) {
        try {
            int off = hash.charAt(2) == '$' ? 3 : 4;
            return Integer.parseInt(hash.substring(off, off + 2));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean checkBcrypt(String password, String storedHash) {
        try {
            String computed = BCrypt.hashpw(password, storedHash);
            return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return false;
        }
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

    /**
     * Picks the highest bcrypt cost whose hash time stays at or under {@code targetMs}.
     * Floor is {@link #MIN_COST} even if that already exceeds the target.
     */
    static int calibrate(HytaleLogger logger, int targetMs) {
        int target = Math.min(2000, Math.max(50, targetMs));
        String sample = "hlAuth-bcrypt-bench";
        // Warm JIT / first-call overhead so the measured costs are comparable.
        BCrypt.hashpw(sample, BCrypt.gensalt(MIN_COST));

        int best = MIN_COST;
        for (int candidate = MIN_COST; candidate <= MAX_COST; candidate++) {
            long start = System.nanoTime();
            BCrypt.hashpw(sample, BCrypt.gensalt(candidate));
            long ms = (System.nanoTime() - start) / 1_000_000L;
            logger.atInfo().log("bcrypt cost %d: %d ms (target %d ms)", candidate, ms, target);
            if (ms > target) {
                break;
            }
            best = candidate;
        }
        logger.atInfo().log("Selected bcrypt cost %d (target %d ms)", best, target);
        return best;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "bcrypt(cost=%d)", cost);
    }
}
