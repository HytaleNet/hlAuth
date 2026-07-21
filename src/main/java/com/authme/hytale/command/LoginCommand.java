package com.authme.hytale.command;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.service.AuthService;
import com.authme.hytale.ui.AuthUi;
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

public final class LoginCommand extends AbstractPlayerCommand {

    private final AuthMePlugin plugin;
    private final RequiredArg<String> passwordArg;

    public LoginCommand(AuthMePlugin plugin) {
        super("login", "Log in with your password");
        this.plugin = plugin;
        this.passwordArg = withRequiredArg("password", "Your account password", ArgTypes.STRING);
        addAliases("l", "log");
        requirePermission("hlauth.player.login");
        setPermissionGroups("hytale:Adventurer");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        AuthService.Result result = plugin.getAuthService().login(playerRef, context.get(passwordArg));
        context.sendMessage(result.message());
        if (result.success()) {
            AuthUi.close(store, ref);
        }
    }
}
