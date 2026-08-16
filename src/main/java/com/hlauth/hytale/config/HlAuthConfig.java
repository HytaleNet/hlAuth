package com.hlauth.hytale.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plugin configuration, stored as {@code config.yml} inside the plugin data directory.
 * The file is written with bilingual (RU / EN) comments; missing keys fall back to defaults.
 * Old {@code config.json} files are migrated automatically on first start.
 */
public final class HlAuthConfig {

    private static final Gson GSON = new GsonBuilder().create();
    static final String[] DEFAULT_UNSAFE_PASSWORDS = {
        "123456", "12345678", "password", "qwerty", "111111"
    };
    public static final String UNSAFE_PASSWORDS_FILE = "unsafePasswords.txt";

    // --- General ---
    /** Message language: file name in the messages/ folder (without .yml), e.g. "en" or "ru". */
    public String language = "en";

    // --- Registration ---
    /** Whether new players must register. When false, unregistered players play without auth. */
    public boolean registrationEnabled = true;
    /** Kick players that fail to authenticate in time. */
    public int timeoutSeconds = 120;
    /** Minimum password length. */
    public int passwordMinLength = 5;
    /** Maximum password length. */
    public int passwordMaxLength = 64;
    /**
     * Lowercase passwords that are never accepted.
     * Loaded from {@code unsafePasswords.txt} (one per line); kept here so old configs can migrate.
     */
    public String[] unsafePasswords = DEFAULT_UNSAFE_PASSWORDS;
    /** Maximum accounts registered per IP address (0 = unlimited). */
    public int maxRegistrationsPerIp = 2;

    // --- Login ---
    /** Number of wrong password attempts before the player is kicked. */
    public int maxLoginTries = 5;
    /** Kick players who try to log in with a wrong password. */
    public boolean kickOnWrongPassword = false;
    /**
     * bcrypt log-rounds used for new hashes. {@code 0} = run a startup benchmark
     * and store the chosen cost (highest that stays under {@link #bcryptTargetMs}).
     * Valid range after calibration: 10–14.
     */
    public int bcryptCost = 0;
    /** Target hash time in milliseconds for the bcrypt cost benchmark. */
    public int bcryptTargetMs = 250;
    /**
     * Commands after a successful registration. Empty = off.
     * Lines: {@code [CONSOLE] ...}, {@code [PLAYER] ...}, {@code [WAIT] millis}. {@code {nick}} = player name.
     */
    public String[] registerCommands = new String[0];
    /**
     * Commands after a successful login (including session / premium auto-login). Empty = off.
     */
    public String[] loginCommands = new String[0];

    // --- Sessions ---
    /** Re-login automatically when the same player reconnects from the same IP. */
    public boolean sessionsEnabled = true;
    /** How long a session stays valid, in minutes. */
    public int sessionTimeoutMinutes = 10;

    // --- UI ---
    /** Open the native login/register menu on join. When false only chat commands are used. */
    public boolean useUiMenus = true;
    /** Delay in milliseconds before the menu opens after the player is ready (avoids UI glitches). */
    public int uiOpenDelayMs = 1000;

    // --- Premium check (playerdb.co) ---
    /** Verify player UUIDs against the Hytale profile service (playerdb.co). */
    public boolean premiumCheckEnabled = false;
    /** Timeout in seconds for a premium lookup; on timeout/error the normal login flow is used. */
    public int premiumCheckTimeoutSeconds = 5;
    /**
     * When true, premium/offline mismatches kick the player immediately.
     * When false, a non-closable UI plaque is shown instead.
     */
    public boolean premiumKickEnabled = false;
    /**
     * When true (and {@link #premiumCheckEnabled}), a verified premium player is
     * let in without a password: existing premium accounts auto-login, new ones
     * are registered automatically as premium.
     */
    public boolean premiumAutoRegister = false;

    // --- Protection ---
    /** Cancel chat messages of players that are not logged in. */
    public boolean protectChat = true;
    /** Interval in seconds between "please login" reminders in chat (0 = disabled). */
    public int messageIntervalSeconds = 15;

    // --- Two-factor authentication (TOTP) ---
    /** Master switch. When false, 2FA is ignored even if an account already has it bound. */
    public boolean twoFactorEnabled = false;
    /** When true (and {@link #twoFactorEnabled}), every player must bind an authenticator after password. */
    public boolean twoFactorRequired = false;
    /** When true, a valid IP session still asks for a 2FA code. */
    public boolean twoFactorRequiredOnSession = false;
    /** Issuer name shown in Authenticator / Aegis / 2FAS. */
    public String twoFactorIssuer = "hlAuth";

    // --- Telegram / Discord (legacy: kept only so old config.json files migrate into the bot files) ---
    public boolean telegramEnabled = false;
    public String telegramBotToken = "";
    public String telegramBotUsername = "";
    public String telegramApiUrl = "";
    public boolean discordEnabled = false;
    public String discordBotToken = "";
    public String discordBotUsername = "";
    public String discordInviteUrl = "";
    /**
     * Messenger login mode default for new links:
     * {@code password_and_confirm} = first password, then Telegram/Discord confirmation;
     * {@code confirm_only} = only Telegram/Discord confirmation (no password step).
     */
    public String messengerLoginMode = "password_and_confirm";

    // --- Storage ---
    /** Account backend: {@code h2} (default file DB), {@code sqlite} (file) or {@code mysql}. */
    public String storageType = "h2";
    /** MySQL / remote host. Ignored for embedded H2 unless {@link #databaseJdbcUrl} is empty and type is mysql. */
    public String databaseHost = "127.0.0.1";
    public int databasePort = 3306;
    /** Schema / database name (MySQL). Created automatically if the user can CREATE DATABASE. */
    public String databaseName = "hlauth";
    public String databaseUsername = "hlauth";
    public String databasePassword = "";
    /** SQL table name (letters, digits, underscore). */
    public String databaseTable = "hlauth_accounts";
    /**
     * Full JDBC URL. When set, host/port/name are ignored.
     * Examples: {@code jdbc:mysql://db.example.com:3306/hlauth}, {@code jdbc:h2:tcp://127.0.0.1:9092/hlauth}
     */
    public String databaseJdbcUrl = "";
    /** MySQL SSL. Ignored when {@link #databaseJdbcUrl} is set (put useSSL in the URL). */
    public boolean databaseUseSsl = false;
    /** Connection pool size (1–16). */
    public int databasePoolSize = 4;

    // --- Backups ---
    /** Periodic snapshots of all accounts into the backups/ folder. */
    public boolean backupEnabled = false;
    /** Auto-backup interval, hours part (added to {@link #backupIntervalMinutes}). */
    public int backupIntervalHours = 6;
    /** Auto-backup interval, extra minutes (e.g. hours=0 minutes=30 → every 30 minutes). */
    public int backupIntervalMinutes = 0;
    /** How many backup files to keep (oldest are deleted). 0 = keep all. */
    public int backupKeepCount = 24;

    public static HlAuthConfig load(Path dataDirectory, HytaleLogger logger) {
        Path yamlFile = dataDirectory.resolve("config.yml");
        Path jsonFile = dataDirectory.resolve("config.json");
        HlAuthConfig config = new HlAuthConfig();
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(yamlFile)) {
                config = fromYaml(YamlConfig.parse(yamlFile));
            } else if (Files.exists(jsonFile)) {
                // Migrate the old JSON config once
                String raw = Files.readString(jsonFile, StandardCharsets.UTF_8);
                HlAuthConfig loaded = GSON.fromJson(stripJsonComments(raw), HlAuthConfig.class);
                if (loaded != null) {
                    config = mergeDefaults(loaded);
                }
                Files.move(jsonFile, dataDirectory.resolve("config.json.old"),
                    StandardCopyOption.REPLACE_EXISTING);
                logger.atInfo().log("Migrated config.json to config.yml (backup: config.json.old)");
            }
            loadUnsafePasswords(dataDirectory, config, logger);
            // Always rewrite so new options and bilingual comments appear after updates
            save(dataDirectory, config);
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to load config.yml, using defaults");
        }
        return config;
    }

    /** Rewrites {@code config.yml} with the current values and bilingual comments. */
    public static void save(Path dataDirectory, HlAuthConfig config, HytaleLogger logger) {
        try {
            save(dataDirectory, config);
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to save config.yml");
        }
    }

    static void save(Path dataDirectory, HlAuthConfig config) throws IOException {
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("config.yml"), toCommentedYaml(config), StandardCharsets.UTF_8);
    }

    /** Populates a config from a parsed flat-YAML map; missing keys keep defaults. */
    private static HlAuthConfig fromYaml(Map<String, Object> y) {
        HlAuthConfig c = new HlAuthConfig();
        c.language = YamlConfig.str(y, "language", c.language);
        c.registrationEnabled = YamlConfig.bool(y, "registrationEnabled", c.registrationEnabled);
        c.timeoutSeconds = YamlConfig.integer(y, "timeoutSeconds", c.timeoutSeconds);
        c.passwordMinLength = YamlConfig.integer(y, "passwordMinLength", c.passwordMinLength);
        c.passwordMaxLength = YamlConfig.integer(y, "passwordMaxLength", c.passwordMaxLength);
        c.maxRegistrationsPerIp = YamlConfig.integer(y, "maxRegistrationsPerIp", c.maxRegistrationsPerIp);
        c.maxLoginTries = YamlConfig.integer(y, "maxLoginTries", c.maxLoginTries);
        c.kickOnWrongPassword = YamlConfig.bool(y, "kickOnWrongPassword", c.kickOnWrongPassword);
        c.bcryptCost = YamlConfig.integer(y, "bcryptCost", c.bcryptCost);
        c.bcryptTargetMs = YamlConfig.integer(y, "bcryptTargetMs", c.bcryptTargetMs);
        c.registerCommands = YamlConfig.list(y, "registerCommands", c.registerCommands);
        c.loginCommands = YamlConfig.list(y, "loginCommands", c.loginCommands);
        c.sessionsEnabled = YamlConfig.bool(y, "sessionsEnabled", c.sessionsEnabled);
        c.sessionTimeoutMinutes = YamlConfig.integer(y, "sessionTimeoutMinutes", c.sessionTimeoutMinutes);
        c.useUiMenus = YamlConfig.bool(y, "useUiMenus", c.useUiMenus);
        c.uiOpenDelayMs = YamlConfig.integer(y, "uiOpenDelayMs", c.uiOpenDelayMs);
        c.premiumCheckEnabled = YamlConfig.bool(y, "premiumCheckEnabled", c.premiumCheckEnabled);
        c.premiumCheckTimeoutSeconds = YamlConfig.integer(y, "premiumCheckTimeoutSeconds", c.premiumCheckTimeoutSeconds);
        c.premiumKickEnabled = YamlConfig.bool(y, "premiumKickEnabled", c.premiumKickEnabled);
        c.premiumAutoRegister = YamlConfig.bool(y, "premiumAutoRegister", c.premiumAutoRegister);
        c.protectChat = YamlConfig.bool(y, "protectChat", c.protectChat);
        c.messageIntervalSeconds = YamlConfig.integer(y, "messageIntervalSeconds", c.messageIntervalSeconds);
        c.twoFactorEnabled = YamlConfig.bool(y, "twoFactorEnabled", c.twoFactorEnabled);
        c.twoFactorRequired = YamlConfig.bool(y, "twoFactorRequired", c.twoFactorRequired);
        c.twoFactorRequiredOnSession = YamlConfig.bool(y, "twoFactorRequiredOnSession", c.twoFactorRequiredOnSession);
        c.twoFactorIssuer = YamlConfig.str(y, "twoFactorIssuer", c.twoFactorIssuer);
        c.messengerLoginMode = YamlConfig.str(y, "messengerLoginMode", c.messengerLoginMode);
        c.storageType = YamlConfig.str(y, "storageType", c.storageType);
        c.databaseHost = YamlConfig.str(y, "databaseHost", c.databaseHost);
        c.databasePort = YamlConfig.integer(y, "databasePort", c.databasePort);
        c.databaseName = YamlConfig.str(y, "databaseName", c.databaseName);
        c.databaseUsername = YamlConfig.str(y, "databaseUsername", c.databaseUsername);
        c.databasePassword = YamlConfig.str(y, "databasePassword", c.databasePassword);
        c.databaseTable = YamlConfig.str(y, "databaseTable", c.databaseTable);
        c.databaseJdbcUrl = YamlConfig.str(y, "databaseJdbcUrl", c.databaseJdbcUrl);
        c.databaseUseSsl = YamlConfig.bool(y, "databaseUseSsl", c.databaseUseSsl);
        c.databasePoolSize = YamlConfig.integer(y, "databasePoolSize", c.databasePoolSize);
        c.backupEnabled = YamlConfig.bool(y, "backupEnabled", c.backupEnabled);
        c.backupIntervalHours = YamlConfig.integer(y, "backupIntervalHours", c.backupIntervalHours);
        c.backupIntervalMinutes = YamlConfig.integer(y, "backupIntervalMinutes", c.backupIntervalMinutes);
        c.backupKeepCount = YamlConfig.integer(y, "backupKeepCount", c.backupKeepCount);
        return mergeDefaults(c);
    }

    /**
     * Fills null/invalid values left by partial files so runtime code never NPEs.
     */
    private static HlAuthConfig mergeDefaults(HlAuthConfig loaded) {
        HlAuthConfig defaults = new HlAuthConfig();
        if (loaded.language == null || loaded.language.isBlank()) {
            loaded.language = defaults.language;
        }
        if (loaded.unsafePasswords == null) {
            loaded.unsafePasswords = defaults.unsafePasswords;
        }
        if (loaded.registerCommands == null) {
            loaded.registerCommands = defaults.registerCommands;
        }
        if (loaded.loginCommands == null) {
            loaded.loginCommands = defaults.loginCommands;
        }
        if (loaded.twoFactorIssuer == null || loaded.twoFactorIssuer.isBlank()) {
            loaded.twoFactorIssuer = defaults.twoFactorIssuer;
        }
        if (loaded.telegramBotToken == null) {
            loaded.telegramBotToken = defaults.telegramBotToken;
        }
        if (loaded.telegramBotUsername == null) {
            loaded.telegramBotUsername = defaults.telegramBotUsername;
        }
        if (loaded.telegramApiUrl == null) {
            loaded.telegramApiUrl = defaults.telegramApiUrl;
        }
        if (loaded.discordBotToken == null) {
            loaded.discordBotToken = defaults.discordBotToken;
        }
        if (loaded.discordBotUsername == null) {
            loaded.discordBotUsername = defaults.discordBotUsername;
        }
        if (loaded.discordInviteUrl == null) {
            loaded.discordInviteUrl = defaults.discordInviteUrl;
        }
        if (loaded.messengerLoginMode == null || loaded.messengerLoginMode.isBlank()) {
            loaded.messengerLoginMode = defaults.messengerLoginMode;
        } else {
            String mode = loaded.messengerLoginMode.trim().toLowerCase(Locale.ROOT);
            if (!"password_and_confirm".equals(mode) && !"confirm_only".equals(mode)) {
                loaded.messengerLoginMode = defaults.messengerLoginMode;
            } else {
                loaded.messengerLoginMode = mode;
            }
        }
        if (loaded.storageType == null || loaded.storageType.isBlank()
                || "json".equalsIgnoreCase(loaded.storageType.trim())) {
            loaded.storageType = defaults.storageType;
        }
        if (loaded.databaseHost == null || loaded.databaseHost.isBlank()) {
            loaded.databaseHost = defaults.databaseHost;
        }
        if (loaded.databaseName == null || loaded.databaseName.isBlank()) {
            loaded.databaseName = defaults.databaseName;
        }
        if (loaded.databaseUsername == null) {
            loaded.databaseUsername = defaults.databaseUsername;
        }
        if (loaded.databasePassword == null) {
            loaded.databasePassword = defaults.databasePassword;
        }
        if (loaded.databaseTable == null || loaded.databaseTable.isBlank()) {
            loaded.databaseTable = defaults.databaseTable;
        }
        if (loaded.databaseJdbcUrl == null) {
            loaded.databaseJdbcUrl = defaults.databaseJdbcUrl;
        }
        if (loaded.bcryptCost < 0) {
            loaded.bcryptCost = 0;
        }
        if (loaded.bcryptTargetMs < 50) {
            loaded.bcryptTargetMs = defaults.bcryptTargetMs;
        } else if (loaded.bcryptTargetMs > 2000) {
            loaded.bcryptTargetMs = 2000;
        }
        return loaded;
    }

    public boolean isPasswordSafe(String password) {
        String lower = password.toLowerCase();
        for (String unsafe : unsafePasswords) {
            if (lower.equals(unsafe)) {
                return false;
            }
        }
        return true;
    }

    public boolean isTwoFactorEnabled() {
        return twoFactorEnabled;
    }

    /** Mandatory bind after password. Requires the master switch. */
    public boolean isTwoFactorRequired() {
        return twoFactorEnabled && twoFactorRequired;
    }

    public boolean isMessengerConfirmOnly() {
        return "confirm_only".equalsIgnoreCase(messengerLoginMode == null ? "" : messengerLoginMode.trim());
    }

    /** Removes line (//) and block comments from JSONC so Gson can parse the old file. */
    static String stripJsonComments(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean inString = false;
        boolean escape = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            char next = i + 1 < raw.length() ? raw.charAt(i + 1) : 0;

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** YAML with bilingual comments for every option. */
    static String toCommentedYaml(HlAuthConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ============================================================\n");
        sb.append("#  hlAuth — configuration / конфигурация\n");
        sb.append("#  Telegram bot: telegram-bot.yml | Discord bot: discord-bot.yml\n");
        sb.append("# ============================================================\n");

        section(sb, "General / Общие");
        field(sb, "language", YamlConfig.quote(c.language),
            "EN: Message language file in messages/ (without .yml), e.g. \"en\" or \"ru\".",
            "RU: Язык сообщений — файл в messages/ без .yml, например \"en\" или \"ru\".");

        section(sb, "Registration / Регистрация");
        field(sb, "registrationEnabled", String.valueOf(c.registrationEnabled),
            "EN: Require new players to register. false = unregistered players play without auth.",
            "RU: Обязательная регистрация. false = незарегистрированные играют без авторизации.");
        field(sb, "timeoutSeconds", String.valueOf(c.timeoutSeconds),
            "EN: Kick players who fail to authenticate within this many seconds.",
            "RU: Кик, если игрок не авторизовался за указанное число секунд.");
        field(sb, "passwordMinLength", String.valueOf(c.passwordMinLength),
            "EN: Minimum password length.",
            "RU: Минимальная длина пароля.");
        field(sb, "passwordMaxLength", String.valueOf(c.passwordMaxLength),
            "EN: Maximum password length.",
            "RU: Максимальная длина пароля.");
        sb.append("# EN: Forbidden passwords are in unsafePasswords.txt (one per line, case-insensitive).\n");
        sb.append("# RU: Запрещённые пароли — в unsafePasswords.txt (по одному на строку, без учёта регистра).\n\n");
        field(sb, "maxRegistrationsPerIp", String.valueOf(c.maxRegistrationsPerIp),
            "EN: Max accounts per IP (0 = unlimited).",
            "RU: Максимум аккаунтов с одного IP (0 = без лимита).");

        section(sb, "Login / Вход");
        field(sb, "maxLoginTries", String.valueOf(c.maxLoginTries),
            "EN: Wrong password attempts before the player is kicked.",
            "RU: Число неверных попыток пароля до кика.");
        field(sb, "kickOnWrongPassword", String.valueOf(c.kickOnWrongPassword),
            "EN: Kick immediately on a wrong password.",
            "RU: Кикать сразу при неверном пароле.");
        field(sb, "bcryptCost", String.valueOf(c.bcryptCost),
            "EN: bcrypt log-rounds for new hashes. 0 = benchmark on start and store the chosen cost (10–14).",
            "RU: log-rounds bcrypt для новых хешей. 0 = бенчмарк при старте и запись выбранного cost (10–14).");
        field(sb, "bcryptTargetMs", String.valueOf(c.bcryptTargetMs),
            "EN: Target hash time in ms for the bcrypt cost benchmark (used when bcryptCost is 0).",
            "RU: Целевое время хеша в мс для бенчмарка bcrypt (когда bcryptCost = 0).");

        section(sb, "Commands after auth / Команды после авторизации");
        commandList(sb, "registerCommands", c.registerCommands,
            "EN: After successful registration. Prefixes: [CONSOLE] [PLAYER] [WAIT] millis. {nick} = player name. Empty = off.",
            "RU: После успешной регистрации. Префиксы: [CONSOLE] [PLAYER] [WAIT] мс. {nick} = ник. Пустой список = выкл.");
        commandList(sb, "loginCommands", c.loginCommands,
            "EN: After successful login (including session / premium auto-login). Same prefixes as registerCommands. Empty = off.",
            "RU: После успешного входа (включая сессию / premium-автовход). Те же префиксы. Пустой список = выкл.");

        section(sb, "Sessions / Сессии");
        field(sb, "sessionsEnabled", String.valueOf(c.sessionsEnabled),
            "EN: Auto re-login when the same player reconnects from the same IP.",
            "RU: Автовход при переподключении того же игрока с того же IP.");
        field(sb, "sessionTimeoutMinutes", String.valueOf(c.sessionTimeoutMinutes),
            "EN: How long a session stays valid, in minutes.",
            "RU: Время жизни сессии в минутах.");

        section(sb, "UI / Интерфейс");
        field(sb, "useUiMenus", String.valueOf(c.useUiMenus),
            "EN: Open native login/register UI on join. false = chat commands only.",
            "RU: Открывать UI входа/регистрации при входе. false = только команды в чате.");
        field(sb, "uiOpenDelayMs", String.valueOf(c.uiOpenDelayMs),
            "EN: Delay in ms before opening the menu (reduces UI texture glitches).",
            "RU: Задержка в мс перед открытием меню (меньше глитчей текстур UI).");

        section(sb, "Premium (playerdb.co)");
        field(sb, "premiumCheckEnabled", String.valueOf(c.premiumCheckEnabled),
            "EN: Verify UUID via playerdb.co. Premium auto-login; blocks premium/offline name misuse.",
            "RU: Проверка UUID через playerdb.co. Premium — автовход; блокирует подмену premium/offline.");
        field(sb, "premiumCheckTimeoutSeconds", String.valueOf(c.premiumCheckTimeoutSeconds),
            "EN: Premium lookup timeout in seconds; on error the normal login flow is used.",
            "RU: Таймаут проверки premium в секундах; при ошибке — обычный вход.");
        field(sb, "premiumKickEnabled", String.valueOf(c.premiumKickEnabled),
            "EN: true = kick on premium/offline mismatch. false = show Access Denied UI with Exit.",
            "RU: true = кик при несовпадении premium/offline. false = UI «Доступ запрещён» с кнопкой Выход.");
        field(sb, "premiumAutoRegister", String.valueOf(c.premiumAutoRegister),
            "EN: (needs premiumCheckEnabled) verified premium skips password; new accounts auto-register as premium.",
            "RU: (нужен premiumCheckEnabled) verified premium без пароля; новые аккаунты регистрируются как premium.");

        section(sb, "Protection / Защита");
        field(sb, "protectChat", String.valueOf(c.protectChat),
            "EN: Block chat messages from players who are not logged in.",
            "RU: Блокировать чат игроков, которые ещё не вошли.");
        field(sb, "messageIntervalSeconds", String.valueOf(c.messageIntervalSeconds),
            "EN: Seconds between \"please login\" chat reminders (0 = disabled).",
            "RU: Интервал напоминаний «войдите» в чате в секундах (0 = выкл).");

        section(sb, "Two-factor / 2FA (TOTP)");
        field(sb, "twoFactorEnabled", String.valueOf(c.twoFactorEnabled),
            "EN: Enable TOTP 2FA (Google Authenticator, Aegis, 2FAS, …). false = feature off.",
            "RU: Включить 2FA по TOTP (Google Authenticator, Aegis, 2FAS, …). false = выкл.");
        field(sb, "twoFactorRequired", String.valueOf(c.twoFactorRequired),
            "EN: If true, every player must bind an authenticator after the password (needs twoFactorEnabled).",
            "RU: Если true, каждый игрок обязан привязать authenticator после пароля (нужен twoFactorEnabled).");
        field(sb, "twoFactorRequiredOnSession", String.valueOf(c.twoFactorRequiredOnSession),
            "EN: If true, session auto-login still asks for a 2FA code.",
            "RU: Если true, автовход по сессии всё равно спрашивает код 2FA.");
        field(sb, "twoFactorIssuer", YamlConfig.quote(c.twoFactorIssuer),
            "EN: Name shown in the authenticator app (otpauth issuer).",
            "RU: Имя сервера в приложении-аутентификаторе (issuer в otpauth).");

        section(sb, "Telegram / Discord");
        sb.append("# EN: Bot settings live in separate files: telegram-bot.yml and discord-bot.yml.\n");
        sb.append("# RU: Настройки ботов — в отдельных файлах: telegram-bot.yml и discord-bot.yml.\n");
        field(sb, "messengerLoginMode", YamlConfig.quote(c.messengerLoginMode),
            "EN: Default for new links: password_and_confirm = password + bot confirmation; confirm_only = bot confirmation only.",
            "RU: Режим по умолчанию для новых привязок: password_and_confirm = пароль + подтверждение; confirm_only = только подтверждение.");

        section(sb, "Storage / Хранилище");
        field(sb, "storageType", YamlConfig.quote(c.storageType),
            "EN: h2 = local file DB (default); sqlite = SQLite file; mysql = MySQL. The JDBC driver is downloaded into lib/ on first start.",
            "RU: h2 = локальный файл БД (по умолчанию); sqlite = файл SQLite; mysql = MySQL. JDBC-драйвер скачивается в lib/ при первом запуске.");
        field(sb, "databaseHost", YamlConfig.quote(c.databaseHost),
            "EN: MySQL host (ignored for embedded H2, or when databaseJdbcUrl is set).",
            "RU: Хост MySQL (не используется для встроенного H2 и если задан databaseJdbcUrl).");
        field(sb, "databasePort", String.valueOf(c.databasePort),
            "EN: MySQL port (default 3306).",
            "RU: Порт MySQL (по умолчанию 3306).");
        field(sb, "databaseName", YamlConfig.quote(c.databaseName),
            "EN: MySQL database name (created if the user is allowed to CREATE DATABASE).",
            "RU: Имя базы MySQL (создаётся, если у пользователя есть CREATE DATABASE).");
        field(sb, "databaseUsername", YamlConfig.quote(c.databaseUsername),
            "EN: Database user (MySQL, or H2 if the file/TCP server requires it).",
            "RU: Пользователь БД (MySQL; для H2 — если файл/TCP этого требует).");
        field(sb, "databasePassword", YamlConfig.quote(c.databasePassword),
            "EN: Database password. Do not share this file.",
            "RU: Пароль БД. Не публикуйте этот файл.");
        field(sb, "databaseTable", YamlConfig.quote(c.databaseTable),
            "EN: Table name for accounts.",
            "RU: Имя таблицы аккаунтов.");
        field(sb, "databaseUseSsl", String.valueOf(c.databaseUseSsl),
            "EN: MySQL SSL (when databaseJdbcUrl is empty).",
            "RU: SSL для MySQL (если databaseJdbcUrl пустой).");
        field(sb, "databasePoolSize", String.valueOf(c.databasePoolSize),
            "EN: JDBC connection pool size (1–16).",
            "RU: Размер пула JDBC-соединений (1–16).");
        field(sb, "databaseJdbcUrl", YamlConfig.quote(c.databaseJdbcUrl),
            "EN: Optional full JDBC URL. Overrides host/port/name. Example: jdbc:mysql://db:3306/hlauth",
            "RU: Полный JDBC URL (необязательно). Перебивает host/port/name. Пример: jdbc:mysql://db:3306/hlauth");

        section(sb, "Backups / Резервные копии");
        field(sb, "backupEnabled", String.valueOf(c.backupEnabled),
            "EN: Automatic account backups into backups/ (H2 / SQLite / MySQL).",
            "RU: Автобекапы аккаунтов в backups/ (H2 / SQLite / MySQL).");
        field(sb, "backupIntervalHours", String.valueOf(c.backupIntervalHours),
            "EN: Auto-backup interval, hours (added to backupIntervalMinutes). 6 + 0 = every 6 hours.",
            "RU: Интервал автобекапа, часы (плюсуются к backupIntervalMinutes). 6 + 0 = каждые 6 часов.");
        field(sb, "backupIntervalMinutes", String.valueOf(c.backupIntervalMinutes),
            "EN: Extra minutes. 0 hours + 30 minutes = every 30 minutes. Total 0 disables the timer.",
            "RU: Дополнительные минуты. 0 часов + 30 минут = каждые 30 минут. Сумма 0 — таймер выкл.");
        field(sb, "backupKeepCount", String.valueOf(c.backupKeepCount),
            "EN: How many backup files to keep (oldest deleted). 0 = keep all.",
            "RU: Сколько файлов бекапа хранить (старые удаляются). 0 = хранить все.");

        return sb.toString();
    }

    private static void section(StringBuilder sb, String title) {
        sb.append('\n');
        sb.append("# ── ").append(title).append(" ──\n");
    }

    private static void field(StringBuilder sb, String key, String value, String en, String ru) {
        sb.append("# ").append(en).append('\n');
        sb.append("# ").append(ru).append('\n');
        sb.append(key).append(": ").append(value).append('\n');
        sb.append('\n');
    }

    private static void commandList(StringBuilder sb, String key, String[] values, String en, String ru) {
        sb.append("# ").append(en).append('\n');
        sb.append("# ").append(ru).append('\n');
        List<String> active = new ArrayList<>();
        if (values != null) {
            for (String line : values) {
                if (line != null && !line.isBlank()) {
                    active.add(line);
                }
            }
        }
        if (active.isEmpty()) {
            sb.append(key).append(": []\n");
            sb.append("# ").append(key).append(":\n");
            sb.append("#   - \"[CONSOLE] heal {nick}\"\n");
            sb.append("#   - \"[WAIT] 1000\"\n");
            sb.append("#   - \"[PLAYER] kit start\"\n");
        } else {
            sb.append(key).append(":\n");
            for (String line : active) {
                sb.append("  - ").append(YamlConfig.quote(line)).append('\n');
            }
        }
        sb.append('\n');
    }

    static void loadUnsafePasswords(Path dataDirectory, HlAuthConfig config, HytaleLogger logger) {
        Path file = dataDirectory.resolve(UNSAFE_PASSWORDS_FILE);
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(file)) {
                String[] seed = (config.unsafePasswords != null && config.unsafePasswords.length > 0)
                    ? config.unsafePasswords
                    : DEFAULT_UNSAFE_PASSWORDS;
                writeUnsafePasswordsFile(file, seed);
            }
            List<String> loaded = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                loaded.add(trimmed.toLowerCase(Locale.ROOT));
            }
            config.unsafePasswords = loaded.toArray(String[]::new);
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to load %s, using defaults", UNSAFE_PASSWORDS_FILE);
            if (config.unsafePasswords == null || config.unsafePasswords.length == 0) {
                config.unsafePasswords = DEFAULT_UNSAFE_PASSWORDS;
            }
        }
    }

    private static void writeUnsafePasswordsFile(Path file, String[] passwords) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# EN: Passwords that are never accepted (one per line, compared case-insensitively).\n");
        sb.append("# RU: Запрещённые пароли (по одному на строку, сравнение без учёта регистра).\n");
        sb.append("# Lines starting with # are ignored.\n");
        sb.append('\n');
        for (String password : passwords) {
            if (password != null && !password.isBlank()) {
                sb.append(password.trim()).append('\n');
            }
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }
}
