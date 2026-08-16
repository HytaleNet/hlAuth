package com.hlauth.hytale.ui;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.data.PlayerAuth;
import com.hlauth.hytale.message.Messages;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Admin page: masked IP(s) of a player and accounts that share those IPs.
 */
public final class IpCheckPage extends InteractiveCustomUIPage<AuthEventData> {

    public record IpGroup(String kindKey, String maskedIp, List<String> accounts) {
    }

    public record View(String playerName, List<IpGroup> groups) {
    }

    private final HlAuthPlugin plugin;
    private final View view;

    public IpCheckPage(@Nonnull PlayerRef playerRef, HlAuthPlugin plugin, View view) {
        super(playerRef, CustomPageLifetime.CanDismiss, AuthEventData.CODEC);
        this.plugin = plugin;
        this.view = view;
    }

    public static View build(PlayerAuth target, Collection<PlayerAuth> all) {
        List<IpGroup> groups = new ArrayList<>();
        String last = blankToNull(target.lastIp);
        String registration = blankToNull(target.registrationIp);
        if (last != null) {
            groups.add(new IpGroup("ui.ipcheck.lastIp", maskIp(last), namesOnIp(all, last)));
        }
        if (registration != null && (last == null || !sameIp(registration, last))) {
            groups.add(new IpGroup("ui.ipcheck.registrationIp", maskIp(registration),
                namesOnIp(all, registration)));
        }
        return new View(target.realName == null ? target.name : target.realName, groups);
    }

    public static String maskIp(@Nullable String ip) {
        if (ip == null || ip.isBlank()) {
            return "—";
        }
        String value = ip.trim();
        int mapped = value.toLowerCase(Locale.ROOT).indexOf(":ffff:");
        if (mapped >= 0) {
            value = value.substring(mapped + 6);
        }
        if (value.indexOf(':') >= 0) {
            String[] parts = value.split(":", -1);
            if (parts.length >= 2) {
                return parts[0] + ":" + parts[1] + ":*:*";
            }
            return "***";
        }
        String[] parts = value.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return "***";
    }

    static List<String> namesOnIp(Collection<PlayerAuth> all, String ip) {
        List<String> names = new ArrayList<>();
        for (PlayerAuth auth : all) {
            if (sameIp(auth.lastIp, ip) || sameIp(auth.registrationIp, ip)) {
                String name = auth.realName == null || auth.realName.isBlank() ? auth.name : auth.realName;
                names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static boolean sameIp(@Nullable String a, @Nullable String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    @Nullable
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/hlAuthIpCheckPage.ui");
        applyTexts(commandBuilder);

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CloseButton",
            EventData.of("Action", "Close"),
            false);
    }

    public void refreshTexts() {
        UICommandBuilder update = new UICommandBuilder();
        applyTexts(update);
        sendUpdate(update, false);
    }

    private void applyTexts(UICommandBuilder commands) {
        Messages msg = plugin.getMessages();
        commands.set("#TitleLabel.Text", msg.text("ui.ipcheck.title"));
        commands.set("#Player.Text", msg.text("ui.ipcheck.player", "player", view.playerName()));
        commands.set("#CloseButton.Text", msg.text("ui.ipcheck.close"));

        IpGroup first = view.groups().isEmpty() ? null : view.groups().get(0);
        IpGroup second = view.groups().size() > 1 ? view.groups().get(1) : null;
        applyGroup(commands, msg, "#IpLabel", "#IpValue", "#AccountsLabel", "#Accounts", first, true);
        applyGroup(commands, msg, "#Ip2Label", "#Ip2Value", "#Accounts2Label", "#Accounts2", second, false);
    }

    private void applyGroup(UICommandBuilder commands, Messages msg,
                            String labelId, String valueId, String accountsLabelId, String accountsId,
                            @Nullable IpGroup group, boolean primary) {
        boolean show = group != null;
        commands.set(labelId + ".Visible", show || primary);
        commands.set(valueId + ".Visible", show || primary);
        commands.set(accountsLabelId + ".Visible", show);
        commands.set(accountsId + ".Visible", show);
        if (!show) {
            if (primary) {
                commands.set(labelId + ".Text", msg.text("ui.ipcheck.noIp"));
                commands.set(valueId + ".Text", "—");
            }
            return;
        }
        commands.set(labelId + ".Text", msg.text(group.kindKey()));
        commands.set(valueId + ".Text", group.maskedIp());
        commands.set(accountsLabelId + ".Text", msg.text("ui.ipcheck.accounts"));
        commands.set(accountsId + ".Text", formatAccounts(group.accounts(), msg));
    }

    private static String formatAccounts(List<String> names, Messages msg) {
        if (names == null || names.isEmpty()) {
            return msg.text("ui.ipcheck.none");
        }
        int limit = 40;
        if (names.size() <= limit) {
            return String.join("\n", names);
        }
        List<String> shown = names.subList(0, limit);
        return String.join("\n", shown) + "\n+" + (names.size() - limit);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull AuthEventData data) {
        if ("Close".equals(data.getAction())) {
            close();
        }
    }
}
