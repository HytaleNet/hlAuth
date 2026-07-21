package com.authme.hytale.command;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.service.AuthService;
import com.authme.hytale.ui.LoginPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class LogoutCommand extends AbstractPlayerCommand {

    private final AuthMePlugin plugin;

    public LogoutCommand(AuthMePlugin plugin) {
        super("logout", "Log out of your account");
        this.plugin = plugin;
        requirePermission("hlauth.player.logout");
        setPermissionGroups("hytale:Adventurer");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        AuthService.Result result = plugin.getAuthService().logout(playerRef);
        context.sendMessage(result.message());
        if (result.success() && plugin.getConfig().useUiMenus) {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player != null) {
                LoginPage page = new LoginPage(playerRef, plugin);
                player.getPageManager().openCustomPage(ref, store, page);
                page.refreshTexts();
            }
        }
    }
}
