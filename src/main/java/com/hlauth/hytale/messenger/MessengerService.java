package com.hlauth.hytale.messenger;

import com.hlauth.hytale.HlAuthPlugin;
import com.hlauth.hytale.config.HlAuthConfig;
import com.hlauth.hytale.data.PlayerAuth;
import com.hlauth.hytale.service.AuthService;
import com.hlauth.hytale.service.TotpService;
import com.hlauth.hytale.ui.AuthUi;
import com.hlauth.hytale.ui.MessengerLinkPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram / Discord account linking and login confirmation.
 *
 * <p>Flow (BaronessAuth-style): {@code /link telegram} shows a code → player
 * sends it to the bot → in-game confirmation (so the player sees the messenger
 * name) → TOTP is disabled if it was bound.</p>
 */
public final class MessengerService {

    public enum Phase {
        NONE, LINK_WAIT_BOT, LINK_WAIT_GAME, VERIFY
    }

    public record BotState(boolean linked, boolean twoFactor, boolean notifications, boolean sessions) {
    }

    private static final long LINK_TTL_MS = 5 * 60_000L;
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    public static final class LinkPending {
        public final UUID playerUuid;
        public final String playerName;
        public final MessengerKind kind;
        public final String botCode;
        public final long created = System.currentTimeMillis();
        public volatile Phase phase = Phase.LINK_WAIT_BOT;
        public volatile String confirmCode;
        public volatile String messengerUserId;
        public volatile String messengerName;
        public volatile String chatId;

        LinkPending(UUID playerUuid, String playerName, MessengerKind kind, String botCode) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.kind = kind;
            this.botCode = botCode;
        }
    }

    static final class VerifyPending {
        final UUID playerUuid;
        final String playerName;
        final String ip;
        final long created = System.currentTimeMillis();
        volatile boolean telegram;
        volatile boolean discord;

        VerifyPending(UUID playerUuid, String playerName, String ip) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.ip = ip;
        }
    }

    private final HlAuthPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, LinkPending> linksByPlayer = new ConcurrentHashMap<>();
    private final Map<String, LinkPending> linksByBotCode = new ConcurrentHashMap<>();
    private final Map<UUID, VerifyPending> verifies = new ConcurrentHashMap<>();

    private volatile TelegramBotConfig telegramConfig = new TelegramBotConfig();
    private volatile DiscordBotConfig discordConfig = new DiscordBotConfig();
    private volatile TelegramBot telegramBot;
    private volatile DiscordBot discordBot;

    public MessengerService(HlAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        reloadBots();
    }

    public void reloadBots() {
        stopBots();
        HlAuthConfig config = plugin.getConfig();
        Path dataDir = plugin.getDataDirectory();
        this.telegramConfig = TelegramBotConfig.load(dataDir, config, plugin.getLogger());
        this.discordConfig = DiscordBotConfig.load(dataDir, config, plugin.getLogger());
        if (telegramConfig.enabled && notBlank(telegramConfig.token)) {
            TelegramBot bot = new TelegramBot(this, plugin, telegramConfig);
            this.telegramBot = bot;
            bot.start();
        } else if (telegramConfig.enabled) {
            plugin.getLogger().atWarning().log("Telegram bot enabled but token is empty (telegram-bot.yml)");
        } else {
            plugin.getLogger().atInfo().log("Telegram bot disabled (telegram-bot.yml: enabled=false)");
        }
        if (discordConfig.enabled && notBlank(discordConfig.token)) {
            DiscordBot bot = new DiscordBot(this, plugin, discordConfig);
            this.discordBot = bot;
            bot.start();
        } else if (discordConfig.enabled) {
            plugin.getLogger().atWarning().log("Discord bot enabled but token is empty (discord-bot.yml)");
        } else {
            plugin.getLogger().atInfo().log("Discord bot disabled (discord-bot.yml: enabled=false)");
        }
    }

    public void stop() {
        stopBots();
        linksByPlayer.clear();
        linksByBotCode.clear();
        verifies.clear();
    }

    private void stopBots() {
        TelegramBot tg = telegramBot;
        telegramBot = null;
        if (tg != null) {
            tg.stop();
        }
        DiscordBot ds = discordBot;
        discordBot = null;
        if (ds != null) {
            ds.stop();
        }
    }

    public void clear(UUID uuid) {
        LinkPending link = linksByPlayer.remove(uuid);
        if (link != null) {
            linksByBotCode.remove(link.botCode);
        }
        verifies.remove(uuid);
    }

    public Phase phase(UUID uuid) {
        VerifyPending verify = verifies.get(uuid);
        if (verify != null) {
            return Phase.VERIFY;
        }
        LinkPending link = linksByPlayer.get(uuid);
        return link == null ? Phase.NONE : link.phase;
    }

    @Nullable
    public String reminderKey(UUID uuid) {
        return switch (phase(uuid)) {
            case VERIFY -> "messenger.reminder.verify";
            case LINK_WAIT_BOT -> "messenger.reminder.sendCode";
            case LINK_WAIT_GAME -> "messenger.reminder.confirm";
            case NONE -> null;
        };
    }

    public static boolean isBound(PlayerAuth auth) {
        return hasTelegram(auth) || hasDiscord(auth);
    }

    public static boolean hasTelegram(PlayerAuth auth) {
        return auth != null && notBlank(auth.telegramId);
    }

    public static boolean hasDiscord(PlayerAuth auth) {
        return auth != null && notBlank(auth.discordId);
    }

    public boolean isKindEnabled(MessengerKind kind) {
        if (kind == MessengerKind.TELEGRAM) {
            return telegramConfig.enabled && notBlank(telegramConfig.token) && telegramBot != null;
        }
        return discordConfig.enabled && notBlank(discordConfig.token) && discordBot != null;
    }

    @Nullable
    public String botMention(MessengerKind kind) {
        if (kind == MessengerKind.TELEGRAM) {
            String name = telegramConfig.username;
            TelegramBot bot = telegramBot;
            if (!notBlank(name) && bot != null) {
                name = bot.username();
            }
            if (!notBlank(name)) {
                return "Telegram-бот";
            }
            return name.startsWith("@") ? name : "@" + name;
        }
        DiscordBot bot = discordBot;
        String name = discordConfig.username;
        if (!notBlank(name) && bot != null) {
            name = bot.username();
        }
        return notBlank(name) ? name : "Discord-бот";
    }

    String telegramApiUrl() {
        return telegramConfig.apiUrl;
    }

    public String botLink(MessengerKind kind) {
        if (kind == MessengerKind.TELEGRAM) {
            String name = botMention(MessengerKind.TELEGRAM);
            if (name != null && name.startsWith("@") && name.length() > 1) {
                return "https://t.me/" + name.substring(1);
            }
            return "";
        }
        return discordConfig.inviteUrl == null ? "" : discordConfig.inviteUrl.trim();
    }

    static final String DEFAULT_HELP_URL = "https://hlauncher.com";

    @Nullable
    String helpUrl(MessengerKind kind) {
        String raw = kind == MessengerKind.TELEGRAM ? telegramConfig.helpUrl : discordConfig.helpUrl;
        if (raw == null || raw.isBlank()) {
            raw = DEFAULT_HELP_URL;
        }
        raw = raw.trim();
        if (!raw.regionMatches(true, 0, "http://", 0, 7)
                && !raw.regionMatches(true, 0, "https://", 0, 8)) {
            raw = "https://" + raw;
        }
        return raw;
    }

    @Nullable
    public LinkPending linkOf(UUID uuid) {
        return linksByPlayer.get(uuid);
    }

    public AuthService.Result startLink(PlayerRef player, MessengerKind kind) {
        if (!plugin.getLimboService().isAuthenticated(player.getUuid())) {
            return fail("error.notLoggedIn");
        }
        if (!isKindEnabled(kind)) {
            return fail("messenger.disabled", "service", kind.display());
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null) {
            return fail("error.notRegistered");
        }
        if (kind == MessengerKind.TELEGRAM && hasTelegram(auth)) {
            return fail("messenger.alreadyLinked", "service", kind.display());
        }
        if (kind == MessengerKind.DISCORD && hasDiscord(auth)) {
            return fail("messenger.alreadyLinked", "service", kind.display());
        }
        clear(player.getUuid());
        LinkPending pending = new LinkPending(player.getUuid(), player.getUsername(), kind, randomCode(8));
        linksByPlayer.put(player.getUuid(), pending);
        linksByBotCode.put(pending.botCode, pending);
        String key = TotpService.isBound(auth) ? "messenger.link.startedTotp" : "messenger.link.started";
        return new AuthService.Result(AuthService.Result.Status.MESSENGER_LINK,
            plugin.getMessages().get(key,
                "service", kind.display(),
                "bot", botMention(kind),
                "code", pending.botCode,
                "botLink", botLink(kind)));
    }

    public AuthService.Result confirmLink(PlayerRef player, String rawCode) {
        LinkPending pending = linksByPlayer.get(player.getUuid());
        if (pending == null || pending.phase != Phase.LINK_WAIT_GAME) {
            return fail("messenger.notWaitingConfirm");
        }
        if (!pending.botCode.equalsIgnoreCase(normalizeCode(rawCode))
                && (pending.confirmCode == null || !pending.confirmCode.equalsIgnoreCase(normalizeCode(rawCode)))) {
            return fail("messenger.unknownCode");
        }
        if (System.currentTimeMillis() - pending.created > LINK_TTL_MS) {
            clear(player.getUuid());
            return fail("messenger.expired");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null) {
            return fail("error.notRegistered");
        }
        PlayerAuth taken = pending.kind == MessengerKind.TELEGRAM
            ? plugin.getDataSource().getAuthByTelegramId(pending.messengerUserId)
            : plugin.getDataSource().getAuthByDiscordId(pending.messengerUserId);
        if (taken != null && !taken.name.equalsIgnoreCase(auth.name)) {
            clear(player.getUuid());
            return fail("messenger.taken", "service", pending.kind.display());
        }
        boolean hadTotp = TotpService.isBound(auth);
        if (pending.kind == MessengerKind.TELEGRAM) {
            auth.telegramId = pending.messengerUserId;
        } else {
            auth.discordId = pending.messengerUserId;
        }
        auth.messengerConfirmOnly = plugin.getConfig().isMessengerConfirmOnly();
        auth.messengerTwoFactorEnabled = true;
        auth.messengerNotificationsEnabled = true;
        auth.messengerSessionsEnabled = true;
        auth.blocked = false;
        if (hadTotp) {
            auth.totpEnabled = false;
            auth.totpSecret = null;
            auth.totpRecoveryHashes = null;
        }
        plugin.getDataSource().updateAuth(auth);
        String chatId = pending.chatId;
        MessengerKind kind = pending.kind;
        String playerName = auth.realName == null ? auth.name : auth.realName;
        clear(player.getUuid());
        sendBotText(kind, chatId, plugin.getMessages().text("messenger.bot.linked", "player", playerName));
        if (kind == MessengerKind.DISCORD && discordBot != null && notBlank(chatId)) {
            discordBot.sendMainMenu(chatId);
        }
        String doneKey = hadTotp ? "messenger.link.successTotpOff" : "messenger.link.success";
        plugin.getLogger().atInfo().log("Player %s linked %s (%s)%s",
            player.getUsername(), kind.display(), pending.messengerUserId,
            hadTotp ? ", TOTP disabled" : "");
        return ok(doneKey, "service", kind.display(), "name", orDash(pending.messengerName));
    }

    public AuthService.Result unlink(PlayerRef player, MessengerKind kind) {
        if (!plugin.getLimboService().isAuthenticated(player.getUuid())) {
            return fail("error.notLoggedIn");
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(player.getUsername());
        if (auth == null) {
            return fail("error.notRegistered");
        }
        return unlinkAccount(auth, kind, "messenger.unlinked");
    }

    public AuthService.Result adminUnlink(PlayerAuth auth, MessengerKind kind) {
        return unlinkAccount(auth, kind, "admin.unlinked");
    }

    private AuthService.Result unlinkAccount(PlayerAuth auth, MessengerKind kind, String successKey) {
        if (kind == MessengerKind.TELEGRAM) {
            if (!hasTelegram(auth)) {
                return fail("messenger.notLinked", "service", kind.display());
            }
            auth.telegramId = null;
        } else {
            if (!hasDiscord(auth)) {
                return fail("messenger.notLinked", "service", kind.display());
            }
            auth.discordId = null;
        }
        if (!isLinkedAny(auth)) {
            auth.messengerTwoFactorEnabled = false;
            auth.messengerConfirmOnly = false;
        }
        plugin.getDataSource().updateAuth(auth);
        return ok(successKey, "player", auth.realName == null ? auth.name : auth.realName,
            "service", kind.display());
    }

    private static boolean isLinkedAny(PlayerAuth auth) {
        return hasTelegram(auth) || hasDiscord(auth);
    }

    public void beginVerify(PlayerRef player, PlayerAuth auth) {
        VerifyPending pending = new VerifyPending(
            player.getUuid(),
            player.getUsername(),
            plugin.getAuthService().getIp(player));
        pending.telegram = hasTelegram(auth);
        pending.discord = hasDiscord(auth);
        verifies.put(player.getUuid(), pending);
        String ip = pending.ip == null || pending.ip.isBlank() ? "?" : pending.ip;
        String text = plugin.getMessages().text("messenger.bot.2fa.request",
            "player", pending.playerName, "ip", ip);
        if (pending.telegram && telegramBot != null && hasTelegram(auth)) {
            telegramBot.sendTwoFactor(auth.telegramId, text, player.getUuid());
        }
        if (pending.discord && discordBot != null && hasDiscord(auth)) {
            discordBot.sendTwoFactor(auth.discordId, text, player.getUuid());
        }
    }

    public boolean cancelUi(PlayerRef player) {
        VerifyPending verify = verifies.remove(player.getUuid());
        if (verify != null) {
            return true;
        }
        clear(player.getUuid());
        return false;
    }

    void onBotCode(MessengerKind kind, String userId, String userName, String chatId, String rawCode) {
        String code = normalizeCode(rawCode);
        if (code.isEmpty()) {
            sendBotText(kind, chatId, plugin.getMessages().text("messenger.bot.invalidInput",
                "bot", botMention(kind)));
            return;
        }
        PlayerAuth already = kind == MessengerKind.TELEGRAM
            ? plugin.getDataSource().getAuthByTelegramId(userId)
            : plugin.getDataSource().getAuthByDiscordId(userId);
        if (already != null) {
            sendBotText(kind, chatId, plugin.getMessages().text("messenger.bot.alreadyLinked",
                "player", already.realName == null ? already.name : already.realName));
            return;
        }
        LinkPending pending = linksByBotCode.get(code);
        if (pending == null || pending.kind != kind || pending.phase != Phase.LINK_WAIT_BOT) {
            sendBotText(kind, chatId, plugin.getMessages().text("messenger.bot.unknownCode"));
            return;
        }
        if (System.currentTimeMillis() - pending.created > LINK_TTL_MS) {
            clear(pending.playerUuid);
            sendBotText(kind, chatId, plugin.getMessages().text("messenger.bot.unknownCode"));
            return;
        }
        pending.messengerUserId = userId;
        pending.messengerName = userName;
        pending.chatId = chatId;
        pending.confirmCode = randomCode(6);
        pending.phase = Phase.LINK_WAIT_GAME;
        sendBotText(kind, chatId, plugin.getMessages().text("messenger.bot.confirmCode",
            "player", pending.playerName,
            "code", pending.confirmCode));
        PlayerRef player = Universe.get().getPlayer(pending.playerUuid);
        if (player != null && player.isValid()) {
            PlayerAuth auth = plugin.getDataSource().getAuth(pending.playerName);
            boolean totp = TotpService.isBound(auth);
            String key = totp ? "messenger.link.confirmFromBotTotp" : "messenger.link.confirmFromBot";
            player.sendMessage(plugin.getMessages().get(key,
                "service", kind.display(),
                "name", orDash(userName)));
            AuthUi.runOnWorldThread(player, live -> {
                if (live.player().getPageManager().getCustomPage() instanceof MessengerLinkPage page) {
                    page.refreshTexts();
                } else {
                    // The player may have closed the page (e.g. via the "open bot" button) — reopen it
                    MessengerLinkPage page = new MessengerLinkPage(live.playerRef(), plugin);
                    live.player().getPageManager().openCustomPage(live.ref(), live.store(), page);
                    page.refreshTexts();
                }
            });
        }
    }

    /** @return true if the decision was applied; false for stale/expired/foreign button presses. */
    boolean onTwoFactorDecision(UUID playerUuid, boolean accept, String actorId) {
        VerifyPending pending = verifies.get(playerUuid);
        if (pending == null) {
            return false;
        }
        PlayerRef player = Universe.get().getPlayer(playerUuid);
        if (player == null || !player.isValid()) {
            verifies.remove(playerUuid);
            return false;
        }
        PlayerAuth auth = plugin.getDataSource().getAuth(pending.playerName);
        if (auth == null) {
            verifies.remove(playerUuid);
            return false;
        }
        boolean actorOk = (hasTelegram(auth) && actorId.equals(auth.telegramId))
            || (hasDiscord(auth) && actorId.equals(auth.discordId));
        if (!actorOk) {
            return false;
        }
        if (auth.blocked) {
            verifies.remove(playerUuid);
            AuthUi.closeAndDisconnect(player, plugin.getMessages().get("error.accountBlocked"));
            return false;
        }
        verifies.remove(playerUuid);
        if (accept) {
            AuthUi.runOnWorldThread(player, live -> {
                plugin.getAuthService().completeLogin(live.playerRef(), auth);
                AuthUi.close(live.store(), live.ref());
                live.playerRef().sendMessage(plugin.getMessages().get("login.success"));
            });
        } else {
            AuthUi.closeAndDisconnect(player, plugin.getMessages().get("messenger.denied"));
        }
        return true;
    }

    void onBotMode(MessengerKind kind, String userId, String chatId, String rawMode) {
        Boolean confirmOnly = parseMode(rawMode);
        if (confirmOnly == null) {
            sendBotText(kind, chatId, plugin.getMessages().text("messenger.bot.mode.usage"));
            return;
        }
        sendBotText(kind, chatId, botToggleMode(kind, userId, confirmOnly));
    }

    @Nullable
    private PlayerAuth linkedAccount(MessengerKind kind, String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return kind == MessengerKind.TELEGRAM
            ? plugin.getDataSource().getAuthByTelegramId(userId)
            : plugin.getDataSource().getAuthByDiscordId(userId);
    }

    public String botStatus(MessengerKind kind, String userId) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        String player = auth.realName == null ? auth.name : auth.realName;
        String online = onlinePlayer(auth) != null ? plugin.getMessages().text("state.online") : plugin.getMessages().text("state.offline");
        String lastLogin = auth.lastLogin > 0 ? Instant.ofEpochMilli(auth.lastLogin).toString() : "-";
        String ip = orDash(auth.lastIp);
        String geo = plugin.getGeoIpService().describe(auth.lastIp);
        return plugin.getMessages().text("messenger.bot.status",
            "player", player,
            "online", online,
            "lastLogin", lastLogin,
            "lastIp", ip,
            "lastGeo", geo,
            "notifications", boolText(auth.messengerNotificationsEnabled),
            "twoFactor", boolText(auth.messengerTwoFactorEnabled),
            "sessions", boolText(auth.messengerSessionsEnabled),
            "blocked", blockText(auth.blocked),
            "mode", plugin.getMessages().text(auth.messengerConfirmOnly
                ? "messenger.mode.confirmOnly"
                : "messenger.mode.passwordAndConfirm"));
    }

    public BotState botState(MessengerKind kind, String userId) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return new BotState(false, false, false, false);
        }
        return new BotState(true, auth.messengerTwoFactorEnabled, auth.messengerNotificationsEnabled, auth.messengerSessionsEnabled);
    }

    public String botResetPassword(MessengerKind kind, String userId) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        String newPassword = randomPassword(10);
        auth.password = plugin.getPasswordSecurity().computeHash(newPassword);
        plugin.getDataSource().updateAuth(auth);
        PlayerRef online = onlinePlayer(auth);
        if (online != null && online.isValid()) {
            AuthUi.closeAndDisconnect(online, plugin.getMessages().get("messenger.bot.passwordResetKick"));
        }
        return plugin.getMessages().text("messenger.bot.passwordReset", "password", newPassword);
    }

    public String botToggleBlock(MessengerKind kind, String userId) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        auth.blocked = !auth.blocked;
        plugin.getDataSource().updateAuth(auth);
        if (auth.blocked) {
            PlayerRef online = onlinePlayer(auth);
            if (online != null && online.isValid()) {
                AuthUi.closeAndDisconnect(online, plugin.getMessages().get("error.accountBlocked"));
            }
        }
        return plugin.getMessages().text(auth.blocked ? "messenger.bot.blocked" : "messenger.bot.unblocked");
    }

    public String botKick(MessengerKind kind, String userId) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        PlayerRef online = onlinePlayer(auth);
        if (online == null || !online.isValid()) {
            return plugin.getMessages().text("messenger.bot.kick.notOnline");
        }
        AuthUi.closeAndDisconnect(online, plugin.getMessages().get("messenger.bot.kick.reason"));
        return plugin.getMessages().text("messenger.bot.kick.done");
    }

    public String botUnlink(MessengerKind kind, String userId) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        if (kind == MessengerKind.TELEGRAM) {
            auth.telegramId = null;
        } else {
            auth.discordId = null;
        }
        if (!isLinkedAny(auth)) {
            auth.messengerTwoFactorEnabled = false;
            auth.messengerConfirmOnly = false;
        }
        plugin.getDataSource().updateAuth(auth);
        return plugin.getMessages().text("messenger.unlinked", "service", kind.display());
    }

    public String botToggleTwoFactor(MessengerKind kind, String userId, boolean enabled) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        auth.messengerTwoFactorEnabled = enabled;
        if (!enabled) {
            auth.messengerConfirmOnly = false;
        }
        plugin.getDataSource().updateAuth(auth);
        return plugin.getMessages().text(enabled ? "messenger.bot.settings.2faOn" : "messenger.bot.settings.2faOff");
    }

    public String botToggleNotifications(MessengerKind kind, String userId, boolean enabled) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        auth.messengerNotificationsEnabled = enabled;
        plugin.getDataSource().updateAuth(auth);
        return plugin.getMessages().text(enabled ? "messenger.bot.settings.notificationsOn" : "messenger.bot.settings.notificationsOff");
    }

    public String botToggleSessions(MessengerKind kind, String userId, boolean enabled) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        auth.messengerSessionsEnabled = enabled;
        plugin.getDataSource().updateAuth(auth);
        return plugin.getMessages().text(enabled ? "messenger.bot.settings.sessionsOn" : "messenger.bot.settings.sessionsOff");
    }

    public String botToggleMode(MessengerKind kind, String userId, boolean confirmOnly) {
        PlayerAuth auth = linkedAccount(kind, userId);
        if (auth == null) {
            return plugin.getMessages().text("messenger.bot.mode.notLinked");
        }
        auth.messengerConfirmOnly = confirmOnly;
        auth.messengerTwoFactorEnabled = true;
        plugin.getDataSource().updateAuth(auth);
        return plugin.getMessages().text("messenger.bot.mode.changed",
            "mode", plugin.getMessages().text(confirmOnly
                ? "messenger.mode.confirmOnly"
                : "messenger.mode.passwordAndConfirm"));
    }

    public String botHelpText() {
        return plugin.getMessages().text("messenger.bot.helpReply");
    }

    public void notifyLoginSuccess(PlayerAuth auth, String ip) {
        if (auth == null || !auth.messengerNotificationsEnabled) {
            return;
        }
        String text = plugin.getMessages().text("messenger.bot.loginSuccess",
            "player", auth.realName == null ? auth.name : auth.realName,
            "ip", ip == null || ip.isBlank() ? "?" : ip,
            "geo", plugin.getGeoIpService().describe(ip));
        if (hasTelegram(auth) && telegramBot != null) {
            telegramBot.sendText(auth.telegramId, text);
        }
        if (hasDiscord(auth) && discordBot != null) {
            discordBot.sendDirectText(auth.discordId, text);
        }
    }

    private String randomPassword(int len) {
        final char[] alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(chars);
    }

    @Nullable
    private static PlayerRef onlinePlayer(PlayerAuth auth) {
        if (auth == null || auth.uuid == null || auth.uuid.isBlank()) {
            return null;
        }
        try {
            return Universe.get().getPlayer(UUID.fromString(auth.uuid));
        } catch (Exception e) {
            return null;
        }
    }

    private String boolText(boolean value) {
        return value ? plugin.getMessages().text("state.enabled") : plugin.getMessages().text("state.disabled");
    }

    private String blockText(boolean value) {
        return value ? plugin.getMessages().text("state.on") : plugin.getMessages().text("state.off");
    }

    private void sendBotText(MessengerKind kind, String chatId, String text) {
        if (!notBlank(chatId) || !notBlank(text)) {
            return;
        }
        if (kind == MessengerKind.TELEGRAM && telegramBot != null) {
            telegramBot.sendText(chatId, text);
        } else if (kind == MessengerKind.DISCORD && discordBot != null) {
            discordBot.sendText(chatId, text);
        }
    }

    private String randomCode(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(chars);
    }

    static String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String extractCode(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("!")) {
            int space = trimmed.indexOf(' ');
            trimmed = space < 0 ? "" : trimmed.substring(space + 1).trim();
        }
        return normalizeCode(trimmed);
    }

    static Boolean parseMode(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("/")) {
            int space = v.indexOf(' ');
            v = space < 0 ? "" : v.substring(space + 1).trim();
        }
        return switch (v) {
            case "confirm_only", "confirm", "only", "bot", "2", "только", "бот" -> true;
            case "password_and_confirm", "password", "pass", "mixed", "1", "пароль", "пароль+бот" -> false;
            default -> null;
        };
    }

    private AuthService.Result ok(String key, String... params) {
        return new AuthService.Result(AuthService.Result.Status.SUCCESS, plugin.getMessages().get(key, params));
    }

    private AuthService.Result fail(String key, String... params) {
        return new AuthService.Result(AuthService.Result.Status.FAILURE, plugin.getMessages().get(key, params));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
