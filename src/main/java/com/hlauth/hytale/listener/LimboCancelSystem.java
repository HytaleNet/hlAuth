package com.hlauth.hytale.listener;

import com.hlauth.hytale.HlAuthPlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.ICancellableEcsEvent;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.InteractivelyPickupItemEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Cancels an ECS gameplay event when the acting entity is a player still in auth limbo.
 *
 * <p>Hytale's component registry allows only one system instance per concrete class,
 * so each event type has its own subclass.</p>
 */
public abstract class LimboCancelSystem<E extends EcsEvent & ICancellableEcsEvent>
        extends EntityEventSystem<EntityStore, E> {

    private final HlAuthPlugin plugin;
    private final Query<EntityStore> query = PlayerRef.getComponentType();

    protected LimboCancelSystem(HlAuthPlugin plugin, Class<E> eventType) {
        super(eventType);
        this.plugin = plugin;
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
                       @Nonnull E event) {
        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef != null && plugin.getLimboService().isInLimbo(playerRef.getUuid())) {
            event.setCancelled(true);
        }
    }

    public static final class BreakBlock extends LimboCancelSystem<BreakBlockEvent> {
        public BreakBlock(HlAuthPlugin plugin) {
            super(plugin, BreakBlockEvent.class);
        }
    }

    public static final class PlaceBlock extends LimboCancelSystem<PlaceBlockEvent> {
        public PlaceBlock(HlAuthPlugin plugin) {
            super(plugin, PlaceBlockEvent.class);
        }
    }

    public static final class PickupItem extends LimboCancelSystem<InteractivelyPickupItemEvent> {
        public PickupItem(HlAuthPlugin plugin) {
            super(plugin, InteractivelyPickupItemEvent.class);
        }
    }

    public static final class DropItem extends LimboCancelSystem<DropItemEvent> {
        public DropItem(HlAuthPlugin plugin) {
            super(plugin, DropItemEvent.class);
        }
    }

    public static final class DropItemRequest extends LimboCancelSystem<DropItemEvent.PlayerRequest> {
        public DropItemRequest(HlAuthPlugin plugin) {
            super(plugin, DropItemEvent.PlayerRequest.class);
        }
    }

    public static final class UseBlockPre extends LimboCancelSystem<UseBlockEvent.Pre> {
        public UseBlockPre(HlAuthPlugin plugin) {
            super(plugin, UseBlockEvent.Pre.class);
        }
    }
}
