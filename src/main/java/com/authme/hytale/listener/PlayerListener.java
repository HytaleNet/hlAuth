package com.authme.hytale.listener;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.data.PlayerAuth;
import com.authme.hytale.service.AuthService;
import com.authme.hytale.service.LimboProtection;
import com.authme.hytale.service.PremiumService;
import com.authme.hytale.ui.AccessDeniedPage;
import com.authme.hytale.ui.AuthUi;
import com.authme.hytale.ui.LoginPage;
import com.authme.hytale.ui.RegisterPage;
import com.authme.hytale.ui.TwoFactorPages;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Hooks the authentication flow into the player lifecycle:
 * <ul>
 *   <li>connect: limbo + freeze + optional premium lookup</li>
 *   <li>ready: premium gate / session / UI (resolved live, with retries)</li>
 *   <li>interact/chat: blocked while in limbo</li>
 *   <li>disconnect: cleanup</li>
 * </ul>
 */
public final class PlayerListener {

    private static final int[] UI_RETRY_MS = {0, 400, 1200, 2500};

    private final AuthMePlugin plugin;

    public PlayerListener(AuthMePlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        var events = plugin.getEventRegistry();

        events.register(PlayerConnectEvent.class, this::onConnect);
        events.registerGlobal(PlayerReadyEvent.class, this::onReady);
        events.register(PlayerDisconnectEvent.class, this::onDisconnect);
        events.register(EventPriority.FIRST, PlayerMouseButtonEvent.class, this::onMouseButton);

        if (plugin.getConfig().protectChat) {
            events.registerAsyncGlobal(EventPriority.FIRST, PlayerChatEvent.class,
                future -> future.thenApply(this::onChat));
        }
    }

    private void onConnect(PlayerConnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        boolean registered = plugin.getDataSource().isRegistered(playerRef.getUsername());
        if (!registered && !plugin.getConfig().registrationEnabled) {
            return;
        }
        plugin.getLimboService().addToLimbo(playerRef.getUuid());
        LimboProtection.apply(event.getHolder(), true);
        if (plugin.getConfig().premiumCheckEnabled) {
            plugin.getPremiumService().startCheck(playerRef.getUuid());
        }
    }

    private void onReady(PlayerReadyEvent event) {
        PlayerRef playerRef = playerRefFromEntity(event.getPlayerRef());
        if (playerRef == null) {
            return;
        }
        World world = AuthUi.resolveWorld(playerRef);
        Runnable run = () -> {
            AuthUi.LivePlayer live = AuthUi.resolveLive(playerRef);
            if (live != null) {
                handleReady(live);
            } else {
                scheduleReadyRetry(playerRef);
            }
        };
        if (world != null) {
            world.execute(run);
        } else {
            run.run();
        }
    }

    private void scheduleReadyRetry(PlayerRef playerRef) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            World world = AuthUi.resolveWorld(playerRef);
            Runnable run = () -> {
                AuthUi.LivePlayer live = AuthUi.resolveLive(playerRef);
                if (live != null) {
                    handleReady(live);
                }
            };
            if (world != null) {
                world.execute(run);
            } else {
                run.run();
            }
        }, 400, TimeUnit.MILLISECONDS);
    }

    private void handleReady(AuthUi.LivePlayer live) {
        PlayerRef playerRef = live.playerRef();
        if (plugin.getLimboService().isAuthenticated(playerRef.getUuid())) {
            return;
        }

        LimboProtection.apply(live.ref(), live.store(), true);

        if (plugin.getConfig().premiumCheckEnabled) {
            UUID uuid = playerRef.getUuid();
            World world = AuthUi.resolveWorld(playerRef);
            plugin.getPremiumService().getResult(uuid).thenAccept(status -> {
                Runnable gate = () -> {
                    AuthUi.LivePlayer now = AuthUi.resolveLive(playerRef);
                    if (now != null) {
                        handlePremiumGate(now, status);
                    }
                };
                if (world != null) {
                    world.execute(gate);
                } else {
                    gate.run();
                }
            });
        } else {
            beginAuthFlow(playerRef);
        }
    }

    private void handlePremiumGate(AuthUi.LivePlayer live, PremiumService.Status status) {
        PlayerRef playerRef = live.playerRef();
        if (!playerRef.isValid() || plugin.getLimboService().isAuthenticated(playerRef.getUuid())) {
            return;
        }

        PlayerAuth auth = plugin.getDataSource().getAuth(playerRef.getUsername());
        if (auth != null) {
            if (auth.premium && status == PremiumService.Status.OFFLINE) {
                denyAccess(playerRef, AccessDeniedPage.Reason.PREMIUM_ACCOUNT);
                return;
            }
            if (!auth.premium && status == PremiumService.Status.PREMIUM) {
                denyAccess(playerRef, AccessDeniedPage.Reason.OFFLINE_ACCOUNT);
                return;
            }
            if (auth.premium && status == PremiumService.Status.PREMIUM) {
                AuthService.Result result = plugin.getAuthService()
                    .continueAfterCredential(playerRef, auth, "login.premium");
                playerRef.sendMessage(result.message());
                if (result.success()) {
                    return;
                }
                TwoFactorPages.open(plugin, playerRef, result);
                return;
            }
        } else if (status == PremiumService.Status.PREMIUM
                && plugin.getConfig().premiumAutoRegister) {
            AuthService.Result result = plugin.getAuthService().registerPremiumAuto(playerRef);
            if (result.status() != AuthService.Result.Status.FAILURE) {
                playerRef.sendMessage(result.message());
                if (!result.success()) {
                    TwoFactorPages.open(plugin, playerRef, result);
                }
                return;
            }
        }

        beginAuthFlow(playerRef);
    }

    private void denyAccess(PlayerRef playerRef, AccessDeniedPage.Reason reason) {
        Message message = plugin.getMessages().get(reason.messageKey());
        if (plugin.getConfig().premiumKickEnabled) {
            plugin.getLimboService().removeFromLimbo(playerRef.getUuid());
            AuthUi.closeAndDisconnect(playerRef, message);
            return;
        }

        scheduleUiAttempts(playerRef, live -> {
            LimboProtection.apply(live.ref(), live.store(), true);
            AccessDeniedPage page = new AccessDeniedPage(live.playerRef(), plugin, reason);
            live.player().getPageManager().openCustomPage(live.ref(), live.store(), page);
            page.refreshTexts();
            live.playerRef().sendMessage(message);
        });
    }

    private void beginAuthFlow(PlayerRef playerRef) {
        AuthService.Result session = plugin.getAuthService().tryAutoLogin(playerRef);
        if (session != null) {
            playerRef.sendMessage(session.message());
            if (session.success()) {
                return;
            }
            TwoFactorPages.open(plugin, playerRef, session);
            return;
        }

        boolean registered = plugin.getDataSource().isRegistered(playerRef.getUsername());
        if (!plugin.getConfig().useUiMenus) {
            playerRef.sendMessage(plugin.getMessages().get(registered
                ? "login.reminder"
                : "register.reminder"));
            return;
        }

        scheduleUiAttempts(playerRef, live -> openAuthUi(live, registered));
    }

    /**
     * Opens UI after resolving the live entity (the Ready-event Ref can go stale
     * after game updates). Retries a few times if the page did not stick.
     */
    private void scheduleUiAttempts(PlayerRef playerRef, LiveUiOpener opener) {
        int baseDelay = Math.max(0, plugin.getConfig().uiOpenDelayMs);
        for (int extra : UI_RETRY_MS) {
            int delay = baseDelay + extra;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> attemptOpen(playerRef, opener),
                delay, TimeUnit.MILLISECONDS);
        }
    }

    private void attemptOpen(PlayerRef playerRef, LiveUiOpener opener) {
        if (playerRef == null || !playerRef.isValid()
                || plugin.getLimboService().isAuthenticated(playerRef.getUuid())) {
            return;
        }
        World world = AuthUi.resolveWorld(playerRef);
        Runnable run = () -> {
            if (!playerRef.isValid() || plugin.getLimboService().isAuthenticated(playerRef.getUuid())) {
                return;
            }
            AuthUi.LivePlayer live = AuthUi.resolveLive(playerRef);
            if (live == null) {
                return;
            }
            if (AuthUi.isAuthPageOpen(live.store(), live.ref())) {
                return;
            }
            LimboProtection.apply(live.ref(), live.store(), true);
            try {
                opener.open(live);
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e)
                    .log("Failed to open auth UI for %s", playerRef.getUsername());
            }
        };
        if (world != null) {
            world.execute(run);
        } else {
            run.run();
        }
    }

    private void openAuthUi(AuthUi.LivePlayer live, boolean registered) {
        if (registered) {
            LoginPage page = new LoginPage(live.playerRef(), plugin);
            live.player().getPageManager().openCustomPage(live.ref(), live.store(), page);
            page.refreshTexts();
        } else {
            RegisterPage page = new RegisterPage(live.playerRef(), plugin);
            live.player().getPageManager().openCustomPage(live.ref(), live.store(), page);
            page.refreshTexts();
        }
        plugin.getLogger().atInfo().log(
            "Opened %s UI for %s", registered ? "login" : "register", live.playerRef().getUsername());
    }

    private void onMouseButton(PlayerMouseButtonEvent event) {
        PlayerRef playerRef = event.getPlayerRefComponent();
        if (playerRef != null && plugin.getLimboService().isInLimbo(playerRef.getUuid())) {
            event.setCancelled(true);
        }
    }

    @Nullable
    private static PlayerRef playerRefFromEntity(Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return ref.getStore().getComponent(ref, PlayerRef.getComponentType());
    }

    private PlayerChatEvent onChat(PlayerChatEvent event) {
        PlayerRef sender = event.getSender();
        if (sender != null && plugin.getLimboService().isInLimbo(sender.getUuid())) {
            event.setCancelled(true);
            String totpKey = plugin.getTotpService().reminderKey(sender.getUuid());
            if (totpKey != null) {
                sender.sendMessage(plugin.getMessages().get(totpKey));
            } else {
                boolean registered = plugin.getDataSource().isRegistered(sender.getUsername());
                sender.sendMessage(plugin.getMessages().get(registered
                    ? "login.reminder"
                    : "register.reminder"));
            }
        }
        return event;
    }

    private void onDisconnect(PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        if (playerRef == null) {
            return;
        }
        UUID uuid = playerRef.getUuid();
        boolean wasAuthenticated = plugin.getLimboService().hasSessionAuth(uuid);
        plugin.getLimboService().clearSession(uuid);
        plugin.getPremiumService().clear(uuid);
        plugin.getTotpService().clear(uuid);

        if (wasAuthenticated) {
            PlayerAuth auth = plugin.getDataSource().getAuth(playerRef.getUsername());
            if (auth != null && auth.lastLogin > 0) {
                auth.lastLogin = System.currentTimeMillis();
                plugin.getDataSource().updateAuth(auth);
            }
        }
    }

    @FunctionalInterface
    private interface LiveUiOpener {
        void open(AuthUi.LivePlayer live);
    }
}
