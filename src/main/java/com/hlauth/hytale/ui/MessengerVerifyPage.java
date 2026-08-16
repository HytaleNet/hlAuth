package com.hlauth.hytale.ui;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.message.Messages;
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

/** Waiting plaque while Telegram / Discord confirms a login. */
public final class MessengerVerifyPage extends InteractiveCustomUIPage<AuthEventData> {

    private final HlAuthPlugin plugin;

    public MessengerVerifyPage(@Nonnull PlayerRef playerRef, HlAuthPlugin plugin) {
        super(playerRef, CustomPageLifetime.CantClose, AuthEventData.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/hlAuthMessengerVerifyPage.ui");
        applyTexts(commandBuilder);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CancelButton",
            EventData.of("Action", "MessengerCancel"),
            false);
    }

    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        sendUpdate(update, false);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        commands.set("#TitleLabel.Text", msg.text("ui.messenger.verify.title"));
        commands.set("#Welcome.Text", msg.text("ui.messenger.verify.welcome"));
        commands.set("#Hint.Text", msg.text("ui.messenger.verify.hint"));
        commands.set("#CancelButton.Text", msg.text("ui.messenger.cancel"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if ("MessengerCancel".equals(data.getAction())) {
            TwoFactorPages.cancel(plugin, playerRef, store, ref);
        }
    }
}
