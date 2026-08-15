package com.authme.hytale.ui;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.message.Messages;
import com.authme.hytale.service.AuthService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Scan-QR + secret + 6-digit confirmation to bind TOTP 2FA.
 */
public final class TotpSetupPage extends InteractiveCustomUIPage<AuthEventData> {

    private final AuthMePlugin plugin;

    public TotpSetupPage(@Nonnull PlayerRef playerRef, AuthMePlugin plugin) {
        super(playerRef, CustomPageLifetime.CantClose, AuthEventData.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/hlAuthTotpSetupPage.ui");
        applyTexts(commandBuilder);
        appendQr(commandBuilder);

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#ConfirmButton",
            EventData.of("Action", "SetupConfirm").append("@Code", "#CodeInput.Value"),
            false);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CancelButton",
            EventData.of("Action", "SetupCancel"),
            false);
    }

    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        update.set("#Error.Visible", false);
        sendUpdate(update, false);
    }

    private void appendQr(UICommandBuilder commands) {
        String markup = plugin.getTotpService().pendingQrMarkup(playerRef.getUuid());
        if (markup == null || markup.isEmpty()) {
            return;
        }
        commands.appendInline("#QrHost", markup);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        UUID uuid = playerRef.getUuid();
        String secret = plugin.getTotpService().pendingSecret(uuid);
        commands.set("#TitleLabel.Text", msg.text("ui.2fa.setup.title"));
        commands.set("#Welcome.Text", msg.text("ui.2fa.setup.welcome"));
        commands.set("#SecretLabel.Text", msg.text("ui.2fa.setup.secret"));
        commands.set("#SecretInput.Value", secret == null ? "" : secret);
        commands.set("#CodeLabel.Text", msg.text("ui.2fa.code"));
        commands.set("#CodeInput.PlaceholderText", msg.text("ui.2fa.code.placeholder"));
        commands.set("#ConfirmButton.Text", msg.text("ui.2fa.setup.button"));
        commands.set("#CancelButton.Text", msg.text("ui.2fa.cancel"));
        commands.set("#Hint.Text", msg.text("ui.2fa.setup.hint"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if ("SetupCancel".equals(data.getAction())) {
            TwoFactorPages.cancel(plugin, playerRef, store, ref);
            return;
        }
        if (!"SetupConfirm".equals(data.getAction())) {
            return;
        }
        AuthService.Result result = plugin.getTotpService().confirmSetup(playerRef, data.getCode());
        if (result.status() == AuthService.Result.Status.TOTP_RECOVERY) {
            TwoFactorPages.open(plugin, playerRef, store, ref, result);
            playerRef.sendMessage(result.message());
            return;
        }
        UICommandBuilder update = new UICommandBuilder();
        update.set("#Error.Visible", true);
        update.set("#Error.Text", result.message().getRawText());
        update.set("#CodeInput.Value", "");
        sendUpdate(update, false);
    }
}
