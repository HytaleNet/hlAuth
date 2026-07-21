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
 * Non-dismissible login form shown to registered players until they enter
 * their password.
 */
public final class LoginPage extends InteractiveCustomUIPage<AuthEventData> {

    private final AuthMePlugin plugin;

    public LoginPage(@Nonnull PlayerRef playerRef, AuthMePlugin plugin) {
        super(playerRef, CustomPageLifetime.CantClose, AuthEventData.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("AuthMe/LoginPage.ui");
        applyTexts(commandBuilder);

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#LoginButton",
            EventData.of("Action", "Login").append("@Password", "#PasswordInput.Value"),
            false);
    }

    /** Fills labels from messages.yml — call again after open to fix first-frame layout glitches. */
    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        update.set("#Error.Visible", false);
        sendUpdate(update, false);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        commands.set("#TitleLabel.Text", msg.text("ui.login.title"));
        commands.set("#Welcome.Text", msg.text("ui.login.welcome"));
        commands.set("#PasswordLabel.Text", msg.text("ui.password"));
        commands.set("#PasswordInput.PlaceholderText", msg.text("ui.password.placeholder"));
        commands.set("#LoginButton.Text", msg.text("ui.login.button"));
        commands.set("#Hint.Text", msg.text("ui.login.hint"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if (!"Login".equals(data.getAction())) {
            return;
        }
        AuthService.Result result = plugin.getAuthService().login(playerRef, data.getPassword());
        if (result.success()) {
            close();
            playerRef.sendMessage(result.message());
        } else {
            UICommandBuilder update = new UICommandBuilder();
            update.set("#Error.Visible", true);
            update.set("#Error.Text", result.message().getRawText());
            update.set("#PasswordInput.Value", "");
            sendUpdate(update, false);
        }
    }
}
