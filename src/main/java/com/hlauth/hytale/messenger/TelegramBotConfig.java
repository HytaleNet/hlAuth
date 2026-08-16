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

/** Telegram bot settings, stored as {@code telegram-bot.yml} with bilingual comments. */
final class TelegramBotConfig {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String FILE_NAME = "telegram-bot.yml";
    private static final String LEGACY_JSON = "telegram-bot.json";

    public boolean enabled = false;
    public String token = "";
    public String username = "";
    public String apiUrl = "";
    public String helpUrl = MessengerService.DEFAULT_HELP_URL;
    public boolean proxyEnabled = false;
    public String proxyType = "http";
    public String proxyHost = "127.0.0.1";
    public int proxyPort = 2080;

    static TelegramBotConfig load(Path dataDirectory, HlAuthConfig legacy, HytaleLogger logger) {
        Path file = dataDirectory.resolve(FILE_NAME);
        Path legacyJson = dataDirectory.resolve(LEGACY_JSON);
        TelegramBotConfig cfg = fromLegacy(legacy);
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(file)) {
                Map<String, Object> y = YamlConfig.parse(file);
                cfg.enabled = YamlConfig.bool(y, "enabled", cfg.enabled);
                cfg.token = YamlConfig.str(y, "token", cfg.token);
                cfg.username = YamlConfig.str(y, "username", cfg.username);
                cfg.apiUrl = YamlConfig.str(y, "apiUrl", cfg.apiUrl);
                cfg.helpUrl = YamlConfig.str(y, "helpUrl", cfg.helpUrl);
                if (cfg.helpUrl == null || cfg.helpUrl.isBlank()) {
                    cfg.helpUrl = MessengerService.DEFAULT_HELP_URL;
                }
                cfg.proxyEnabled = YamlConfig.bool(y, "proxyEnabled", cfg.proxyEnabled);
                cfg.proxyType = YamlConfig.str(y, "proxyType", cfg.proxyType);
                cfg.proxyHost = YamlConfig.str(y, "proxyHost", cfg.proxyHost);
                cfg.proxyPort = YamlConfig.integer(y, "proxyPort", cfg.proxyPort);
            } else if (Files.exists(legacyJson)) {
                TelegramBotConfig old = GSON.fromJson(
                    Files.readString(legacyJson, StandardCharsets.UTF_8), TelegramBotConfig.class);
                if (old != null) {
                    cfg.enabled = old.enabled;
                    cfg.token = old.token == null ? cfg.token : old.token;
                    cfg.username = old.username == null ? cfg.username : old.username;
                    cfg.apiUrl = old.apiUrl == null ? cfg.apiUrl : old.apiUrl;
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

    private static TelegramBotConfig fromLegacy(HlAuthConfig c) {
        TelegramBotConfig cfg = new TelegramBotConfig();
        cfg.enabled = c.telegramEnabled;
        cfg.token = c.telegramBotToken == null ? "" : c.telegramBotToken;
        cfg.username = c.telegramBotUsername == null ? "" : c.telegramBotUsername;
        cfg.apiUrl = c.telegramApiUrl == null ? "" : c.telegramApiUrl;
        return cfg;
    }

    private static String toYaml(TelegramBotConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("#  hlAuth — Telegram bot / Telegram-бот\n");
        sb.append("# ============================================================\n\n");
        sb.append("# EN: Enable the Telegram bot (account linking + login confirmation).\n");
        sb.append("# RU: Включить Telegram-бота (привязка аккаунтов + подтверждение входа).\n");
        sb.append("enabled: ").append(c.enabled).append("\n\n");
        sb.append("# EN: Bot token from @BotFather. Keep this file private!\n");
        sb.append("# RU: Токен бота от @BotFather. Не публикуйте этот файл!\n");
        sb.append("token: ").append(YamlConfig.quote(c.token)).append("\n\n");
        sb.append("# EN: Bot username without @ (optional; fetched from Telegram if empty).\n");
        sb.append("# RU: Username бота без @ (необязательно; если пусто — берётся из Telegram).\n");
        sb.append("username: ").append(YamlConfig.quote(c.username)).append("\n\n");
        sb.append("# EN: Custom Telegram API URL. Empty = https://api.telegram.org\n");
        sb.append("# RU: Свой URL Telegram API. Пусто = https://api.telegram.org\n");
        sb.append("apiUrl: ").append(YamlConfig.quote(c.apiUrl)).append("\n\n");
        sb.append("# EN: Link on the Help button (default https://hlauncher.com).\n");
        sb.append("# RU: Ссылка у кнопки «Помощь» (по умолчанию https://hlauncher.com).\n");
        sb.append("helpUrl: ").append(YamlConfig.quote(c.helpUrl)).append("\n\n");
        sb.append("# EN: Optional HTTP/SOCKS proxy. Leave disabled if Telegram already works (TUN VPN).\n");
        sb.append("# EN: If Java hangs on SocksSocketImpl, keep this off — hlAuth ignores the JVM SOCKS proxy.\n");
        sb.append("# RU: Опциональный HTTP/SOCKS-прокси. Не включайте, если Telegram и так работает (TUN VPN).\n");
        sb.append("# RU: Если Java зависает на SocksSocketImpl — оставьте выключенным: hlAuth не использует системный SOCKS.\n");
        sb.append("proxyEnabled: ").append(c.proxyEnabled).append("\n");
        sb.append("proxyType: ").append(YamlConfig.quote(c.proxyType)).append("\n");
        sb.append("proxyHost: ").append(YamlConfig.quote(c.proxyHost)).append("\n");
        sb.append("proxyPort: ").append(c.proxyPort).append("\n");
        return sb.toString();
    }
}
