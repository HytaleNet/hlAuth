package com.hlauth.hytale.messenger;

import com.hlauth.hytale.config.HlAuthConfig;
import com.hlauth.hytale.config.YamlConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Discord bot settings, stored as {@code discord-bot.yml} with bilingual comments. */
final class DiscordBotConfig {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String FILE_NAME = "discord-bot.yml";
    private static final String LEGACY_JSON = "discord-bot.json";

    public boolean enabled = false;
    public String token = "";
    public String username = "";
    public String inviteUrl = "";
    public String helpUrl = MessengerService.DEFAULT_HELP_URL;
    /** Custom text shown above the menu buttons (empty = messages key messenger.bot.menu.title). */
    public String menuText = "";
    /** Embed accent color, hex like "#5865F2". */
    public String embedColor = "#5865F2";
    /** Bot presence: online, idle, dnd, invisible. */
    public String presenceStatus = "online";
    /** Activity type: playing, streaming, listening, watching, competing (empty activityText = none). */
    public String activityType = "playing";
    public String activityText = "";
    /** Route Discord traffic through a local proxy (e.g. xray/v2ray HTTP port) — needed where Discord is blocked. */
    public boolean proxyEnabled = false;
    /** "http" or "socks". The gateway (WebSocket) only supports "http"; use the HTTP port of your VPN/proxy. */
    public String proxyType = "http";
    public String proxyHost = "127.0.0.1";
    public int proxyPort = 2080;

    static DiscordBotConfig load(Path dataDirectory, HlAuthConfig legacy, HytaleLogger logger) {
        Path file = dataDirectory.resolve(FILE_NAME);
        Path legacyJson = dataDirectory.resolve(LEGACY_JSON);
        DiscordBotConfig cfg = fromLegacy(legacy);
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(file)) {
                Map<String, Object> y = YamlConfig.parse(file);
                cfg.enabled = YamlConfig.bool(y, "enabled", cfg.enabled);
                cfg.token = YamlConfig.str(y, "token", cfg.token);
                cfg.username = YamlConfig.str(y, "username", cfg.username);
                cfg.inviteUrl = YamlConfig.str(y, "inviteUrl", cfg.inviteUrl);
                cfg.helpUrl = YamlConfig.str(y, "helpUrl", cfg.helpUrl);
                if (cfg.helpUrl == null || cfg.helpUrl.isBlank()) {
                    cfg.helpUrl = MessengerService.DEFAULT_HELP_URL;
                }
                cfg.menuText = YamlConfig.str(y, "menuText", cfg.menuText);
                cfg.embedColor = YamlConfig.str(y, "embedColor", cfg.embedColor);
                cfg.presenceStatus = YamlConfig.str(y, "presenceStatus", cfg.presenceStatus);
                cfg.activityType = YamlConfig.str(y, "activityType", cfg.activityType);
                cfg.activityText = YamlConfig.str(y, "activityText", cfg.activityText);
                cfg.proxyEnabled = YamlConfig.bool(y, "proxyEnabled", cfg.proxyEnabled);
                cfg.proxyType = YamlConfig.str(y, "proxyType", cfg.proxyType);
                cfg.proxyHost = YamlConfig.str(y, "proxyHost", cfg.proxyHost);
                cfg.proxyPort = YamlConfig.integer(y, "proxyPort", cfg.proxyPort);
            } else if (Files.exists(legacyJson)) {
                DiscordBotConfig old = GSON.fromJson(
                    Files.readString(legacyJson, StandardCharsets.UTF_8), DiscordBotConfig.class);
                if (old != null) {
                    cfg.enabled = old.enabled;
                    cfg.token = old.token == null ? cfg.token : old.token;
                    cfg.username = old.username == null ? cfg.username : old.username;
                    cfg.inviteUrl = old.inviteUrl == null ? cfg.inviteUrl : old.inviteUrl;
                    cfg.helpUrl = (old.helpUrl == null || old.helpUrl.isBlank())
                        ? MessengerService.DEFAULT_HELP_URL : old.helpUrl;
                }
                Files.move(legacyJson, dataDirectory.resolve(LEGACY_JSON + ".old"),
                    StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, toYaml(cfg), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to load %s", FILE_NAME);
        }
        return cfg;
    }

    private static DiscordBotConfig fromLegacy(HlAuthConfig c) {
        DiscordBotConfig cfg = new DiscordBotConfig();
        cfg.enabled = c.discordEnabled;
        cfg.token = c.discordBotToken == null ? "" : c.discordBotToken;
        cfg.username = c.discordBotUsername == null ? "" : c.discordBotUsername;
        cfg.inviteUrl = c.discordInviteUrl == null ? "" : c.discordInviteUrl;
        return cfg;
    }

    private static String toYaml(DiscordBotConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("#  hlAuth — Discord bot / Discord-бот\n");
        sb.append("# ============================================================\n\n");
        sb.append("# EN: Enable the Discord bot (account linking + login confirmation).\n");
        sb.append("# RU: Включить Discord-бота (привязка аккаунтов + подтверждение входа).\n");
        sb.append("enabled: ").append(c.enabled).append("\n\n");
        sb.append("# EN: Bot token from the Discord Developer Portal (Bot → Token). Keep this file private!\n");
        sb.append("# RU: Токен бота из Discord Developer Portal (Bot → Token). Не публикуйте этот файл!\n");
        sb.append("token: ").append(YamlConfig.quote(c.token)).append("\n\n");
        sb.append("# EN: Bot username shown to players (optional; taken from the gateway if empty).\n");
        sb.append("# RU: Имя бота для игроков (необязательно; если пусто — берётся с gateway).\n");
        sb.append("username: ").append(YamlConfig.quote(c.username)).append("\n\n");
        sb.append("# EN: Server invite URL — the bot can only DM players who share a server with it.\n");
        sb.append("# RU: Ссылка-приглашение на сервер — бот может писать в ЛС только тем, с кем есть общий сервер.\n");
        sb.append("inviteUrl: ").append(YamlConfig.quote(c.inviteUrl)).append("\n\n");
        sb.append("# EN: Link button in the bot menu / Help reply (default https://hlauncher.com).\n");
        sb.append("# RU: Кнопка-ссылка в меню бота и в ответе «Помощь» (по умолчанию https://hlauncher.com).\n");
        sb.append("helpUrl: ").append(YamlConfig.quote(c.helpUrl)).append("\n\n");
        sb.append("# EN: Custom text shown above the menu buttons (empty = default from messages/*.yml).\n");
        sb.append("# RU: Свой текст над кнопками меню (пусто = стандартный из messages/*.yml).\n");
        sb.append("menuText: ").append(YamlConfig.quote(c.menuText)).append("\n\n");
        sb.append("# EN: Accent color of embed messages, hex format.\n");
        sb.append("# RU: Цвет embed-сообщений бота, hex-формат.\n");
        sb.append("embedColor: ").append(YamlConfig.quote(c.embedColor)).append("\n\n");
        sb.append("# EN: Bot presence: online, idle, dnd, invisible.\n");
        sb.append("# RU: Статус бота: online (в сети), idle (не активен), dnd (не беспокоить), invisible (невидимый).\n");
        sb.append("presenceStatus: ").append(YamlConfig.quote(c.presenceStatus)).append("\n\n");
        sb.append("# EN: Activity: playing / streaming / listening / watching / competing + text.\n");
        sb.append("# RU: Активность: playing (играет) / streaming (стримит) / listening (слушает) /\n");
        sb.append("# RU: watching (смотрит) / competing (соревнуется). Пустой activityText = без активности.\n");
        sb.append("activityType: ").append(YamlConfig.quote(c.activityType)).append("\n");
        sb.append("activityText: ").append(YamlConfig.quote(c.activityText)).append("\n\n");
        sb.append("# EN: Proxy for Discord (needed in countries where Discord is blocked, e.g. via xray/v2ray).\n");
        sb.append("# RU: Прокси для Discord (нужен там, где Discord заблокирован, например через xray/v2ray).\n");
        sb.append("# EN: Use the LOCAL port of your xray/sing-box client (mixed port, usually 2080 or 10809),\n");
        sb.append("# EN: NOT public proxies from the internet - they do not work.\n");
        sb.append("# RU: Указывайте ЛОКАЛЬНЫЙ порт вашего xray/sing-box (mixed-порт, обычно 2080 или 10809),\n");
        sb.append("# RU: а НЕ публичные прокси из интернета - они не работают.\n");
        sb.append("proxyEnabled: ").append(c.proxyEnabled).append("\n\n");
        sb.append("# EN: \"http\" or \"socks\". The gateway (WebSocket) works only with \"http\".\n");
        sb.append("# RU: \"http\" или \"socks\". Gateway (WebSocket) работает только через \"http\".\n");
        sb.append("proxyType: ").append(YamlConfig.quote(c.proxyType)).append("\n\n");
        sb.append("proxyHost: ").append(YamlConfig.quote(c.proxyHost)).append("\n\n");
        sb.append("proxyPort: ").append(c.proxyPort).append("\n");
        return sb.toString();
    }
}
