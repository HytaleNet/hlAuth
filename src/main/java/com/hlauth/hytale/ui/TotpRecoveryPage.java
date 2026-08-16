package com.hlauth.hytale.ui;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.message.Messages;
import com.hlauth.hytale.service.AuthService;
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
 * One-time screenshot page for recovery codes after 2FA is bound.
 */
public final class TotpRecoveryPage extends InteractiveCustomUIPage<AuthEventData> {

    private final HlAuthPlugin plugin;

    public TotpRecoveryPage(@Nonnull PlayerRef playerRef, HlAuthPlugin plugin) {
        super(playerRef, CustomPageLifetime.CantClose, AuthEventData.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/hlAuthTotpRecoveryPage.ui");
        applyTexts(commandBuilder);

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#ContinueButton",
            EventData.of("Action", "TotpContinue"),
            false);
    }

    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        sendUpdate(update, false);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        String[] codes = plugin.getTotpService().pendingRecoveryCodes(playerRef.getUuid());
        String joined = codes == null ? "" : String.join("\n", codes);
        commands.set("#TitleLabel.Text", msg.text("ui.2fa.recovery.title"));
        commands.set("#Welcome.Text", msg.text("ui.2fa.recovery.welcome"));
        commands.set("#Codes.Text", joined);
        commands.set("#Warning.Text", msg.text("ui.2fa.recovery.warning"));
        commands.set("#ContinueButton.Text", msg.text("ui.2fa.recovery.button"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if (!"TotpContinue".equals(data.getAction())) {
            return;
        }
        AuthService.Result result = plugin.getTotpService().continueAfterRecovery(playerRef);
        close();
        playerRef.sendMessage(result.message());
    }
}
