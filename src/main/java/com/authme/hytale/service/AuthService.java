package com.authme.hytale.service;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.config.AuthMeConfig;
import com.authme.hytale.data.PlayerAuth;
import com.authme.hytale.ui.AuthUi;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Core authentication logic shared by the UI pages and the chat commands.
 */
public final class AuthService {

    /** Result of a login/register attempt, carrying a localized message. */
    public record Result(boolean success, Message message) {
    }

    private final AuthMePlugin plugin;

    public AuthService(AuthMePlugin plugin) {
        this.plugin = plugin;
    }

    private Result ok(String key) {
        return new Result(true, plugin.getMessages().get(key));
    }

    private Result fail(String key) {
        return new Result(false, plugin.getMessages().get(key));
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    public Result register(PlayerRef player, String password, String confirmation) {
        AuthMeConfig config = plugin.getConfig();
        String name = player.getUsername();

        if (plugin.getDataSource().isRegistered(name)) {
            return fail("error.alreadyRegistered");
        }
        if (password == null || password.isEmpty() || confirmation == null || confirmation.isEmpty()) {
            return fail("error.emptyPassword");
        }
        if (!password.equals(confirmation)) {
            return fail("error.passwordMismatch");
        }
        Result validation = validatePassword(password, name);
        if (validation != null) {
            return validation;
        }

        String ip = getIp(player);
        if (config.maxRegistrationsPerIp > 0
                && plugin.getDataSource().countByRegistrationIp(ip) >= config.maxRegistrationsPerIp) {
            return fail("error.tooManyAccountsPerIp");
        }

        String hash = plugin.getPasswordSecurity().computeHash(password);
        PlayerAuth auth = new PlayerAuth(name, player.getUuid().toString(), hash, ip);
        auth.lastIp = ip;
        auth.lastLogin = System.currentTimeMillis();
        auth.premium = config.premiumCheckEnabled
            && plugin.getPremiumService().isVerifiedPremium(player.getUuid());
        plugin.getDataSource().saveAuth(auth);
        finishAuth(player);

        plugin.getLogger().atInfo().log("Player %s registered (ip: %s, premium: %s)",
            name, ip, auth.premium);
        return ok("register.success");
    }

    /**
     * Registers a verified premium player without a password and lets them in.
     * The stored hash is a random secret so offline clients cannot log in with an empty password.
     */
    public boolean registerPremiumAuto(PlayerRef player) {
        String name = player.getUsername();
        if (plugin.getDataSource().isRegistered(name)) {
            return false;
        }
        String ip = getIp(player);
        String randomSecret = java.util.UUID.randomUUID() + ":" + player.getUuid();
        String hash = plugin.getPasswordSecurity().computeHash(randomSecret);
        PlayerAuth auth = new PlayerAuth(name, player.getUuid().toString(), hash, ip);
        auth.lastIp = ip;
        auth.lastLogin = System.currentTimeMillis();
        auth.premium = true;
        plugin.getDataSource().saveAuth(auth);
        finishAuth(player);
        plugin.getLogger().atInfo().log("Player %s auto-registered as premium (ip: %s)", name, ip);
        return true;
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    public Result login(PlayerRef player, String password) {
        String name = player.getUsername();
        PlayerAuth auth = plugin.getDataSource().getAuth(name);

        if (auth == null) {
            return fail("error.notRegistered");
        }
        if (plugin.getLimboService().isAuthenticated(player.getUuid())) {
            return fail("error.alreadyLoggedIn");
        }
        if (password == null || password.isEmpty()) {
            return fail("error.emptyPassword");
        }

        Result premiumBlock = checkPremiumMismatch(player, auth);
        if (premiumBlock != null) {
            return premiumBlock;
        }

        if (!plugin.getPasswordSecurity().comparePassword(password, auth.password)) {
            int tries = plugin.getLimboService().incrementLoginTries(player.getUuid());
            plugin.getLogger().atWarning().log("Failed login attempt %d for %s (ip: %s)",
                tries, name, getIp(player));
            if (plugin.getConfig().kickOnWrongPassword
                    || tries >= plugin.getConfig().maxLoginTries) {
                AuthUi.closeAndDisconnect(player, plugin.getMessages().get("error.wrongPassword"));
            }
            return fail("error.wrongPassword");
        }

        completeLogin(player, auth);
        return ok("login.success");
    }

    /**
     * Blocks password login when the joining client type does not match the
     * account (premium ↔ offline). Waits briefly for the PlayerDB lookup if needed.
     */
    private Result checkPremiumMismatch(PlayerRef player, PlayerAuth auth) {
        if (!plugin.getConfig().premiumCheckEnabled) {
            return null;
        }
        PremiumService.Status status = resolvePremiumStatus(player.getUuid());
        if (auth.premium && status == PremiumService.Status.OFFLINE) {
            return fail("error.premiumAccount");
        }
        if (!auth.premium && status == PremiumService.Status.PREMIUM) {
            return fail("error.offlineAccount");
        }
        // Stored premium UUID but this join is a different identity
        if (auth.premium && auth.uuid != null && !auth.uuid.isEmpty()
                && !auth.uuid.equalsIgnoreCase(player.getUuid().toString())
                && status != PremiumService.Status.PREMIUM) {
            return fail("error.premiumAccount");
        }
        return null;
    }

    private PremiumService.Status resolvePremiumStatus(java.util.UUID uuid) {
        plugin.getPremiumService().startCheck(uuid);
        try {
            int timeout = Math.max(1, plugin.getConfig().premiumCheckTimeoutSeconds);
            return plugin.getPremiumService().getResult(uuid)
                .get(timeout, TimeUnit.SECONDS);
        } catch (Exception e) {
            return plugin.getPremiumService().getResult(uuid)
                .getNow(PremiumService.Status.UNKNOWN);
        }
    }

    /** Marks the player authenticated and refreshes the stored session data. */
    public void completeLogin(PlayerRef player, PlayerAuth auth) {
        auth.lastIp = getIp(player);
        auth.lastLogin = System.currentTimeMillis();
        auth.uuid = player.getUuid().toString();
        plugin.getDataSource().updateAuth(auth);
        finishAuth(player);
        plugin.getLogger().atInfo().log("Player %s logged in", player.getUsername());
    }

    private void finishAuth(PlayerRef player) {
        plugin.getLimboService().markAuthenticated(player.getUuid());
        Ref<EntityStore> ref = player.getReference();
        if (ref != null && ref.isValid()) {
            LimboProtection.setInvulnerable(ref, ref.getStore(), false);
        }
    }

    /** Attempts session auto-login: same IP, same UUID and within the session timeout window. */
    public boolean tryAutoLogin(PlayerRef player) {
        AuthMeConfig config = plugin.getConfig();
        if (!config.sessionsEnabled) {
            return false;
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null || auth.lastIp == null || auth.lastLogin <= 0) {
            return false;
        }
        // Never resume a session across different client identities (offline ↔ premium UUID)
        if (auth.uuid != null && !auth.uuid.isEmpty()
                && !auth.uuid.equalsIgnoreCase(player.getUuid().toString())) {
            return false;
        }
        if (checkPremiumMismatch(player, auth) != null) {
            return false;
        }
        long sessionMillis = config.sessionTimeoutMinutes * 60_000L;
        boolean sameIp = auth.lastIp.equals(getIp(player));
        boolean fresh = System.currentTimeMillis() - auth.lastLogin < sessionMillis;
        if (sameIp && fresh) {
            completeLogin(player, auth);
            player.sendMessage(plugin.getMessages().get("login.sessionResumed"));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Account management
    // ------------------------------------------------------------------

    public Result logout(PlayerRef player) {
        if (!plugin.getLimboService().isAuthenticated(player.getUuid())
                || !plugin.getDataSource().isRegistered(player.getUsername())) {
            return fail("error.notLoggedIn");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth != null) {
            auth.lastLogin = 0;
            plugin.getDataSource().updateAuth(auth);
        }
        plugin.getLimboService().revokeAuthentication(player.getUuid());
        Ref<EntityStore> ref = player.getReference();
        if (ref != null && ref.isValid()) {
            LimboProtection.setInvulnerable(ref, ref.getStore(), true);
        }
        return ok("logout.success");
    }

    public Result changePassword(PlayerRef player, String oldPassword, String newPassword) {
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null) {
            return fail("error.notRegistered");
        }
        if (!plugin.getLimboService().isAuthenticated(player.getUuid())) {
            return fail("error.notLoggedIn");
        }
        if (!plugin.getPasswordSecurity().comparePassword(oldPassword, auth.password)) {
            return fail("error.wrongPassword");
        }
        Result validation = validatePassword(newPassword, player.getUsername());
        if (validation != null) {
            return validation;
        }
        auth.password = plugin.getPasswordSecurity().computeHash(newPassword);
        plugin.getDataSource().updateAuth(auth);
        return ok("changepassword.success");
    }

    public Result unregister(PlayerRef player, String password) {
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null) {
            return fail("error.notRegistered");
        }
        if (!plugin.getLimboService().isAuthenticated(player.getUuid())) {
            return fail("error.notLoggedIn");
        }
        if (!plugin.getPasswordSecurity().comparePassword(password, auth.password)) {
            return fail("error.wrongPassword");
        }
        plugin.getDataSource().removeAuth(auth.name);
        plugin.getLogger().atInfo().log("Player %s unregistered", player.getUsername());
        return ok("unregister.success");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** @return a failure result, or null when the password passes validation. */
    private Result validatePassword(String password, String playerName) {
        AuthMeConfig config = plugin.getConfig();
        if (password.length() < config.passwordMinLength) {
            return fail("error.passwordTooShort");
        }
        if (password.length() > config.passwordMaxLength) {
            return fail("error.passwordTooLong");
        }
        if (!config.isPasswordSafe(password) || password.equalsIgnoreCase(playerName)) {
            return fail("error.passwordUnsafe");
        }
        return null;
    }

    public String getIp(PlayerRef player) {
        try {
            SocketAddress address = player.getPacketHandler().getChannel().remoteAddress();
            if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
                return inet.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
            // connection may already be closed
        }
        return "";
    }
}
