package com.authme.hytale.command;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.service.AuthService;
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

public final class ChangePasswordCommand extends AbstractPlayerCommand {

    private final AuthMePlugin plugin;
    private final RequiredArg<String> oldPasswordArg;
    private final RequiredArg<String> newPasswordArg;

    public ChangePasswordCommand(AuthMePlugin plugin) {
        super("changepassword", "Change your account password");
        this.plugin = plugin;
        this.oldPasswordArg = withRequiredArg("oldPassword", "Your current password", ArgTypes.STRING);
        this.newPasswordArg = withRequiredArg("newPassword", "Your new password", ArgTypes.STRING);
        addAliases("changepass", "cp");
        requirePermission("hlauth.player.changepassword");
        setPermissionGroups("hytale:Adventurer");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        AuthService.Result result = plugin.getAuthService()
            .changePassword(playerRef, context.get(oldPasswordArg), context.get(newPasswordArg));
        context.sendMessage(result.message());
    }
}
