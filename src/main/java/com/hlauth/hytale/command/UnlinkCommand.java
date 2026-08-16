package com.hlauth.hytale.command;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.messenger.MessengerKind;
import com.hlauth.hytale.service.AuthService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** {@code /unlink <telegram|discord>} */
public final class UnlinkCommand extends AbstractPlayerCommand {

    private final HlAuthPlugin plugin;
    private final RequiredArg<String> serviceArg;

    public UnlinkCommand(HlAuthPlugin plugin) {
        super("unlink", "Unlink Telegram or Discord");
        this.plugin = plugin;
        this.serviceArg = withRequiredArg("service", "telegram or discord", ArgTypes.STRING);
        requirePermission("hlauth.player.unlink");
        setPermissionGroups("hytale:Adventurer");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        MessengerKind kind = MessengerKind.fromInput(context.get(serviceArg));
        if (kind == null) {
            context.sendMessage(plugin.getMessages().get("messenger.unknownService"));
            return;
        }
        AuthService.Result result = plugin.getMessengerService().unlink(playerRef, kind);
        context.sendMessage(result.message());
    }
}
