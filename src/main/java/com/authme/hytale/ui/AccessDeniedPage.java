package com.authme.hytale.ui;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.message.Messages;
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
 * Non-dismissible plaque shown when a premium/offline mismatch is detected.
 * The only action is a red Exit button that disconnects the player.
 */
public final class AccessDeniedPage extends InteractiveCustomUIPage<AuthEventData> {

    public enum Reason {
        /** Offline client tried to join a premium-registered name. */
        PREMIUM_ACCOUNT("ui.denied.premium.title", "error.premiumAccount"),
        /** Premium client tried to join an offline-registered name. */
        OFFLINE_ACCOUNT("ui.denied.offline.title", "error.offlineAccount");

        private final String titleKey;
        private final String messageKey;

        Reason(String titleKey, String messageKey) {
            this.titleKey = titleKey;
            this.messageKey = messageKey;
        }

        public String titleKey() {
            return titleKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final AuthMePlugin plugin;
    private final Reason reason;

    public AccessDeniedPage(@Nonnull PlayerRef playerRef, AuthMePlugin plugin, Reason reason) {
        super(playerRef, CustomPageLifetime.CantClose, AuthEventData.CODEC);
        this.plugin = plugin;
        this.reason = reason;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("AuthMe/AccessDeniedPage.ui");
        applyTexts(commandBuilder);

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#ExitButton",
            EventData.of("Action", "Exit"),
            false);
    }

    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        sendUpdate(update, false);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        commands.set("#TitleLabel.Text", msg.text(reason.titleKey()));
        commands.set("#Message.Text", msg.text(reason.messageKey()));
        commands.set("#ExitButton.Text", msg.text("ui.denied.exit"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if (!"Exit".equals(data.getAction())) {
            return;
        }
        // Close the page first — kicking with UI open bugs the client hit-zones
        AuthUi.closeAndDisconnect(playerRef, plugin.getMessages().get(reason.messageKey()));
    }
}
