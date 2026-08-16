package com.hlauth.hytale.service;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

/**
 * Restricts unauthenticated players: vanilla {@link Invulnerable} (cannot be hurt)
 * and {@link Frozen} (cannot move). Applied on join and cleared after login.
 */
public final class LimboProtection {

    private LimboProtection() {
    }

    public static void apply(@Nullable Ref<EntityStore> ref,
                             @Nullable Store<EntityStore> store,
                             boolean inLimbo) {
        if (ref == null || store == null || !ref.isValid()) {
            return;
        }
        setMarker(store, ref, Invulnerable.getComponentType(), Invulnerable.INSTANCE, inLimbo);
        setMarker(store, ref, Frozen.getComponentType(), Frozen.get(), inLimbo);
    }

    /** Same markers on a not-yet-spawned holder (PlayerConnectEvent). */
    public static void apply(@Nullable Holder<EntityStore> holder, boolean inLimbo) {
        if (holder == null) {
            return;
        }
        try {
            setHolderMarker(holder, Invulnerable.getComponentType(), Invulnerable.INSTANCE, inLimbo);
            setHolderMarker(holder, Frozen.getComponentType(), Frozen.get(), inLimbo);
        } catch (Exception ignored) {
            // Entity module / store may not be ready on very early connect
        }
    }

    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> void setMarker(
            Store<EntityStore> store,
            Ref<EntityStore> ref,
            com.hypixel.hytale.component.ComponentType<EntityStore, T> type,
            T instance,
            boolean enable) {
        if (enable) {
            if (store.getComponent(ref, type) == null) {
                store.putComponent(ref, type, instance);
            }
        } else {
            store.tryRemoveComponent(ref, type);
        }
    }

    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> void setHolderMarker(
            Holder<EntityStore> holder,
            com.hypixel.hytale.component.ComponentType<EntityStore, T> type,
            T instance,
            boolean enable) {
        if (enable) {
            if (holder.getComponent(type) == null) {
                holder.putComponent(type, instance);
            }
        } else {
            holder.tryRemoveComponent(type);
        }
    }
}
