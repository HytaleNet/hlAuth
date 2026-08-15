package com.authme.hytale.listener;

import com.authme.hytale.AuthMePlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Cancels damage taken by limbo players, and damage dealt by them.
 */
public final class LimboDamageSystem extends DamageEventSystem {

    private final AuthMePlugin plugin;
    private final Query<EntityStore> query = Query.any();

    public LimboDamageSystem(AuthMePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    @Nullable
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage event) {
        PlayerRef victim = chunk.getComponent(index, PlayerRef.getComponentType());
        if (victim != null && plugin.getLimboService().isInLimbo(victim.getUuid())) {
            event.setCancelled(true);
            return;
        }
        if (event.getSource() instanceof Damage.EntitySource entitySource) {
            Ref<EntityStore> attacker = entitySource.getRef();
            if (attacker != null && attacker.isValid()) {
                PlayerRef attackerRef = store.getComponent(attacker, PlayerRef.getComponentType());
                if (attackerRef != null && plugin.getLimboService().isInLimbo(attackerRef.getUuid())) {
                    event.setCancelled(true);
                }
            }
        }
    }
}
