package com.authme.hytale.service;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

/** Applies / removes the vanilla {@link Invulnerable} marker for limbo players. */
public final class LimboProtection {

    private LimboProtection() {
    }

    public static void setInvulnerable(@Nullable Ref<EntityStore> ref,
                                       @Nullable Store<EntityStore> store,
                                       boolean invulnerable) {
        if (ref == null || store == null || !ref.isValid()) {
            return;
        }
        var type = Invulnerable.getComponentType();
        if (invulnerable) {
            if (store.getComponent(ref, type) == null) {
                store.putComponent(ref, type, Invulnerable.INSTANCE);
            }
        } else {
            store.tryRemoveComponent(ref, type);
        }
    }
}
