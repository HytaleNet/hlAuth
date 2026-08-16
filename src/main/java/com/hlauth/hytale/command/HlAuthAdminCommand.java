package com.hlauth.hytale.command;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.data.PlayerAuth;
import com.hlauth.hytale.messenger.MessengerKind;
import com.hlauth.hytale.ui.AuthUi;
import com.hlauth.hytale.ui.IpCheckPage;
import com.hypixel.hytale.server.core.Message;
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
 * Admin command collection: {@code /hlauth <register|unregister|changepassword|info|reload|backup|2fareset|ipcheck>}.
 * Requires {@code hlauth.admin}.
 */
public final class HlAuthAdminCommand extends CommandBase {

    private final HlAuthPlugin plugin;

    public HlAuthAdminCommand(HlAuthPlugin plugin) {
        super("hlauth", "hlAuth administration");
        this.plugin = plugin;
        requirePermission("hlauth.admin");
        addSubCommand(new RegisterSub(plugin));
        addSubCommand(new UnregisterSub(plugin));
        addSubCommand(new ChangePasswordSub(plugin));
        addSubCommand(new InfoSub(plugin));
        addSubCommand(new ReloadSub(plugin));
        addSubCommand(new BackupSub(plugin));
        addSubCommand(new TwoFactorResetSub(plugin));
        addSubCommand(new IpCheckSub(plugin));
        addSubCommand(new UnlinkSub(plugin));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(plugin.getMessages().get("admin.usage"));
    }

    // ------------------------------------------------------------------

    private static final class RegisterSub extends CommandBase {
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> nameArg;
        private final RequiredArg<String> passwordArg;

        RegisterSub(HlAuthPlugin plugin) {
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
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> nameArg;

        UnregisterSub(HlAuthPlugin plugin) {
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
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> nameArg;
        private final RequiredArg<String> passwordArg;

        ChangePasswordSub(HlAuthPlugin plugin) {
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
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> nameArg;

        InfoSub(HlAuthPlugin plugin) {
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
                "totp", String.valueOf(auth.totpEnabled),
                "blocked", String.valueOf(auth.blocked),
                "registrationDate", auth.registrationDate > 0
                    ? Instant.ofEpochMilli(auth.registrationDate).toString() : "-",
                "registrationIp", orDash(auth.registrationIp),
                "registrationGeo", plugin.getGeoIpService().describe(auth.registrationIp),
                "lastLogin", auth.lastLogin > 0
                    ? Instant.ofEpochMilli(auth.lastLogin).toString() : "-",
                "lastIp", orDash(auth.lastIp),
                "lastGeo", plugin.getGeoIpService().describe(auth.lastIp),
                "telegram", orDash(auth.telegramId),
                "discord", orDash(auth.discordId)));
        }

        private static String orDash(String value) {
            return value == null || value.isEmpty() ? "-" : value;
        }
    }

    private static final class ReloadSub extends CommandBase {
        private final HlAuthPlugin plugin;

        ReloadSub(HlAuthPlugin plugin) {
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

    private static final class BackupSub extends CommandBase {
        private final HlAuthPlugin plugin;

        BackupSub(HlAuthPlugin plugin) {
            super("backup", "Write a JSON snapshot of all accounts");
            this.plugin = plugin;
            requirePermission("hlauth.admin.backup");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            var result = plugin.getBackupService().createBackup();
            if (result.success()) {
                context.sendMessage(plugin.getMessages().get("admin.backup.ok",
                    "file", result.file().getFileName().toString(),
                    "count", String.valueOf(result.count())));
            } else {
                context.sendMessage(plugin.getMessages().get("admin.backup.fail",
                    "error", result.error() == null ? "unknown" : result.error()));
            }
        }
    }

    private static final class TwoFactorResetSub extends CommandBase {
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> nameArg;

        TwoFactorResetSub(HlAuthPlugin plugin) {
            super("2fareset", "Remove two-factor authentication from a player");
            this.plugin = plugin;
            this.nameArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
            requirePermission("hlauth.admin.2fareset");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String name = context.get(nameArg);
            PlayerAuth auth = plugin.getDataSource().getAuth(name);
            if (auth == null) {
                context.sendMessage(plugin.getMessages().get("error.notRegistered"));
                return;
            }
            plugin.getTotpService().adminReset(auth);
            context.sendMessage(plugin.getMessages().get("admin.2fareset", "player", name));
        }
    }

    private static final class IpCheckSub extends CommandBase {
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> nameArg;

        IpCheckSub(HlAuthPlugin plugin) {
            super("ipcheck", "Show masked IPs and accounts linked to a player");
            this.plugin = plugin;
            this.nameArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
            requirePermission("hlauth.admin.ipcheck");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            String name = context.get(nameArg);
            PlayerAuth auth = plugin.getDataSource().getAuth(name);
            if (auth == null) {
                context.sendMessage(plugin.getMessages().get("error.notRegistered"));
                return;
            }
            IpCheckPage.View view = IpCheckPage.build(auth, plugin.getDataSource().getAllAuths());
            PlayerRef admin = context.isPlayer() ? context.senderAs(PlayerRef.class) : null;
            if (admin != null && admin.isValid()) {
                boolean scheduled = AuthUi.runOnWorldThread(admin, live -> {
                    IpCheckPage page = new IpCheckPage(live.playerRef(), plugin, view);
                    live.player().getPageManager().openCustomPage(live.ref(), live.store(), page);
                    page.refreshTexts();
                });
                if (scheduled) {
                    return;
                }
            }
            sendIpCheckChat(context, view);
        }

        private void sendIpCheckChat(CommandContext context, IpCheckPage.View view) {
            var msg = plugin.getMessages();
            context.sendMessage(msg.get("ui.ipcheck.player", "player", view.playerName()));
            if (view.groups().isEmpty()) {
                context.sendMessage(msg.get("ui.ipcheck.noIp"));
                return;
            }
            for (IpCheckPage.IpGroup group : view.groups()) {
                context.sendMessage(Message.raw(msg.text(group.kindKey()) + ": " + group.maskedIp()));
                String names = group.accounts().isEmpty()
                    ? msg.text("ui.ipcheck.none")
                    : String.join(", ", group.accounts());
                context.sendMessage(Message.raw(msg.text("ui.ipcheck.accounts") + " " + names));
            }
        }
    }

    private static final class UnlinkSub extends CommandBase {
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> nameArg;
        private final RequiredArg<String> serviceArg;

        UnlinkSub(HlAuthPlugin plugin) {
            super("unlink", "Unlink Telegram or Discord from a player");
            this.plugin = plugin;
            this.nameArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
            this.serviceArg = withRequiredArg("service", "telegram or discord", ArgTypes.STRING);
            requirePermission("hlauth.admin.unlink");
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            PlayerAuth auth = plugin.getDataSource().getAuth(context.get(nameArg));
            if (auth == null) {
                context.sendMessage(plugin.getMessages().get("error.notRegistered"));
                return;
            }
            MessengerKind kind = MessengerKind.fromInput(context.get(serviceArg));
            if (kind == null) {
                context.sendMessage(plugin.getMessages().get("messenger.unknownService"));
                return;
            }
            context.sendMessage(plugin.getMessengerService().adminUnlink(auth, kind).message());
        }
    }
}
