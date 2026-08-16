package com.hlauth.hytale.command;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.messenger.MessengerKind;
import com.hlauth.hytale.service.AuthService;
import com.hlauth.hytale.ui.TwoFactorPages;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * {@code /link telegram} / {@code /link discord} — start linking a messenger,
 * {@code /link confirm <code>} — chat fallback for the confirmation code.
 * Structured as sub-commands (like /hlauth) so positional arguments parse correctly.
 */
public final class LinkCommand extends CommandBase {

    private final HlAuthPlugin plugin;

    public LinkCommand(HlAuthPlugin plugin) {
        super("link", "Link Telegram or Discord");
        this.plugin = plugin;
        requirePermission("hlauth.player.link");
        setPermissionGroups("hytale:Adventurer");
        addSubCommand(new StartSub(plugin, "telegram", MessengerKind.TELEGRAM));
        addSubCommand(new StartSub(plugin, "discord", MessengerKind.DISCORD));
        addSubCommand(new ConfirmSub(plugin));
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        context.sendMessage(plugin.getMessages().get("messenger.link.usage",
            "services", availableServices(plugin)));
    }

    static String availableServices(HlAuthPlugin plugin) {
        StringBuilder sb = new StringBuilder();
        if (plugin.getMessengerService().isKindEnabled(MessengerKind.TELEGRAM)) {
            sb.append("telegram");
        }
        if (plugin.getMessengerService().isKindEnabled(MessengerKind.DISCORD)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("discord");
        }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    private static PlayerRef playerOrNull(CommandContext context) {
        return context.isPlayer() ? context.senderAs(PlayerRef.class) : null;
    }

    private static void handleResult(HlAuthPlugin plugin, CommandContext context,
                                     PlayerRef playerRef, AuthService.Result result) {
        context.sendMessage(result.message());
        if (result.status() == AuthService.Result.Status.MESSENGER_LINK) {
            TwoFactorPages.open(plugin, playerRef, result);
        }
    }

    private static final class StartSub extends CommandBase {
        private final HlAuthPlugin plugin;
        private final MessengerKind kind;

        StartSub(HlAuthPlugin plugin, String name, MessengerKind kind) {
            super(name, "Link your " + kind.display() + " account");
            this.plugin = plugin;
            this.kind = kind;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            PlayerRef playerRef = playerOrNull(context);
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }
            handleResult(plugin, context, playerRef,
                plugin.getMessengerService().startLink(playerRef, kind));
        }
    }

    private static final class ConfirmSub extends CommandBase {
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> codeArg;

        ConfirmSub(HlAuthPlugin plugin) {
            super("confirm", "Confirm linking with the code from the bot");
            this.plugin = plugin;
            this.codeArg = withRequiredArg("code", "Confirmation code from the bot", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            PlayerRef playerRef = playerOrNull(context);
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }
            handleResult(plugin, context, playerRef,
                plugin.getMessengerService().confirmLink(playerRef, context.get(codeArg)));
        }
    }
}
