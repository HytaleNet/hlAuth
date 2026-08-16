package com.hlauth.hytale.messenger;

import com.hlauth.hytale.HlAuthPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal Discord Gateway + REST bot (DMs and button clicks). */
final class DiscordBot implements WebSocket.Listener {

    // GUILDS + DIRECT_MESSAGES. DM content is always delivered, so the privileged
    // MESSAGE_CONTENT intent is NOT requested (it causes close 4014 when not enabled in the dev portal).
    private static final int INTENTS = 1 | (1 << 12);
    private static final String API = "https://discord.com/api/v10";

    private final MessengerService messenger;
    private final HlAuthPlugin plugin;
    private final DiscordBotConfig config;
    private final String token;
    private final HttpClient http;
    private final java.net.Proxy restProxy;
    private final StringBuilder textBuf = new StringBuilder();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hlAuth-Discord-hb");
        t.setDaemon(true);
        return t;
    });
    /** All REST calls run here so game/world threads (and the heartbeat) never block on network I/O. */
    private final java.util.concurrent.ExecutorService rest = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hlAuth-Discord-rest");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running;
    private volatile WebSocket socket;
    private volatile String username = "";
    private volatile String botUserId = "";
    private volatile String applicationId = "";
    private volatile boolean slashCommandsRegistered;
    private volatile Integer lastSeq;
    private volatile ScheduledFuture<?> heartbeat;
    private final AtomicInteger reconnects = new AtomicInteger();

    DiscordBot(MessengerService messenger, HlAuthPlugin plugin, DiscordBotConfig config) {
        this.messenger = messenger;
        this.plugin = plugin;
        this.config = config;
        this.token = config.token == null ? "" : config.token.trim();

        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        java.net.Proxy proxy = null;
        if (config.proxyEnabled && config.proxyHost != null && !config.proxyHost.isBlank()) {
            java.net.InetSocketAddress addr =
                new java.net.InetSocketAddress(config.proxyHost.trim(), config.proxyPort);
            boolean socks = "socks".equalsIgnoreCase(config.proxyType == null ? "" : config.proxyType.trim());
            proxy = new java.net.Proxy(socks ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP, addr);
            if (socks) {
                plugin.getLogger().atWarning().log(
                    "Discord proxy type 'socks': the gateway (WebSocket) cannot use SOCKS in Java - "
                        + "REST goes through the proxy, the gateway connects directly. "
                        + "Use the HTTP port of your proxy (proxyType: \"http\") for full coverage.");
            } else {
                builder.proxy(java.net.ProxySelector.of(addr));
            }
            plugin.getLogger().atInfo().log("Discord bot: using %s proxy %s:%d",
                socks ? "SOCKS" : "HTTP", config.proxyHost.trim(), config.proxyPort);
        }
        this.restProxy = proxy;
        this.http = builder.build();
    }

    String username() {
        return username;
    }

    void start() {
        running = true;
        restAsync(this::connect);
    }

    /** Runs a REST call on the sender thread; never blocks the caller. */
    private void restAsync(Runnable task) {
        if (!rest.isShutdown()) {
            rest.execute(task);
        }
    }

    void stop() {
        running = false;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "stop");
            } catch (Exception ignored) {
                // closing
            }
        }
        scheduler.shutdownNow();
        rest.shutdownNow();
    }

    private final java.util.concurrent.atomic.AtomicBoolean reconnectPending =
        new java.util.concurrent.atomic.AtomicBoolean();

    private void connect() {
        if (!running) {
            return;
        }
        reconnectPending.set(false);
        try {
            plugin.getLogger().atInfo().log("Discord bot: connecting to gateway...");
            JsonObject gateway = HttpJson.parse(HttpJson.get(API + "/gateway", 10_000, 15_000, authHeader(), restProxy))
                .getAsJsonObject();
            String url = gateway.get("url").getAsString() + "/?v=10&encoding=json";
            plugin.getLogger().atInfo().log("Discord bot: gateway REST ok, opening WebSocket %s", url);
            http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .header("User-Agent", "hlAuth")
                .buildAsync(URI.create(url), this)
                .orTimeout(30, TimeUnit.SECONDS)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        plugin.getLogger().atWarning().log(
                            "Discord gateway connect failed: %s (blocked network? try proxyEnabled in discord-bot.yml)",
                            String.valueOf(err));
                        scheduleReconnect();
                    }
                    // On success the socket is already assigned in onOpen (before any frame arrives).
                });
        } catch (Exception e) {
            plugin.getLogger().atWarning().log(
                "Discord REST /gateway failed: %s (blocked network? try proxyEnabled in discord-bot.yml)", e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running || !reconnectPending.compareAndSet(false, true)) {
            return;
        }
        int n = Math.min(reconnects.incrementAndGet(), 6);
        scheduler.schedule(() -> restAsync(this::connect), n * 5L, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        // Must be assigned here (not in buildAsync callbacks): HELLO may arrive before
        // those callbacks run, and sendIdentify() would silently drop the frame on a null socket.
        socket = webSocket;
        plugin.getLogger().atInfo().log("Discord bot: gateway socket open, waiting for HELLO");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuf.append(data);
        if (last) {
            String payload = textBuf.toString();
            textBuf.setLength(0);
            handlePayload(payload);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (running) {
            plugin.getLogger().atWarning().log("Discord gateway closed (%d %s)%s",
                statusCode, reason, closeHint(statusCode));
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (statusCode != 4004) {
                scheduleReconnect();
            }
        }
        return null;
    }

    private static String closeHint(int code) {
        return switch (code) {
            case 4004 -> " - invalid bot token, check discord-bot.yml (bot will not reconnect)";
            case 4014 -> " - disallowed intents, enable them in the Discord Developer Portal";
            case 4013 -> " - invalid intents";
            default -> "";
        };
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (running) {
            plugin.getLogger().atWarning().log("Discord gateway error: %s (blocked network? try proxyEnabled in discord-bot.yml)", error);
            scheduleReconnect();
        }
    }

    private void handlePayload(String payload) {
        try {
            handlePayload0(payload);
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e).log("Discord gateway payload handling failed");
        }
    }

    private void handlePayload0(String payload) {
        JsonObject root;
        try {
            root = HttpJson.parse(payload).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        if (root.has("s") && !root.get("s").isJsonNull()) {
            lastSeq = root.get("s").getAsInt();
        }
        int op = root.get("op").getAsInt();
        if (op == 7 || op == 9) {
            // Gateway asks us to reconnect / session invalidated
            WebSocket ws = socket;
            socket = null;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
                } catch (Exception ignored) {
                    // already closing
                }
            }
            scheduleReconnect();
            return;
        }
        if (op == 10) {
            JsonObject d = root.getAsJsonObject("d");
            long interval = d.get("heartbeat_interval").getAsLong();
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            heartbeat = scheduler.scheduleAtFixedRate(this::sendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
            sendIdentify();
            return;
        }
        if (op == 0) {
            String type = root.has("t") && !root.get("t").isJsonNull() ? root.get("t").getAsString() : "";
            JsonObject d = root.getAsJsonObject("d");
            if ("READY".equals(type) && d != null) {
                JsonObject user = d.getAsJsonObject("user");
                if (user != null) {
                    username = text(user, "username");
                    botUserId = text(user, "id");
                }
                JsonObject app = d.getAsJsonObject("application");
                applicationId = app != null ? text(app, "id") : botUserId;
                reconnects.set(0);
                plugin.getLogger().atInfo().log("Discord bot connected (%s)", username);
                restAsync(this::registerSlashCommands);
            } else if ("MESSAGE_CREATE".equals(type) && d != null) {
                onMessage(d);
            } else if ("INTERACTION_CREATE".equals(type) && d != null) {
                onInteraction(d);
            }
        }
    }

    private void sendIdentify() {
        JsonObject props = new JsonObject();
        props.addProperty("os", "java");
        props.addProperty("browser", "hlAuth");
        props.addProperty("device", "hlAuth");
        JsonObject d = new JsonObject();
        d.addProperty("token", token);
        d.addProperty("intents", INTENTS);
        d.add("properties", props);
        d.add("presence", presence());
        JsonObject root = new JsonObject();
        root.addProperty("op", 2);
        root.add("d", d);
        sendJson(root.toString());
    }

    /** Presence from discord-bot.yml: status (online/idle/dnd/invisible) + optional activity. */
    private JsonObject presence() {
        JsonObject p = new JsonObject();
        String status = config.presenceStatus == null || config.presenceStatus.isBlank()
            ? "online" : config.presenceStatus.trim().toLowerCase(java.util.Locale.ROOT);
        p.addProperty("status", status);
        p.add("since", com.google.gson.JsonNull.INSTANCE);
        p.addProperty("afk", false);
        JsonArray activities = new JsonArray();
        if (config.activityText != null && !config.activityText.isBlank()) {
            JsonObject activity = new JsonObject();
            activity.addProperty("name", config.activityText.trim());
            activity.addProperty("type", activityType());
            activities.add(activity);
        }
        p.add("activities", activities);
        return p;
    }

    private int activityType() {
        String type = config.activityType == null ? "" : config.activityType.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "streaming" -> 1;
            case "listening" -> 2;
            case "watching" -> 3;
            case "competing" -> 5;
            default -> 0; // playing
        };
    }

    /** Registers the global slash commands (/menu, /status, ...) once per session. */
    private void registerSlashCommands() {
        if (slashCommandsRegistered) {
            return;
        }
        String appId = applicationId == null || applicationId.isEmpty() ? botUserId : applicationId;
        if (appId == null || appId.isEmpty()) {
            return;
        }
        try {
            var msg = plugin.getMessages();
            JsonArray commands = new JsonArray();
            commands.add(slashCommand("menu", msg.text("messenger.bot.menu.title")));
            commands.add(slashCommand("status", msg.text("messenger.bot.menu.status")));
            commands.add(slashCommand("resetpassword", msg.text("messenger.bot.menu.reset")));
            commands.add(slashCommand("settings", msg.text("messenger.bot.menu.settings")));
            commands.add(slashCommand("block", msg.text("messenger.bot.menu.block")));
            commands.add(slashCommand("unlink", msg.text("messenger.bot.menu.unlink")));
            commands.add(slashCommand("kick", msg.text("messenger.bot.menu.kick")));
            commands.add(slashCommand("help", msg.text("messenger.bot.menu.help")));
            HttpJson.put(API + "/applications/" + appId + "/commands", commands.toString(),
                10_000, 15_000, authHeader(), restProxy);
            slashCommandsRegistered = true;
            plugin.getLogger().atInfo().log("Discord bot: %d slash commands registered", commands.size());
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e).log("Discord slash command registration failed");
        }
    }

    private static JsonObject slashCommand(String name, String description) {
        JsonObject c = new JsonObject();
        c.addProperty("name", name);
        c.addProperty("type", 1);
        String desc = description == null || description.isBlank() ? name : description.trim();
        if (desc.length() > 100) {
            desc = desc.substring(0, 100);
        }
        c.addProperty("description", desc);
        return c;
    }

    private void sendHeartbeat() {
        JsonObject root = new JsonObject();
        root.addProperty("op", 1);
        if (lastSeq == null) {
            root.add("d", com.google.gson.JsonNull.INSTANCE);
        } else {
            root.addProperty("d", lastSeq);
        }
        sendJson(root.toString());
    }

    private void sendJson(String json) {
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendText(json, true).whenComplete((r, err) -> {
                if (err != null) {
                    plugin.getLogger().atWarning().log("Discord gateway send failed: %s", String.valueOf(err));
                }
            });
        }
    }

    private void onMessage(JsonObject d) {
        JsonObject author = d.getAsJsonObject("author");
        if (author == null) {
            return;
        }
        if (author.has("bot") && author.get("bot").getAsBoolean()) {
            return;
        }
        String authorId = text(author, "id");
        if (authorId.equals(botUserId)) {
            return;
        }
        String channelId = text(d, "channel_id");
        String content = text(d, "content");
        if (content.isEmpty()) {
            return;
        }
        // A linked account gets the status panel on any message
        if (messenger.botState(MessengerKind.DISCORD, authorId).linked()) {
            sendStatusPanel(channelId, authorId);
            return;
        }
        String lower = content.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("/start") || lower.equals("/menu") || lower.equals("!menu")
                || lower.equals("menu") || lower.equals("РјРµРЅСЋ")) {
            sendMainMenu(channelId);
            return;
        }
        String userName = text(author, "global_name");
        if (userName.isEmpty()) {
            userName = text(author, "username");
        }
        String code = MessengerService.extractCode(content);
        if (code.isEmpty()) {
            // Not a code: explain and show the menu instead of a silent ignore
            sendText(channelId, plugin.getMessages().text("messenger.bot.invalidInput",
                "bot", username));
            sendMainMenu(channelId);
            return;
        }
        messenger.onBotCode(MessengerKind.DISCORD, authorId, userName, channelId, code);
    }

    /** Status embed + main menu buttons (shown to linked accounts on any message). */
    private void sendStatusPanel(String channelId, String userId) {
        JsonObject body = new JsonObject();
        body.add("embeds", embeds(messenger.botStatus(MessengerKind.DISCORD, userId)));
        body.add("components", mainMenuComponents());
        restAsync(() -> {
            try {
                HttpJson.post(API + "/channels/" + channelId + "/messages", body.toString(),
                    10_000, 15_000, authHeader(), restProxy);
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e).log("Discord status panel failed");
            }
        });
    }

    private void onInteraction(JsonObject d) {
        int type = d.has("type") ? d.get("type").getAsInt() : 0;
        String userId = interactionUserId(d);
        String id = text(d, "id");
        String tokenIx = text(d, "token");
        if (type == 2) {
            // Slash command
            JsonObject data = d.getAsJsonObject("data");
            String name = data == null ? "" : text(data, "name");
            String action = switch (name) {
                case "menu" -> "s:back";
                case "status" -> "m:status";
                case "resetpassword" -> "m:reset";
                case "settings" -> "m:settings";
                case "block" -> "m:block";
                case "unlink" -> "m:unlink";
                case "kick" -> "m:kick";
                case "help" -> "m:help";
                default -> null;
            };
            if (action != null) {
                handleMenuInteraction(id, tokenIx, userId, action);
            }
            return;
        }
        if (type != 3) {
            return;
        }
        JsonObject data = d.getAsJsonObject("data");
        String customId = data == null ? "" : text(data, "custom_id");
        if (customId.startsWith("A:") || customId.startsWith("D:")) {
            boolean accept = customId.charAt(0) == 'A';
            boolean applied = false;
            try {
                UUID uuid = TelegramBot.uuidFrom(customId.substring(2));
                applied = messenger.onTwoFactorDecision(uuid, accept, userId);
            } catch (Exception ignored) {
                // stale button
            }
            String content = applied
                ? plugin.getMessages().text(accept ? "messenger.bot.2fa.accept" : "messenger.bot.2fa.decline")
                : plugin.getMessages().text("messenger.bot.2fa.expired");
            // Type 7 (UPDATE_MESSAGE) replaces the prompt and strips the buttons,
            // so the same request cannot be answered twice.
            updateInteractionMessage(id, tokenIx, content);
            return;
        }
        if (customId.startsWith("m:") || customId.startsWith("s:")) {
            handleMenuInteraction(id, tokenIx, userId, customId);
        }
    }

    private static String interactionUserId(JsonObject d) {
        JsonObject user = d.getAsJsonObject("user");
        if (user == null && d.has("member")) {
            JsonObject member = d.getAsJsonObject("member");
            if (member != null) {
                user = member.getAsJsonObject("user");
            }
        }
        return user == null ? "" : text(user, "id");
    }

    private void handleMenuInteraction(String id, String token, String userId, String action) {
        var msg = plugin.getMessages();
        switch (action) {
            case "m:status" -> ackInteraction(id, token,
                messenger.botStatus(MessengerKind.DISCORD, userId), null);
            case "m:reset" -> ackInteraction(id, token,
                messenger.botResetPassword(MessengerKind.DISCORD, userId), null);
            case "m:block" -> ackInteraction(id, token,
                messenger.botToggleBlock(MessengerKind.DISCORD, userId), null);
            case "m:unlink" -> ackInteraction(id, token,
                messenger.botUnlink(MessengerKind.DISCORD, userId), null);
            case "m:kick" -> ackInteraction(id, token,
                messenger.botKick(MessengerKind.DISCORD, userId), null);
            case "m:help" -> ackInteraction(id, token, messenger.botHelpText(), helpComponents());
            case "m:settings" -> {
                MessengerService.BotState state = messenger.botState(MessengerKind.DISCORD, userId);
                if (!state.linked()) {
                    ackInteraction(id, token, msg.text("messenger.bot.mode.notLinked"), null);
                } else {
                    ackInteraction(id, token, msg.text("messenger.bot.settings.title"), settingsComponents());
                }
            }
            case "s:mode:password" -> ackInteraction(id, token,
                messenger.botToggleMode(MessengerKind.DISCORD, userId, false), null);
            case "s:mode:only" -> ackInteraction(id, token,
                messenger.botToggleMode(MessengerKind.DISCORD, userId, true), null);
            case "s:2fa:on" -> ackInteraction(id, token,
                messenger.botToggleTwoFactor(MessengerKind.DISCORD, userId, true), null);
            case "s:2fa:off" -> ackInteraction(id, token,
                messenger.botToggleTwoFactor(MessengerKind.DISCORD, userId, false), null);
            case "s:notif:on" -> ackInteraction(id, token,
                messenger.botToggleNotifications(MessengerKind.DISCORD, userId, true), null);
            case "s:notif:off" -> ackInteraction(id, token,
                messenger.botToggleNotifications(MessengerKind.DISCORD, userId, false), null);
            case "s:sess:on" -> ackInteraction(id, token,
                messenger.botToggleSessions(MessengerKind.DISCORD, userId, true), null);
            case "s:sess:off" -> ackInteraction(id, token,
                messenger.botToggleSessions(MessengerKind.DISCORD, userId, false), null);
            case "s:back" -> ackInteraction(id, token, menuText(), mainMenuComponents());
            default -> ackInteraction(id, token, msg.text("messenger.bot.invalidAction"), null);
        }
    }

    /** Builds the embeds array used by every bot message. */
    private JsonArray embeds(String text) {
        JsonObject embed = new JsonObject();
        embed.addProperty("description", text == null ? "" : text);
        embed.addProperty("color", embedColor());
        JsonArray array = new JsonArray();
        array.add(embed);
        return array;
    }

    private int embedColor() {
        String hex = config.embedColor == null ? "" : config.embedColor.trim().replace("#", "");
        if (!hex.isEmpty()) {
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 0x5865F2; // Discord blurple
    }

    void sendText(String channelId, String text) {
        JsonObject body = new JsonObject();
        body.add("embeds", embeds(text));
        restAsync(() -> {
            try {
                HttpJson.post(API + "/channels/" + channelId + "/messages", body.toString(),
                    10_000, 15_000, authHeader(), restProxy);
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e).log("Discord sendMessage failed");
            }
        });
    }

    void sendDirectText(String userId, String text) {
        JsonArray embeds = embeds(text);
        restAsync(() -> {
            try {
                String channelId = openDmChannel(userId);
                if (channelId.isEmpty()) {
                    return;
                }
                JsonObject body = new JsonObject();
                body.add("embeds", embeds);
                HttpJson.post(API + "/channels/" + channelId + "/messages", body.toString(),
                    10_000, 15_000, authHeader(), restProxy);
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e).log("Discord DM send failed");
            }
        });
    }

    void sendTwoFactor(String userId, String text, UUID playerUuid) {
        JsonObject allow = button(3, plugin.getMessages().text("messenger.bot.2fa.allow"),
            "A:" + TelegramBot.compact(playerUuid));
        JsonObject deny = button(4, plugin.getMessages().text("messenger.bot.2fa.deny"),
            "D:" + TelegramBot.compact(playerUuid));
        JsonObject body = new JsonObject();
        body.add("embeds", embeds(text));
        JsonArray components = new JsonArray();
        components.add(actionRow(allow, deny));
        body.add("components", components);
        restAsync(() -> {
            try {
                String channelId = openDmChannel(userId);
                if (channelId.isEmpty()) {
                    throw new IllegalStateException("no DM channel");
                }
                HttpJson.post(API + "/channels/" + channelId + "/messages", body.toString(),
                    10_000, 15_000, authHeader(), restProxy);
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e)
                    .log("Discord 2FA DM failed (is the player in a shared server with the bot?)");
            }
        });
    }

    /** Opens (or reuses) the DM channel with a user; blocking, sender thread only. */
    private String openDmChannel(String userId) throws Exception {
        JsonObject open = new JsonObject();
        open.addProperty("recipient_id", userId);
        JsonObject dm = HttpJson.parse(HttpJson.post(API + "/users/@me/channels", open.toString(),
            10_000, 15_000, authHeader(), restProxy)).getAsJsonObject();
        return text(dm, "id");
    }

    private void ackInteraction(String id, String ixToken, String content, JsonArray components) {
        JsonObject data = new JsonObject();
        data.add("embeds", embeds(content));
        if (components != null) {
            data.add("components", components);
        }
        JsonObject body = new JsonObject();
        body.addProperty("type", 4);
        body.add("data", data);
        restAsync(() -> {
            try {
                HttpJson.post(API + "/interactions/" + id + "/" + ixToken + "/callback",
                    body.toString(), 8_000, 8_000, authHeader(), restProxy);
            } catch (Exception ignored) {
                // already acknowledged
            }
        });
    }

    /** Replaces the message the button belongs to (removes its buttons). */
    private void updateInteractionMessage(String id, String ixToken, String content) {
        JsonObject data = new JsonObject();
        data.add("embeds", embeds(content));
        data.add("components", new JsonArray());
        JsonObject body = new JsonObject();
        body.addProperty("type", 7);
        body.add("data", data);
        restAsync(() -> {
            try {
                HttpJson.post(API + "/interactions/" + id + "/" + ixToken + "/callback",
                    body.toString(), 8_000, 8_000, authHeader(), restProxy);
            } catch (Exception ignored) {
                // already acknowledged
            }
        });
    }

    /** Sends the main menu (message with buttons) into a channel. */
    void sendMainMenu(String channelId) {
        JsonObject body = new JsonObject();
        body.add("embeds", embeds(menuText()));
        body.add("components", mainMenuComponents());
        restAsync(() -> {
            try {
                HttpJson.post(API + "/channels/" + channelId + "/messages", body.toString(),
                    10_000, 15_000, authHeader(), restProxy);
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e).log("Discord main menu failed");
            }
        });
    }

    /** Text above the menu buttons: custom from discord-bot.yml or the messages default. */
    private String menuText() {
        if (config.menuText != null && !config.menuText.isBlank()) {
            return config.menuText.trim();
        }
        return plugin.getMessages().text("messenger.bot.menu.title");
    }

    private JsonArray mainMenuComponents() {
        var msg = plugin.getMessages();
        JsonArray rows = new JsonArray();
        rows.add(actionRow(button(2, msg.text("messenger.bot.menu.status"), "m:status")));
        rows.add(actionRow(
            button(2, msg.text("messenger.bot.menu.reset"), "m:reset"),
            button(2, msg.text("messenger.bot.menu.settings"), "m:settings")));
        rows.add(actionRow(
            button(4, msg.text("messenger.bot.menu.block"), "m:block"),
            button(4, msg.text("messenger.bot.menu.unlink"), "m:unlink"),
            button(4, msg.text("messenger.bot.menu.kick"), "m:kick")));
        rows.add(actionRow(
            button(1, msg.text("messenger.bot.menu.help"), "m:help"),
            linkButton(messenger.helpUrl(MessengerKind.DISCORD))));
        return rows;
    }

    private JsonArray settingsComponents() {
        var msg = plugin.getMessages();
        JsonArray rows = new JsonArray();
        rows.add(actionRow(
            button(2, msg.text("messenger.bot.settings.modePassword"), "s:mode:password"),
            button(2, msg.text("messenger.bot.settings.modeOnly"), "s:mode:only")));
        rows.add(actionRow(
            button(3, msg.text("messenger.bot.settings.2faOn"), "s:2fa:on"),
            button(4, msg.text("messenger.bot.settings.2faOff"), "s:2fa:off")));
        rows.add(actionRow(
            button(3, msg.text("messenger.bot.settings.notificationsOn"), "s:notif:on"),
            button(4, msg.text("messenger.bot.settings.notificationsOff"), "s:notif:off")));
        rows.add(actionRow(
            button(3, msg.text("messenger.bot.settings.sessionsOn"), "s:sess:on"),
            button(4, msg.text("messenger.bot.settings.sessionsOff"), "s:sess:off")));
        rows.add(actionRow(button(2, msg.text("messenger.bot.settings.close"), "s:back")));
        return rows;
    }

    private JsonArray helpComponents() {
        JsonArray rows = new JsonArray();
        rows.add(actionRow(linkButton(messenger.helpUrl(MessengerKind.DISCORD))));
        return rows;
    }

    private JsonObject linkButton(String url) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2);
        b.addProperty("style", 5); // link button
        b.addProperty("label", plugin.getMessages().text("messenger.bot.helpLinkButton"));
        b.addProperty("url", url == null || url.isBlank() ? MessengerService.DEFAULT_HELP_URL : url);
        return b;
    }

    private static JsonObject actionRow(JsonObject... buttons) {
        JsonArray components = new JsonArray();
        for (JsonObject b : buttons) {
            components.add(b);
        }
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        row.add("components", components);
        return row;
    }

    private static JsonObject button(int style, String label, String customId) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2);
        b.addProperty("style", style);
        b.addProperty("label", label);
        b.addProperty("custom_id", customId);
        return b;
    }

    private String authHeader() {
        return "Bot " + token;
    }

    private static String text(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
    }
}
