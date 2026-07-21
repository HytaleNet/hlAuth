package com.authme.hytale.command;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.data.PlayerAuth;
import com.authme.hytale.ui.AuthUi;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.UUID;

/**
 * Admin command collection: {@code /hlauth <register|unregister|changepassword|info|reload>}.
 * Alias {@code /authme} kept for convenience. Requires {@code hlauth.admin}.
 */
public final class AuthMeAdminCommand extends CommandBase {

    private final AuthMePlugin plugin;

    public AuthMeAdminCommand(AuthMePlugin plugin) {
        super("hlauth", "hlAuth administration");
        this.plugin = plugin;
        addAliases("authme");
        requirePermission("hlauth.admin");
        addSubCommand(new RegisterSub(plugin));
        addSubCommand(new UnregisterSub(plugin));
        addSubCommand(new ChangePasswordSub(plugin));
        addSubCommand(new InfoSub(plugin));
        addSubCommand(new ReloadSub(plugin));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(plugin.getMessages().get("admin.usage"));
    }

    // ------------------------------------------------------------------

    private static final class RegisterSub extends CommandBase {
        private final AuthMePlugin plugin;
        private final RequiredArg<String> nameArg;
        private final RequiredArg<String> passwordArg;

        RegisterSub(AuthMePlugin plugin) {
            super("register", "Register an account for a player");
            this.plugin = plugin;
            this.nameArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
            this.passwordArg = withRequiredArg("password", "Password", ArgTypes.STRING);
            requirePermission("hlauth.admin.register");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String name = context.get(nameArg);
            if (plugin.getDataSource().isRegistered(name)) {
                context.sendMessage(plugin.getMessages().get("error.alreadyRegistered"));
                return;
            }
            String hash = plugin.getPasswordSecurity().computeHash(context.get(passwordArg));
            plugin.getDataSource().saveAuth(new PlayerAuth(name, null, hash, ""));
            context.sendMessage(plugin.getMessages().get("admin.registered", "player", name));
        }
    }

    private static final class UnregisterSub extends CommandBase {
        private final AuthMePlugin plugin;
        private final RequiredArg<String> nameArg;

        UnregisterSub(AuthMePlugin plugin) {
            super("unregister", "Delete a player's account");
            this.plugin = plugin;
            this.nameArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
            requirePermission("hlauth.admin.unregister");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String name = context.get(nameArg);
            PlayerAuth auth = plugin.getDataSource().getAuth(name);
            if (auth == null) {
                context.sendMessage(plugin.getMessages().get("error.notRegistered"));
                return;
            }
            plugin.getDataSource().removeAuth(name);
            // Kick online player if present
            if (auth.uuid != null && !auth.uuid.isEmpty()) {
                try {
                    PlayerRef online = Universe.get().getPlayer(UUID.fromString(auth.uuid));
                    if (online != null && online.isValid()) {
                        AuthUi.closeAndDisconnect(online,
                            plugin.getMessages().get("unregister.success"));
                    }
                } catch (IllegalArgumentException ignored) {
                    // stored uuid may be invalid for imported accounts
                }
            }
            context.sendMessage(plugin.getMessages().get("admin.unregistered", "player", name));
        }
    }

    private static final class ChangePasswordSub extends CommandBase {
        private final AuthMePlugin plugin;
        private final RequiredArg<String> nameArg;
        private final RequiredArg<String> passwordArg;

        ChangePasswordSub(AuthMePlugin plugin) {
            super("changepassword", "Change a player's password");
            this.plugin = plugin;
            this.nameArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
            this.passwordArg = withRequiredArg("password", "New password", ArgTypes.STRING);
            requirePermission("hlauth.admin.changepassword");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String name = context.get(nameArg);
            PlayerAuth auth = plugin.getDataSource().getAuth(name);
            if (auth == null) {
                context.sendMessage(plugin.getMessages().get("error.notRegistered"));
                return;
            }
            auth.password = plugin.getPasswordSecurity().computeHash(context.get(passwordArg));
            plugin.getDataSource().updateAuth(auth);
            context.sendMessage(plugin.getMessages().get("admin.passwordChanged", "player", name));
        }
    }

    private static final class InfoSub extends CommandBase {
        private final AuthMePlugin plugin;
        private final RequiredArg<String> nameArg;

        InfoSub(AuthMePlugin plugin) {
            super("info", "Show account info of a player");
            this.plugin = plugin;
            this.nameArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
            requirePermission("hlauth.admin.info");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String name = context.get(nameArg);
            PlayerAuth auth = plugin.getDataSource().getAuth(name);
            if (auth == null) {
                context.sendMessage(plugin.getMessages().get("error.notRegistered"));
                return;
            }
            context.sendMessage(plugin.getMessages().get("admin.info",
                "player", auth.realName,
                "premium", String.valueOf(auth.premium),
                "registrationDate", auth.registrationDate > 0
                    ? Instant.ofEpochMilli(auth.registrationDate).toString() : "-",
                "registrationIp", orDash(auth.registrationIp),
                "lastLogin", auth.lastLogin > 0
                    ? Instant.ofEpochMilli(auth.lastLogin).toString() : "-",
                "lastIp", orDash(auth.lastIp)));
        }

        private static String orDash(String value) {
            return value == null || value.isEmpty() ? "-" : value;
        }
    }

    private static final class ReloadSub extends CommandBase {
        private final AuthMePlugin plugin;

        ReloadSub(AuthMePlugin plugin) {
            super("reload", "Reload the hlAuth configuration");
            this.plugin = plugin;
            requirePermission("hlauth.admin.reload");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            plugin.reloadConfig();
            context.sendMessage(plugin.getMessages().get("admin.reloaded"));
        }
    }
}
