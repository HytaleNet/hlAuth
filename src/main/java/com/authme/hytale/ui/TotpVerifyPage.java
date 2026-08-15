package com.authme.hytale.ui;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.message.Messages;
import com.authme.hytale.service.AuthService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Asks for a 6-digit authenticator code after the password (or session) step.
 */
public final class TotpVerifyPage extends InteractiveCustomUIPage<AuthEventData> {

    private final AuthMePlugin plugin;

    public TotpVerifyPage(@Nonnull PlayerRef playerRef, AuthMePlugin plugin) {
        super(playerRef, CustomPageLifetime.CantClose, AuthEventData.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/hlAuthTotpVerifyPage.ui");
        applyTexts(commandBuilder);

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#ConfirmButton",
            EventData.of("Action", "TotpVerify").append("@Code", "#CodeInput.Value"),
            false);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CancelButton",
            EventData.of("Action", "TotpCancel"),
            false);
    }

    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        update.set("#Error.Visible", false);
        sendUpdate(update, false);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        commands.set("#TitleLabel.Text", msg.text("ui.2fa.verify.title"));
        commands.set("#Welcome.Text", msg.text("ui.2fa.verify.welcome"));
        commands.set("#CodeLabel.Text", msg.text("ui.2fa.code"));
        commands.set("#CodeInput.PlaceholderText", msg.text("ui.2fa.code.placeholder"));
        commands.set("#ConfirmButton.Text", msg.text("ui.2fa.verify.button"));
        commands.set("#CancelButton.Text", msg.text("ui.2fa.cancel"));
        commands.set("#Hint.Text", msg.text("ui.2fa.verify.hint"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if ("TotpCancel".equals(data.getAction())) {
            TwoFactorPages.cancel(plugin, playerRef, store, ref);
            return;
        }
        if (!"TotpVerify".equals(data.getAction())) {
            return;
        }
        AuthService.Result result = plugin.getTotpService().verifyLoginCode(playerRef, data.getCode());
        if (result.success()) {
            close();
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
