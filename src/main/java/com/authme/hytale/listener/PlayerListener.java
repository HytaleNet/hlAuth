package com.authme.hytale.listener;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.data.PlayerAuth;
import com.authme.hytale.service.LimboProtection;
import com.authme.hytale.service.PremiumService;
import com.authme.hytale.ui.AccessDeniedPage;
import com.authme.hytale.ui.AuthUi;
import com.authme.hytale.ui.LoginPage;
import com.authme.hytale.ui.RegisterPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Hooks the authentication flow into the player lifecycle:
 * <ul>
 *   <li>connect: limbo + optional premium lookup</li>
 *   <li>ready: premium gate / session / UI (with delay + refresh)</li>
 *   <li>chat: blocked while in limbo</li>
 *   <li>disconnect: cleanup</li>
 * </ul>
 */
public final class PlayerListener {

    private final AuthMePlugin plugin;

    public PlayerListener(AuthMePlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        var events = plugin.getEventRegistry();

        events.register(PlayerConnectEvent.class, this::onConnect);
        events.registerGlobal(PlayerReadyEvent.class, this::onReady);
        events.register(PlayerDisconnectEvent.class, this::onDisconnect);

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
        if (plugin.getConfig().premiumCheckEnabled) {
            plugin.getPremiumService().startCheck(playerRef.getUuid());
        }
    }

    private void onReady(PlayerReadyEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        Player player = event.getPlayer();
        if (playerRef == null || player == null) {
            return;
        }
        if (plugin.getLimboService().isAuthenticated(playerRef.getUuid())) {
            return;
        }

        // God-mode while waiting for login / on the denied plaque
        LimboProtection.setInvulnerable(ref, store, true);

        World world = store.getExternalData() instanceof EntityStore entityStore
            ? entityStore.getWorld()
            : null;
        if (world == null) {
            beginAuthFlow(playerRef, player, ref, store);
            return;
        }

        if (plugin.getConfig().premiumCheckEnabled) {
            UUID uuid = playerRef.getUuid();
            plugin.getPremiumService().getResult(uuid).thenAccept(status ->
                world.execute(() -> handlePremiumGate(playerRef, player, ref, store, status)));
        } else {
            beginAuthFlow(playerRef, player, ref, store);
        }
    }

    private void handlePremiumGate(PlayerRef playerRef, Player player,
                                   Ref<EntityStore> ref, Store<EntityStore> store,
                                   PremiumService.Status status) {
        if (!playerRef.isValid() || plugin.getLimboService().isAuthenticated(playerRef.getUuid())) {
            return;
        }

        PlayerAuth auth = plugin.getDataSource().getAuth(playerRef.getUsername());
        if (auth != null) {
            if (auth.premium && status == PremiumService.Status.OFFLINE) {
                denyAccess(playerRef, player, ref, store, AccessDeniedPage.Reason.PREMIUM_ACCOUNT);
                return;
            }
            if (!auth.premium && status == PremiumService.Status.PREMIUM) {
                denyAccess(playerRef, player, ref, store, AccessDeniedPage.Reason.OFFLINE_ACCOUNT);
                return;
            }
            if (auth.premium && status == PremiumService.Status.PREMIUM) {
                plugin.getAuthService().completeLogin(playerRef, auth);
                playerRef.sendMessage(plugin.getMessages().get("login.premium"));
                return;
            }
        } else if (status == PremiumService.Status.PREMIUM
                && plugin.getConfig().premiumAutoRegister) {
            if (plugin.getAuthService().registerPremiumAuto(playerRef)) {
                playerRef.sendMessage(plugin.getMessages().get("register.premium"));
                return;
            }
        }

        beginAuthFlow(playerRef, player, ref, store);
    }

    /**
     * Blocks a mismatched premium/offline join: either kicks immediately
     * ({@code premiumKickEnabled}) or shows a non-closable plaque with Exit.
     */
    private void denyAccess(PlayerRef playerRef, Player player,
                            Ref<EntityStore> ref, Store<EntityStore> store,
                            AccessDeniedPage.Reason reason) {
        Message message = plugin.getMessages().get(reason.messageKey());
        if (plugin.getConfig().premiumKickEnabled) {
            plugin.getLimboService().removeFromLimbo(playerRef.getUuid());
            AuthUi.closeAndDisconnect(playerRef, message);
            return;
        }

        // Keep the player in limbo (no chat / no auth) and show the plaque
        LimboProtection.setInvulnerable(ref, store, true);
        int delayMs = Math.max(0, plugin.getConfig().uiOpenDelayMs);
        World world = store.getExternalData() instanceof EntityStore entityStore
            ? entityStore.getWorld()
            : null;
        Runnable open = () -> {
            if (!playerRef.isValid() || !ref.isValid()) {
                return;
            }
            AccessDeniedPage page = new AccessDeniedPage(playerRef, plugin, reason);
            player.getPageManager().openCustomPage(ref, store, page);
            scheduleUiRefresh(store, page::refreshTexts);
            playerRef.sendMessage(message);
        };
        if (delayMs > 0 && world != null) {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> world.execute(open), delayMs, TimeUnit.MILLISECONDS);
        } else {
            open.run();
        }
    }

    private void beginAuthFlow(PlayerRef playerRef, Player player,
                               Ref<EntityStore> ref, Store<EntityStore> store) {
        if (plugin.getAuthService().tryAutoLogin(playerRef)) {
            return;
        }

        boolean registered = plugin.getDataSource().isRegistered(playerRef.getUsername());
        if (!plugin.getConfig().useUiMenus) {
            playerRef.sendMessage(plugin.getMessages().get(registered
                ? "login.reminder"
                : "register.reminder"));
            return;
        }

        int delayMs = Math.max(0, plugin.getConfig().uiOpenDelayMs);
        World world = store.getExternalData() instanceof EntityStore entityStore
            ? entityStore.getWorld()
            : null;

        Runnable open = () -> openAuthUi(playerRef, player, ref, store, registered);
        if (delayMs > 0 && world != null) {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> world.execute(open), delayMs, TimeUnit.MILLISECONDS);
        } else {
            open.run();
        }
    }

    private void openAuthUi(PlayerRef playerRef, Player player,
                            Ref<EntityStore> ref, Store<EntityStore> store,
                            boolean registered) {
        if (!playerRef.isValid() || !ref.isValid()
                || plugin.getLimboService().isAuthenticated(playerRef.getUuid())) {
            return;
        }

        if (registered) {
            LoginPage page = new LoginPage(playerRef, plugin);
            player.getPageManager().openCustomPage(ref, store, page);
            scheduleUiRefresh(store, page::refreshTexts);
        } else {
            RegisterPage page = new RegisterPage(playerRef, plugin);
            player.getPageManager().openCustomPage(ref, store, page);
            scheduleUiRefresh(store, page::refreshTexts);
        }
    }

    /** Second paint shortly after open — fixes skewed container textures on first frame. */
    private void scheduleUiRefresh(Store<EntityStore> store, Runnable refresh) {
        World world = store.getExternalData() instanceof EntityStore entityStore
            ? entityStore.getWorld()
            : null;
        if (world == null) {
            return;
        }
        HytaleServer.SCHEDULED_EXECUTOR.schedule(
            () -> world.execute(refresh), 200, TimeUnit.MILLISECONDS);
    }

    private PlayerChatEvent onChat(PlayerChatEvent event) {
        PlayerRef sender = event.getSender();
        if (sender != null && plugin.getLimboService().isInLimbo(sender.getUuid())) {
            event.setCancelled(true);
            boolean registered = plugin.getDataSource().isRegistered(sender.getUsername());
            sender.sendMessage(plugin.getMessages().get(registered
                ? "login.reminder"
                : "register.reminder"));
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

        if (wasAuthenticated) {
            PlayerAuth auth = plugin.getDataSource().getAuth(playerRef.getUsername());
            if (auth != null && auth.lastLogin > 0) {
                auth.lastLogin = System.currentTimeMillis();
                plugin.getDataSource().updateAuth(auth);
            }
        }
    }
}
