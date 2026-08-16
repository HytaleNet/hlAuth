package com.hlauth.hytale.messenger;

import com.hlauth.hytale.HlAuthPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/** Telegram Bot API long-poll client. */
final class TelegramBot {

    private final MessengerService messenger;
    private final HlAuthPlugin plugin;
    private final String token;
    private final java.net.Proxy restProxy;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile String username = "";
    private volatile long offset;

    TelegramBot(MessengerService messenger, HlAuthPlugin plugin, TelegramBotConfig config) {
        this.messenger = messenger;
        this.plugin = plugin;
        this.token = config.token == null ? "" : config.token.trim();
        this.restProxy = proxyFrom(config, plugin);
    }

    @Nullable
    private static java.net.Proxy proxyFrom(TelegramBotConfig config, HlAuthPlugin plugin) {
        if (!config.proxyEnabled || config.proxyHost == null || config.proxyHost.isBlank()) {
            return null;
        }
        java.net.InetSocketAddress addr =
            new java.net.InetSocketAddress(config.proxyHost.trim(), config.proxyPort);
        boolean socks = "socks".equalsIgnoreCase(config.proxyType == null ? "" : config.proxyType.trim());
        plugin.getLogger().atInfo().log("Telegram bot: using %s proxy %s:%d",
            socks ? "SOCKS" : "HTTP", config.proxyHost.trim(), config.proxyPort);
        return new java.net.Proxy(socks ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP, addr);
    }

    String username() {
        return username;
    }

    /** All outbound API calls run here so game/world threads never block on network I/O. */
    private final java.util.concurrent.ExecutorService sender =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hlAuth-Telegram-send");
            t.setDaemon(true);
            return t;
        });

    void start() {
        running = true;
        thread = new Thread(this::loop, "hlAuth-Telegram");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        sender.shutdownNow();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Fire-and-forget POST to the Bot API on the sender thread. */
    private void postAsync(String method, JsonObject body, @Nullable String errorLog) {
        if (sender.isShutdown()) {
            return;
        }
        sender.execute(() -> {
            try {
                HttpJson.post(api(method), body.toString(), 8_000, 15_000, null, restProxy);
            } catch (Exception e) {
                if (errorLog != null) {
                    plugin.getLogger().atWarning().withCause(e).log(errorLog);
                }
            }
        });
    }

    private String api(String method) {
        String base = messenger.telegramApiUrl();
        if (base == null || base.isBlank()) {
            base = "https://api.telegram.org";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/bot" + token + "/" + method;
    }

    private void loop() {
        try {
            JsonObject me = HttpJson.parse(HttpJson.get(api("getMe"), 8_000, 15_000, null, restProxy)).getAsJsonObject();
            if (me.get("ok") != null && me.get("ok").getAsBoolean()) {
                JsonObject result = me.getAsJsonObject("result");
                if (result != null && result.has("username")) {
                    username = result.get("username").getAsString();
                }
            }
            plugin.getLogger().atInfo().log("Telegram bot connected (%s)",
                username.isEmpty() ? "ok" : "@" + username);
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e).log("Telegram getMe failed");
        }
        while (running) {
            try {
                String url = api("getUpdates") + "?timeout=25&allowed_updates=%5B%22message%22%2C%22callback_query%22%5D"
                    + (offset > 0 ? "&offset=" + offset : "");
                JsonObject root = HttpJson.parse(HttpJson.get(url, 8_000, 35_000, null, restProxy)).getAsJsonObject();
                if (root.get("ok") == null || !root.get("ok").getAsBoolean()) {
                    sleepQuiet(3_000);
                    continue;
                }
                JsonArray result = root.getAsJsonArray("result");
                if (result == null) {
                    continue;
                }
                for (JsonElement el : result) {
                    JsonObject update = el.getAsJsonObject();
                    offset = update.get("update_id").getAsLong() + 1;
                    handle(update);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running) {
                    plugin.getLogger().atWarning().withCause(e).log("Telegram getUpdates failed");
                    sleepQuiet(3_000);
                }
            }
        }
    }

    private void handle(JsonObject update) {
        if (update.has("callback_query")) {
            JsonObject cb = update.getAsJsonObject("callback_query");
            String id = text(cb, "id");
            JsonObject from = cb.getAsJsonObject("from");
            String userId = from == null ? "" : String.valueOf(from.get("id").getAsLong());
            String data = text(cb, "data");
            JsonObject msg = cb.getAsJsonObject("message");
            String chatId = msg == null ? userId : chatId(msg);
            if (data.startsWith("A:") || data.startsWith("D:")) {
                boolean accept = data.charAt(0) == 'A';
                boolean applied = false;
                try {
                    UUID uuid = uuidFrom(data.substring(2));
                    applied = messenger.onTwoFactorDecision(uuid, accept, userId);
                } catch (Exception ignored) {
                    // stale button
                }
                answerCallback(id, applied ? null : plugin.getMessages().text("messenger.bot.2fa.expired"));
                long messageId = msg != null && msg.has("message_id") ? msg.get("message_id").getAsLong() : 0;
                if (messageId > 0) {
                    removeInlineKeyboard(chatId, messageId);
                }
                if (applied) {
                    sendText(chatId, plugin.getMessages().text(accept
                        ? "messenger.bot.2fa.accept"
                        : "messenger.bot.2fa.decline"));
                }
                return;
            }
            answerCallback(id, null);
            if (data.startsWith("m:") || data.startsWith("s:")) {
                handleMenuAction(userId, chatId, data);
            }
            return;
        }
        if (!update.has("message")) {
            return;
        }
        JsonObject message = update.getAsJsonObject("message");
        JsonObject from = message.getAsJsonObject("from");
        if (from == null || (from.has("is_bot") && from.get("is_bot").getAsBoolean())) {
            return;
        }
        String text = text(message, "text");
        if (text.isEmpty()) {
            return;
        }
        String userId = String.valueOf(from.get("id").getAsLong());
        String userName = displayName(from);
        String chatId = chatId(message);
        if (text.startsWith("/start") || text.equalsIgnoreCase("/menu")) {
            sendMainMenu(chatId);
            return;
        }
        String action = actionFromText(text);
        if (action != null) {
            handleMenuAction(userId, chatId, action);
            return;
        }
        if (text.toLowerCase().startsWith("/mode")) {
            messenger.onBotMode(MessengerKind.TELEGRAM, userId, chatId, text);
            return;
        }
        // A linked account gets the status panel instead of "already linked"
        if (messenger.botState(MessengerKind.TELEGRAM, userId).linked()) {
            sendText(chatId, messenger.botStatus(MessengerKind.TELEGRAM, userId));
            return;
        }
        messenger.onBotCode(MessengerKind.TELEGRAM, userId, userName, chatId, MessengerService.extractCode(text));
    }

    private void handleMenuAction(String userId, String chatId, String action) {
        // The reply keyboard is persistent, so action results are plain messages:
        // the menu is only re-sent on /start, /menu and "back" from settings.
        switch (action) {
            case "m:status" -> sendText(chatId, messenger.botStatus(MessengerKind.TELEGRAM, userId));
            case "m:reset" -> sendText(chatId, messenger.botResetPassword(MessengerKind.TELEGRAM, userId));
            case "m:block" -> sendText(chatId, messenger.botToggleBlock(MessengerKind.TELEGRAM, userId));
            case "m:unlink" -> sendText(chatId, messenger.botUnlink(MessengerKind.TELEGRAM, userId));
            case "m:kick" -> sendText(chatId, messenger.botKick(MessengerKind.TELEGRAM, userId));
            case "m:help" -> sendHelp(chatId);
            case "m:settings" -> sendSettingsMenu(chatId, messenger.botState(MessengerKind.TELEGRAM, userId));
            case "s:mode:password" -> sendText(chatId, messenger.botToggleMode(MessengerKind.TELEGRAM, userId, false));
            case "s:mode:only" -> sendText(chatId, messenger.botToggleMode(MessengerKind.TELEGRAM, userId, true));
            case "s:2fa:on" -> sendText(chatId, messenger.botToggleTwoFactor(MessengerKind.TELEGRAM, userId, true));
            case "s:2fa:off" -> sendText(chatId, messenger.botToggleTwoFactor(MessengerKind.TELEGRAM, userId, false));
            case "s:notif:on" -> sendText(chatId, messenger.botToggleNotifications(MessengerKind.TELEGRAM, userId, true));
            case "s:notif:off" -> sendText(chatId, messenger.botToggleNotifications(MessengerKind.TELEGRAM, userId, false));
            case "s:sess:on" -> sendText(chatId, messenger.botToggleSessions(MessengerKind.TELEGRAM, userId, true));
            case "s:sess:off" -> sendText(chatId, messenger.botToggleSessions(MessengerKind.TELEGRAM, userId, false));
            case "s:back" -> sendMainMenu(chatId);
            default -> {
            }
        }
    }

    private void sendMainMenu(String chatId) {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", plugin.getMessages().text("messenger.bot.menu.title"));
        JsonArray rows = new JsonArray();
        rows.add(row(btn(plugin.getMessages().text("messenger.bot.menu.status"), "m:status")));
        rows.add(row(
            btn(plugin.getMessages().text("messenger.bot.menu.reset"), "m:reset"),
            btn(plugin.getMessages().text("messenger.bot.menu.settings"), "m:settings")));
        rows.add(row(
            btn(plugin.getMessages().text("messenger.bot.menu.block"), "m:block"),
            btn(plugin.getMessages().text("messenger.bot.menu.unlink"), "m:unlink"),
            btn(plugin.getMessages().text("messenger.bot.menu.kick"), "m:kick")));
        rows.add(row(btn(plugin.getMessages().text("messenger.bot.menu.help"), "m:help")));
        JsonObject markup = new JsonObject();
        markup.add("keyboard", rows);
        markup.addProperty("resize_keyboard", true);
        markup.addProperty("one_time_keyboard", false);
        body.add("reply_markup", markup);
        postAsync("sendMessage", body, "Telegram main menu failed");
    }

    private void sendSettingsMenu(String chatId, MessengerService.BotState state) {
        if (!state.linked()) {
            sendText(chatId, plugin.getMessages().text("messenger.bot.mode.notLinked"));
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", plugin.getMessages().text("messenger.bot.settings.title"));
        JsonArray rows = new JsonArray();
        rows.add(row(
            btn(plugin.getMessages().text("messenger.bot.settings.modePassword"), "s:mode:password"),
            btn(plugin.getMessages().text("messenger.bot.settings.modeOnly"), "s:mode:only")));
        rows.add(row(
            btn(plugin.getMessages().text("messenger.bot.settings.2faOn"), "s:2fa:on"),
            btn(plugin.getMessages().text("messenger.bot.settings.2faOff"), "s:2fa:off")));
        rows.add(row(
            btn(plugin.getMessages().text("messenger.bot.settings.notificationsOn"), "s:notif:on"),
            btn(plugin.getMessages().text("messenger.bot.settings.notificationsOff"), "s:notif:off")));
        rows.add(row(
            btn(plugin.getMessages().text("messenger.bot.settings.sessionsOn"), "s:sess:on"),
            btn(plugin.getMessages().text("messenger.bot.settings.sessionsOff"), "s:sess:off")));
        rows.add(row(btn(plugin.getMessages().text("messenger.bot.settings.close"), "s:back")));
        JsonObject markup = new JsonObject();
        markup.add("keyboard", rows);
        markup.addProperty("resize_keyboard", true);
        markup.addProperty("one_time_keyboard", false);
        body.add("reply_markup", markup);
        postAsync("sendMessage", body, "Telegram settings menu failed");
    }

    /** One help message; the configured help URL is attached as an inline link button. */
    private void sendHelp(String chatId) {
        String url = messenger.helpUrl(MessengerKind.TELEGRAM);
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", messenger.botHelpText());
        if (url != null && !url.isBlank()) {
            JsonObject helpBtn = new JsonObject();
            helpBtn.addProperty("text", plugin.getMessages().text("messenger.bot.helpLinkButton"));
            helpBtn.addProperty("url", url);
            JsonArray row = new JsonArray();
            row.add(helpBtn);
            JsonArray rows = new JsonArray();
            rows.add(row);
            JsonObject markup = new JsonObject();
            markup.add("inline_keyboard", rows);
            body.add("reply_markup", markup);
        }
        postAsync("sendMessage", body, "Telegram help message failed");
    }

    private String actionFromText(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        if (matchesButton(t, "messenger.bot.menu.status")) return "m:status";
        if (matchesButton(t, "messenger.bot.menu.reset")) return "m:reset";
        if (matchesButton(t, "messenger.bot.menu.settings")) return "m:settings";
        if (matchesButton(t, "messenger.bot.menu.block")) return "m:block";
        if (matchesButton(t, "messenger.bot.menu.unlink")) return "m:unlink";
        if (matchesButton(t, "messenger.bot.menu.kick")) return "m:kick";
        if (matchesButton(t, "messenger.bot.menu.help")) return "m:help";
        if (matchesButton(t, "messenger.bot.settings.modePassword")) return "s:mode:password";
        if (matchesButton(t, "messenger.bot.settings.modeOnly")) return "s:mode:only";
        if (matchesButton(t, "messenger.bot.settings.2faOn")) return "s:2fa:on";
        if (matchesButton(t, "messenger.bot.settings.2faOff")) return "s:2fa:off";
        if (matchesButton(t, "messenger.bot.settings.notificationsOn")) return "s:notif:on";
        if (matchesButton(t, "messenger.bot.settings.notificationsOff")) return "s:notif:off";
        if (matchesButton(t, "messenger.bot.settings.sessionsOn")) return "s:sess:on";
        if (matchesButton(t, "messenger.bot.settings.sessionsOff")) return "s:sess:off";
        if (matchesButton(t, "messenger.bot.settings.close")) return "s:back";
        return null;
    }

    private boolean matchesButton(String incoming, String key) {
        String label = plugin.getMessages().text(key).toLowerCase(Locale.ROOT);
        return incoming.equals(label) || incoming.equals(stripEmoji(label));
    }

    private static String stripEmoji(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}\\s]", "").trim();
    }

    private static JsonObject btn(String text, String callback) {
        JsonObject b = new JsonObject();
        b.addProperty("text", text);
        return b;
    }

    private static JsonArray row(JsonObject... buttons) {
        JsonArray row = new JsonArray();
        for (JsonObject b : buttons) {
            row.add(b);
        }
        return row;
    }

    void sendText(String chatId, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text);
        postAsync("sendMessage", body, "Telegram sendMessage failed");
    }

    void sendTwoFactor(String chatId, String text, UUID playerUuid) {
        JsonObject accept = new JsonObject();
        accept.addProperty("text", plugin.getMessages().text("messenger.bot.2fa.allow"));
        accept.addProperty("callback_data", "A:" + compact(playerUuid));
        JsonObject decline = new JsonObject();
        decline.addProperty("text", plugin.getMessages().text("messenger.bot.2fa.deny"));
        decline.addProperty("callback_data", "D:" + compact(playerUuid));
        JsonArray row = new JsonArray();
        row.add(accept);
        row.add(decline);
        JsonArray keyboard = new JsonArray();
        keyboard.add(row);
        JsonObject markup = new JsonObject();
        markup.add("inline_keyboard", keyboard);
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text);
        body.add("reply_markup", markup);
        postAsync("sendMessage", body, "Telegram 2FA message failed");
    }

    private void answerCallback(String id, @Nullable String toastText) {
        if (id.isEmpty()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("callback_query_id", id);
        if (toastText != null && !toastText.isBlank()) {
            body.addProperty("text", toastText);
        }
        postAsync("answerCallbackQuery", body, null);
    }

    /** Removes the inline keyboard from an already-sent message (e.g. after a 2FA decision). */
    private void removeInlineKeyboard(String chatId, long messageId) {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("message_id", messageId);
        body.add("reply_markup", new JsonObject());
        postAsync("editMessageReplyMarkup", body, null);
    }

    private static String chatId(JsonObject message) {
        JsonObject chat = message.getAsJsonObject("chat");
        if (chat == null || !chat.has("id")) {
            return "";
        }
        return String.valueOf(chat.get("id").getAsLong());
    }

    private static String displayName(JsonObject from) {
        String user = text(from, "username");
        if (!user.isEmpty()) {
            return "@" + user;
        }
        String first = text(from, "first_name");
        String last = text(from, "last_name");
        if (last.isEmpty()) {
            return first;
        }
        return (first + " " + last).trim();
    }

    private static String text(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    static String compact(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    static UUID uuidFrom(String compact) {
        String hex = compact.trim();
        if (hex.length() != 32) {
            return UUID.fromString(compact);
        }
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
            + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
