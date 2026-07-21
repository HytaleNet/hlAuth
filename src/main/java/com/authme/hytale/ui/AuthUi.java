package com.authme.hytale.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Helpers for opening / closing AuthMe custom pages. */
public final class AuthUi {

    /** Delay so the client applies Page.None before the disconnect packet. */
    private static final long CLOSE_BEFORE_KICK_MS = 100;

    private AuthUi() {
    }

    /**
     * Closes any open custom page. Must be called on the player's world thread
     * (e.g. from {@code AbstractPlayerCommand.execute} or {@code World.execute}).
     */
    public static void close(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (store == null || ref == null || !ref.isValid()) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    /**
     * Closes the custom UI on the world thread, then disconnects after a short delay.
     * Safe to call from command threads / schedulers (avoids Store.assertThread).
     */
    public static void closeAndDisconnect(@Nullable PlayerRef playerRef, Message reason) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }

        Runnable kick = () -> {
            if (playerRef.isValid()) {
                playerRef.getPacketHandler().disconnect(reason);
            }
        };

        World world = resolveWorld(playerRef);
        if (world != null) {
            world.execute(() -> {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null && ref.isValid()) {
                    close(ref.getStore(), ref);
                }
                HytaleServer.SCHEDULED_EXECUTOR.schedule(
                    kick, CLOSE_BEFORE_KICK_MS, TimeUnit.MILLISECONDS);
            });
        } else {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(
                kick, CLOSE_BEFORE_KICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Resolves the player's world without touching Store from a foreign thread.
     * Uses {@link PlayerRef#getWorldUuid()} + {@link Universe#getWorld(UUID)}.
     */
    @Nullable
    private static World resolveWorld(PlayerRef playerRef) {
        try {
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid != null) {
                World world = Universe.get().getWorld(worldUuid);
                if (world != null) {
                    return world;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /** True when the player currently has an AuthMe login/register/denied page open. */
    public static boolean isAuthPageOpen(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (store == null || ref == null || !ref.isValid()) {
            return false;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return false;
        }
        CustomUIPage page = player.getPageManager().getCustomPage();
        return page instanceof LoginPage
            || page instanceof RegisterPage
            || page instanceof AccessDeniedPage;
    }
}
