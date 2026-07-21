package com.authme.hytale.service;

import com.authme.hytale.AuthMePlugin;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tracks players that have joined but not authenticated yet ("limbo"),
 * and players that completed auth in the current session.
 *
 * <p>Unauthenticated players cannot chat and are kicked after a timeout.
 * The authenticated set survives spurious re-connect/ready events (e.g. after
 * death/respawn) so login state is not wiped mid-session.</p>
 */
public final class LimboService {

    private static final class LimboEntry {
        final long joinTime = System.currentTimeMillis();
        volatile long lastReminder = System.currentTimeMillis();
        volatile int loginTries = 0;
    }

    private final AuthMePlugin plugin;
    private final Map<UUID, LimboEntry> limbo = new ConcurrentHashMap<>();
    /** Players that successfully authenticated this session (until disconnect/logout). */
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> timeoutTask;

    public LimboService(AuthMePlugin plugin) {
        this.plugin = plugin;
    }

    public void addToLimbo(UUID uuid) {
        // Death/respawn can re-fire connect-like events — never re-limbo a logged-in session
        if (authenticated.contains(uuid)) {
            return;
        }
        limbo.put(uuid, new LimboEntry());
    }

    public void removeFromLimbo(UUID uuid) {
        limbo.remove(uuid);
    }

    /** Marks the player as authenticated for this connection session. */
    public void markAuthenticated(UUID uuid) {
        limbo.remove(uuid);
        authenticated.add(uuid);
    }

    /** Clears session auth (logout) and puts the player back into limbo. */
    public void revokeAuthentication(UUID uuid) {
        authenticated.remove(uuid);
        limbo.put(uuid, new LimboEntry());
    }

    /** Full cleanup on disconnect. */
    public void clearSession(UUID uuid) {
        limbo.remove(uuid);
        authenticated.remove(uuid);
    }

    /** @return true when the player has passed authentication (or never had to). */
    public boolean isAuthenticated(UUID uuid) {
        if (authenticated.contains(uuid)) {
            return true;
        }
        return !limbo.containsKey(uuid);
    }

    public boolean isInLimbo(UUID uuid) {
        return limbo.containsKey(uuid);
    }

    /** True when the player logged in during this connection (not merely "not in limbo"). */
    public boolean hasSessionAuth(UUID uuid) {
        return authenticated.contains(uuid);
    }

    /** @return the new number of failed login attempts of this player. */
    public int incrementLoginTries(UUID uuid) {
        LimboEntry entry = limbo.get(uuid);
        if (entry == null) {
            return 0;
        }
        return ++entry.loginTries;
    }

    public void startTimeoutTask() {
        timeoutTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
            this::tick, 5, 5, TimeUnit.SECONDS);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long timeoutMillis = plugin.getConfig().timeoutSeconds * 1000L;
        long reminderMillis = plugin.getConfig().messageIntervalSeconds * 1000L;

        for (Map.Entry<UUID, LimboEntry> mapEntry : limbo.entrySet()) {
            UUID uuid = mapEntry.getKey();
            LimboEntry entry = mapEntry.getValue();
            PlayerRef player = Universe.get().getPlayer(uuid);
            if (player == null || !player.isValid()) {
                limbo.remove(uuid);
                continue;
            }
            if (timeoutMillis > 0 && now - entry.joinTime > timeoutMillis) {
                limbo.remove(uuid);
                com.authme.hytale.ui.AuthUi.closeAndDisconnect(
                    player, plugin.getMessages().get("error.timeout"));
                continue;
            }
            if (reminderMillis > 0 && now - entry.lastReminder > reminderMillis) {
                entry.lastReminder = now;
                boolean registered = plugin.getDataSource().isRegistered(player.getUsername());
                player.sendMessage(plugin.getMessages().get(registered
                    ? "login.reminder"
                    : "register.reminder"));
            }
        }
    }

    public void stop() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
        limbo.clear();
        authenticated.clear();
    }
}
