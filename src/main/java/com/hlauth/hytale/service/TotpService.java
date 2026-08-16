package com.hlauth.hytale.service;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.data.PlayerAuth;
import com.hlauth.hytale.security.QrCode;
import com.hlauth.hytale.security.Totp;
import com.hlauth.hytale.ui.AuthUi;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory 2FA challenges (setup / verify / recovery screenshot) plus
 * TOTP and recovery-code checks against {@link PlayerAuth}.
 */
public final class TotpService {

    public enum Phase {
        NONE, VERIFY, SETUP, RECOVERY
    }

    private static final int RECOVERY_COUNT = 8;
    private static final char[] RECOVERY_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final HexFormat HEX = HexFormat.of();

    private static final class Pending {
        Phase phase = Phase.NONE;
        String secret;
        String otpauth;
        String qrMarkup;
        String[] recoveryCodes;
        boolean loginAfter;
        long lastTotpCounter = -1;
    }

    private final HlAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public TotpService(HlAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void clear(UUID uuid) {
        pending.remove(uuid);
    }

    public Phase phase(UUID uuid) {
        Pending p = pending.get(uuid);
        return p == null ? Phase.NONE : p.phase;
    }

    public String reminderKey(UUID uuid) {
        return switch (phase(uuid)) {
            case VERIFY -> "2fa.reminder.verify";
            case SETUP -> "2fa.reminder.setup";
            case RECOVERY -> "2fa.reminder.recovery";
            case NONE -> null;
        };
    }

    @Nullable
    public String pendingSecret(UUID uuid) {
        Pending p = pending.get(uuid);
        return p == null ? null : p.secret;
    }

    @Nullable
    public String pendingOtpauth(UUID uuid) {
        Pending p = pending.get(uuid);
        return p == null ? null : p.otpauth;
    }

    @Nullable
    public String pendingQrMarkup(UUID uuid) {
        Pending p = pending.get(uuid);
        return p == null ? null : p.qrMarkup;
    }

    @Nullable
    public String[] pendingRecoveryCodes(UUID uuid) {
        Pending p = pending.get(uuid);
        return p == null ? null : p.recoveryCodes;
    }

    public static boolean isBound(PlayerAuth auth) {
        return auth != null && auth.totpEnabled
            && auth.totpSecret != null && !auth.totpSecret.isBlank();
    }

    /**
     * Aborts a pending SETUP or VERIFY. Recovery is already bound, so it is left alone.
     *
     * @return {@code true} when login was not finished and the player should be disconnected
     */
    public boolean cancelUi(PlayerRef player) {
        Pending p = pending.get(player.getUuid());
        if (p == null || p.phase == Phase.NONE || p.phase == Phase.RECOVERY) {
            return false;
        }
        boolean kick = p.loginAfter || p.phase == Phase.VERIFY;
        pending.remove(player.getUuid());
        return kick;
    }

    public void beginVerify(UUID uuid) {
        Pending p = new Pending();
        p.phase = Phase.VERIFY;
        pending.put(uuid, p);
    }

    public void beginSetup(PlayerRef player, boolean loginAfter) {
        String secret = Totp.generateSecret();
        String issuer = plugin.getConfig().twoFactorIssuer;
        String otpauth = Totp.otpauthUri(issuer, player.getUsername(), secret);
        Pending p = new Pending();
        p.phase = Phase.SETUP;
        p.secret = secret;
        p.otpauth = otpauth;
        p.loginAfter = loginAfter;
        try {
            p.qrMarkup = QrCode.toUiMarkup(QrCode.encode(otpauth));
        } catch (RuntimeException e) {
            plugin.getLogger().atWarning().withCause(e)
                .log("Failed to generate 2FA QR for %s, secret-only fallback", player.getUsername());
            p.qrMarkup = "";
        }
        pending.put(player.getUuid(), p);
    }

    public AuthService.Result startEnable(PlayerRef player) {
        if (!plugin.getConfig().isTwoFactorEnabled()) {
            return fail("2fa.disabled");
        }
        if (!plugin.getLimboService().isAuthenticated(player.getUuid())) {
            return fail("error.notLoggedIn");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null) {
            return fail("error.notRegistered");
        }
        if (isBound(auth)) {
            return fail("2fa.alreadyEnabled");
        }
        if (com.hlauth.hytale.messenger.MessengerService.isBound(auth)) {
            return fail("messenger.totpBlocked");
        }
        beginSetup(player, false);
        return new AuthService.Result(AuthService.Result.Status.TOTP_SETUP,
            plugin.getMessages().get("2fa.setup.started"));
    }

    public AuthService.Result verifyLoginCode(PlayerRef player, String code) {
        Pending p = pending.get(player.getUuid());
        if (p == null || p.phase != Phase.VERIFY) {
            return fail("2fa.notWaiting");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (!isBound(auth)) {
            return fail("2fa.notEnabled");
        }
        long counter = Totp.matchingCounter(auth.totpSecret, code, Totp.DEFAULT_WINDOW, p.lastTotpCounter);
        if (counter < 0) {
            return wrongCode(player);
        }
        p.lastTotpCounter = counter;
        pending.remove(player.getUuid());
        plugin.getAuthService().completeLogin(player, auth);
        return ok("login.success");
    }

    public AuthService.Result confirmSetup(PlayerRef player, String code) {
        Pending p = pending.get(player.getUuid());
        if (p == null || p.phase != Phase.SETUP || p.secret == null) {
            return fail("2fa.notWaiting");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null) {
            return fail("error.notRegistered");
        }
        long counter = Totp.matchingCounter(p.secret, code, Totp.DEFAULT_WINDOW, p.lastTotpCounter);
        if (counter < 0) {
            return wrongCode(player);
        }
        String[] codes = generateRecoveryCodes();
        String[] hashes = new String[codes.length];
        for (int i = 0; i < codes.length; i++) {
            hashes[i] = hashRecovery(codes[i]);
        }
        auth.totpEnabled = true;
        auth.totpSecret = p.secret;
        auth.totpRecoveryHashes = hashes;
        plugin.getDataSource().updateAuth(auth);
        p.phase = Phase.RECOVERY;
        p.recoveryCodes = codes;
        p.lastTotpCounter = counter;
        plugin.getLogger().atInfo().log("Player %s enabled 2FA", player.getUsername());
        return new AuthService.Result(AuthService.Result.Status.TOTP_RECOVERY,
            plugin.getMessages().get("2fa.recovery.ready"));
    }

    public AuthService.Result useRecoveryCode(PlayerRef player, String code) {
        Pending p = pending.get(player.getUuid());
        if (p == null || p.phase != Phase.VERIFY) {
            return fail("2fa.notWaiting");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null || auth.totpRecoveryHashes == null) {
            return fail("2fa.badRecovery");
        }
        String normalized = normalizeRecovery(code);
        if (normalized.isEmpty()) {
            return fail("2fa.badRecovery");
        }
        List<String> remaining = new ArrayList<>();
        boolean matched = false;
        for (String hash : auth.totpRecoveryHashes) {
            if (!matched && hash != null && compareRecovery(normalized, hash)) {
                matched = true;
                continue;
            }
            remaining.add(hash);
        }
        if (!matched) {
            return wrongCode(player);
        }
        auth.totpRecoveryHashes = remaining.toArray(String[]::new);
        plugin.getDataSource().updateAuth(auth);
        pending.remove(player.getUuid());
        plugin.getAuthService().completeLogin(player, auth);
        plugin.getLogger().atInfo().log("Player %s logged in with a 2FA recovery code (%d left)",
            player.getUsername(), remaining.size());
        return ok("2fa.recovery.used");
    }

    public AuthService.Result continueAfterRecovery(PlayerRef player) {
        Pending p = pending.get(player.getUuid());
        if (p == null || p.phase != Phase.RECOVERY) {
            return fail("2fa.notWaiting");
        }
        boolean loginAfter = p.loginAfter;
        pending.remove(player.getUuid());
        if (loginAfter && !plugin.getLimboService().isAuthenticated(player.getUuid())) {
            PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
            if (auth != null) {
                plugin.getAuthService().completeLogin(player, auth);
            }
        }
        return ok("2fa.enabled");
    }

    public AuthService.Result disable(PlayerRef player, String code) {
        if (!plugin.getLimboService().isAuthenticated(player.getUuid())) {
            return fail("error.notLoggedIn");
        }
        if (plugin.getConfig().isTwoFactorRequired()) {
            return fail("2fa.cannotDisableRequired");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (!isBound(auth)) {
            return fail("2fa.notEnabled");
        }
        boolean totpOk = Totp.matchingCounter(auth.totpSecret, code, Totp.DEFAULT_WINDOW, -1) >= 0;
        boolean recoveryOk = false;
        if (!totpOk && auth.totpRecoveryHashes != null) {
            String normalized = normalizeRecovery(code);
            List<String> remaining = new ArrayList<>();
            for (String hash : auth.totpRecoveryHashes) {
                if (!recoveryOk && hash != null && compareRecovery(normalized, hash)) {
                    recoveryOk = true;
                    continue;
                }
                remaining.add(hash);
            }
            if (recoveryOk) {
                auth.totpRecoveryHashes = remaining.toArray(String[]::new);
            }
        }
        if (!totpOk && !recoveryOk) {
            return fail("2fa.wrongCode");
        }
        auth.totpEnabled = false;
        auth.totpSecret = null;
        auth.totpRecoveryHashes = null;
        plugin.getDataSource().updateAuth(auth);
        plugin.getLogger().atInfo().log("Player %s disabled 2FA", player.getUsername());
        return ok("2fa.disabled.ok");
    }

    public void adminReset(PlayerAuth auth) {
        auth.totpEnabled = false;
        auth.totpSecret = null;
        auth.totpRecoveryHashes = null;
        plugin.getDataSource().updateAuth(auth);
        if (auth.uuid != null && !auth.uuid.isEmpty()) {
            try {
                pending.remove(UUID.fromString(auth.uuid));
            } catch (IllegalArgumentException ignored) {
                // imported accounts may lack a real UUID
            }
        }
    }

    private AuthService.Result wrongCode(PlayerRef player) {
        int tries = plugin.getLimboService().incrementLoginTries(player.getUuid());
        plugin.getLogger().atWarning().log("Failed 2FA attempt %d for %s", tries, player.getUsername());
        if (tries >= plugin.getConfig().maxLoginTries) {
            AuthUi.closeAndDisconnect(player, plugin.getMessages().get("2fa.wrongCode"));
        }
        return fail("2fa.wrongCode");
    }

    private AuthService.Result ok(String key) {
        return new AuthService.Result(AuthService.Result.Status.SUCCESS, plugin.getMessages().get(key));
    }

    private AuthService.Result fail(String key) {
        return new AuthService.Result(AuthService.Result.Status.FAILURE, plugin.getMessages().get(key));
    }

    private String[] generateRecoveryCodes() {
        String[] codes = new String[RECOVERY_COUNT];
        for (int i = 0; i < codes.length; i++) {
            codes[i] = randomBlock(4) + "-" + randomBlock(4);
        }
        return codes;
    }

    private String randomBlock(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)];
        }
        return new String(chars);
    }

    private static String normalizeRecovery(String code) {
        if (code == null) {
            return "";
        }
        return code.replace("-", "").replace(" ", "").trim().toUpperCase();
    }

    private String hashRecovery(String code) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] digest = sha256(concat(salt, normalizeRecovery(code).getBytes(StandardCharsets.UTF_8)));
        return "$REC$" + HEX.formatHex(salt) + "$" + HEX.formatHex(digest);
    }

    private static boolean compareRecovery(String normalizedCode, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"REC".equals(parts[1])) {
            return false;
        }
        try {
            byte[] salt = HEX.parseHex(parts[2]);
            byte[] expected = HEX.parseHex(parts[3]);
            byte[] actual = sha256(concat(salt, normalizedCode.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
