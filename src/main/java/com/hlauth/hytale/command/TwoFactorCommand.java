package com.hlauth.hytale.command;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.service.AuthService;
import com.hlauth.hytale.service.TotpService;
import com.hlauth.hytale.ui.AuthUi;
import com.hlauth.hytale.ui.TwoFactorPages;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Player 2FA: {@code /2fa [code]}, {@code /2fa disable <code>}, {@code /2fa recover <code>},
 * {@code /2fa done}.
 */
public final class TwoFactorCommand extends AbstractPlayerCommand {

    private final HlAuthPlugin plugin;
    private final OptionalArg<String> codeArg;

    public TwoFactorCommand(HlAuthPlugin plugin) {
        super("2fa", "Two-factor authentication");
        this.plugin = plugin;
        this.codeArg = withOptionalArg("code", "Authenticator or recovery code", ArgTypes.STRING);
        addAliases("totp");
        requirePermission("hlauth.player.2fa");
        setPermissionGroups("hytale:Adventurer");
        addSubCommand(new DisableSub(plugin));
        addSubCommand(new RecoverSub(plugin));
        addSubCommand(new DoneSub(plugin));
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        if (!plugin.getConfig().isTwoFactorEnabled()) {
            context.sendMessage(plugin.getMessages().get("2fa.disabled"));
            return;
        }

        TotpService totp = plugin.getTotpService();
        if (context.provided(codeArg)) {
            handleCode(context, store, ref, playerRef, context.get(codeArg));
            return;
        }

        switch (totp.phase(playerRef.getUuid())) {
            case SETUP -> TwoFactorPages.open(plugin, playerRef, store, ref,
                    new AuthService.Result(AuthService.Result.Status.TOTP_SETUP,
                        plugin.getMessages().get("2fa.setup.started")));
            case VERIFY -> context.sendMessage(plugin.getMessages().get("2fa.verify.needed"));
            case RECOVERY -> {
                TwoFactorPages.sendRecoveryToChat(plugin, playerRef);
                TwoFactorPages.open(plugin, playerRef, store, ref,
                    new AuthService.Result(AuthService.Result.Status.TOTP_RECOVERY,
                        plugin.getMessages().get("2fa.recovery.ready")));
            }
            case NONE -> {
                AuthService.Result result = totp.startEnable(playerRef);
                context.sendMessage(result.message());
                if (result.status() == AuthService.Result.Status.TOTP_SETUP) {
                    TwoFactorPages.open(plugin, playerRef, store, ref, result);
                }
            }
        }
    }

    private void handleCode(CommandContext context,
                            Store<EntityStore> store,
                            Ref<EntityStore> ref,
                            PlayerRef playerRef,
                            String code) {
        TotpService totp = plugin.getTotpService();
        AuthService.Result result = switch (totp.phase(playerRef.getUuid())) {
            case VERIFY -> totp.verifyLoginCode(playerRef, code);
            case SETUP -> totp.confirmSetup(playerRef, code);
            default -> fail("2fa.notWaiting");
        };
        context.sendMessage(result.message());
        if (result.success()) {
            AuthUi.close(store, ref);
            return;
        }
        if (result.status() == AuthService.Result.Status.TOTP_RECOVERY) {
            TwoFactorPages.sendRecoveryToChat(plugin, playerRef);
            TwoFactorPages.open(plugin, playerRef, store, ref, result);
        }
    }

    private AuthService.Result fail(String key) {
        return new AuthService.Result(AuthService.Result.Status.FAILURE, plugin.getMessages().get(key));
    }

    private static final class DisableSub extends AbstractPlayerCommand {
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> codeArg;

        DisableSub(HlAuthPlugin plugin) {
            super("disable", "Disable two-factor authentication");
            this.plugin = plugin;
            this.codeArg = withRequiredArg("code", "Authenticator or recovery code", ArgTypes.STRING);
            requirePermission("hlauth.player.2fa");
            setPermissionGroups("hytale:Adventurer");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            AuthService.Result result = plugin.getTotpService().disable(playerRef, context.get(codeArg));
            context.sendMessage(result.message());
        }
    }

    private static final class RecoverSub extends AbstractPlayerCommand {
        private final HlAuthPlugin plugin;
        private final RequiredArg<String> codeArg;

        RecoverSub(HlAuthPlugin plugin) {
            super("recover", "Log in with a one-time recovery code");
            this.plugin = plugin;
            this.codeArg = withRequiredArg("code", "Recovery code", ArgTypes.STRING);
            requirePermission("hlauth.player.2fa");
            setPermissionGroups("hytale:Adventurer");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            AuthService.Result result = plugin.getTotpService()
                .useRecoveryCode(playerRef, context.get(codeArg));
            context.sendMessage(result.message());
            if (result.success()) {
                AuthUi.close(store, ref);
            }
        }
    }

    private static final class DoneSub extends AbstractPlayerCommand {
        private final HlAuthPlugin plugin;

        DoneSub(HlAuthPlugin plugin) {
            super("done", "Continue after saving recovery codes");
            this.plugin = plugin;
            requirePermission("hlauth.player.2fa");
            setPermissionGroups("hytale:Adventurer");
        }

        @Override
        protected void execute(@Nonnull CommandContext context,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef,
                               @Nonnull World world) {
            AuthService.Result result = plugin.getTotpService().continueAfterRecovery(playerRef);
            context.sendMessage(result.message());
            if (result.success()) {
                AuthUi.close(store, ref);
            }
        }
    }
}
