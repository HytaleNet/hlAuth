package com.hlauth.hytale.ui;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.data.PlayerAuth;
import com.hlauth.hytale.message.Messages;
import com.hlauth.hytale.messenger.MessengerService;
import com.hlauth.hytale.service.AuthService;
import com.hlauth.hytale.service.TotpService;
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

/** In-game instructions for linking Telegram / Discord. */
public final class MessengerLinkPage extends InteractiveCustomUIPage<AuthEventData> {

    private final HlAuthPlugin plugin;

    public MessengerLinkPage(@Nonnull PlayerRef playerRef, HlAuthPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, AuthEventData.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/hlAuthMessengerLinkPage.ui");
        applyTexts(commandBuilder);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CancelButton",
            EventData.of("Action", "MessengerCancel"),
            false);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#ConfirmButton",
            EventData.of("Action", "MessengerConfirm").append("@Code", "#CodeInput.Value"),
            false);
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#BotLinkButton",
            EventData.of("Action", "MessengerBotLink"),
            false);
    }

    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        sendUpdate(update, false);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        MessengerService.LinkPending pending = plugin.getMessengerService().linkOf(playerRef.getUuid());
        commands.set("#CancelButton.Text", msg.text("ui.messenger.cancel"));
        commands.set("#ConfirmButton.Text", msg.text("ui.messenger.link.confirmButton"));
        commands.set("#CodeInput.PlaceholderText", msg.text("ui.messenger.link.inputPlaceholder"));
        commands.set("#BotLinkButton.Text", msg.text("ui.messenger.link.openBot"));
        String botUrl = pending == null ? "" : plugin.getMessengerService().botLink(pending.kind);
        commands.set("#BotLinkButton.Visible", botUrl != null && !botUrl.isEmpty());
        if (pending == null) {
            commands.set("#TitleLabel.Text", msg.text("ui.messenger.link.title"));
            commands.set("#Welcome.Text", msg.text("ui.messenger.link.missing"));
            commands.set("#Warning.Visible", false);
            commands.set("#CodeLabel.Text", "");
            commands.set("#CodeValue.Text", "");
            commands.set("#CodeInput.Visible", false);
            commands.set("#ConfirmButton.Visible", false);
            commands.set("#Hint.Text", "");
            return;
        }
        String service = pending.kind.display();
        String bot = plugin.getMessengerService().botMention(pending.kind);
        commands.set("#TitleLabel.Text", msg.text("ui.messenger.link.titleService", "service", service));
        PlayerAuth auth = plugin.getDataSource().getAuth(playerRef.getUsername());
        boolean totp = TotpService.isBound(auth);
        commands.set("#Warning.Visible", totp);
        commands.set("#Warning.Text", totp ? msg.text("ui.messenger.link.totpWarning", "service", service) : "");
        if (pending.phase == MessengerService.Phase.LINK_WAIT_GAME) {
            commands.set("#Welcome.Text", msg.text("ui.messenger.link.confirmWelcome",
                "service", service, "name", pending.messengerName == null ? "—" : pending.messengerName));
            commands.set("#CodeLabel.Text", msg.text("ui.messenger.link.inputLabel"));
            commands.set("#CodeValue.Text", "");
            commands.set("#CodeInput.Visible", true);
            commands.set("#ConfirmButton.Visible", true);
            commands.set("#Hint.Text", msg.text("ui.messenger.link.confirmHint"));
        } else {
            commands.set("#Welcome.Text", msg.text("ui.messenger.link.welcome",
                "service", service, "bot", bot == null ? service : bot));
            commands.set("#CodeLabel.Text", msg.text("ui.messenger.link.codeLabel"));
            commands.set("#CodeValue.Text", pending.botCode);
            commands.set("#CodeInput.Visible", false);
            commands.set("#ConfirmButton.Visible", false);
            commands.set("#Hint.Text", msg.text("ui.messenger.link.hint"));
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if ("MessengerCancel".equals(data.getAction())) {
            close();
            return;
        }
        if ("MessengerBotLink".equals(data.getAction())) {
            MessengerService.LinkPending pending = plugin.getMessengerService().linkOf(playerRef.getUuid());
            String url = pending == null ? "" : plugin.getMessengerService().botLink(pending.kind);
            if (url != null && !url.isEmpty()) {
                playerRef.sendMessage(com.hypixel.hytale.server.core.Message
                    .raw(plugin.getMessages().text("ui.messenger.link.openBotChat", "url", url))
                    .link(url));
                playerRef.sendMessage(plugin.getMessages().get("ui.messenger.link.openBotReopen"));
                // Close so the chat link is clickable; the page reopens when the bot code arrives
                close();
            }
            return;
        }
        if ("MessengerConfirm".equals(data.getAction())) {
            AuthService.Result result = plugin.getMessengerService().confirmLink(playerRef, data.getCode());
            playerRef.sendMessage(result.message());
            if (result.success()) {
                close();
                return;
            }
            UICommandBuilder update = new UICommandBuilder();
            update.set("#CodeInput.Value", "");
            sendUpdate(update, false);
        }
    }
}
