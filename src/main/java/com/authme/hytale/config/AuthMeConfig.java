package com.authme.hytale.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plugin configuration, stored as {@code config.json} inside the plugin data directory.
 * The file is written with bilingual (RU / EN) comments; missing fields fall back to defaults.
 */
public final class AuthMeConfig {

    private static final Gson GSON = new GsonBuilder().create();

    // --- General ---
    /** Message language: file name in the messages/ folder (without .yml), e.g. "ru" or "en". */
    public String language = "ru";

    // --- Registration ---
    /** Whether new players must register. When false, unregistered players play without auth. */
    public boolean registrationEnabled = true;
    /** Kick players that fail to authenticate in time. */
    public int timeoutSeconds = 120;
    /** Minimum password length. */
    public int passwordMinLength = 5;
    /** Maximum password length. */
    public int passwordMaxLength = 64;
    /** Lowercase passwords that are never accepted. */
    public String[] unsafePasswords = {"123456", "12345678", "password", "qwerty", "111111"};
    /** Maximum accounts registered per IP address (0 = unlimited). */
    public int maxRegistrationsPerIp = 2;

    // --- Login ---
    /** Number of wrong password attempts before the player is kicked. */
    public int maxLoginTries = 5;
    /** Kick players who try to log in with a wrong password. */
    public boolean kickOnWrongPassword = false;

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

    public static AuthMeConfig load(Path dataDirectory, HytaleLogger logger) {
        Path file = dataDirectory.resolve("config.json");
        AuthMeConfig config = new AuthMeConfig();
        try {
            if (Files.exists(file)) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                AuthMeConfig loaded = GSON.fromJson(stripJsonComments(raw), AuthMeConfig.class);
                if (loaded != null) {
                    config = mergeDefaults(loaded);
                }
            }
            // Always rewrite so new options and bilingual comments appear after updates
            Files.createDirectories(dataDirectory);
            Files.writeString(file, toCommentedJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to load config.json, using defaults");
        }
        return config;
    }

    /**
     * Fills null arrays left by partial JSON so runtime code never NPEs.
     */
    private static AuthMeConfig mergeDefaults(AuthMeConfig loaded) {
        AuthMeConfig defaults = new AuthMeConfig();
        if (loaded.language == null || loaded.language.isBlank()) {
            loaded.language = defaults.language;
        }
        if (loaded.unsafePasswords == null) {
            loaded.unsafePasswords = defaults.unsafePasswords;
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

    /** Removes line (//) and block comments from JSONC so Gson can parse the file. */
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

    /** Pretty JSON with bilingual comments for every option. */
    static String toCommentedJson(AuthMeConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("\n");
        sb.append("  // ── General / Общие ──\n");
        field(sb, "language", quote(c.language),
            "EN: Message language file in messages/ (without .yml), e.g. \"ru\" or \"en\".",
            "RU: Язык сообщений — файл в messages/ без .yml, например \"ru\" или \"en\".");

        sb.append("\n");
        sb.append("  // ── Registration / Регистрация ──\n");
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
        field(sb, "unsafePasswords", toJsonArray(c.unsafePasswords),
            "EN: Passwords that are never accepted (compared case-insensitively).",
            "RU: Запрещённые пароли (сравнение без учёта регистра).");
        field(sb, "maxRegistrationsPerIp", String.valueOf(c.maxRegistrationsPerIp),
            "EN: Max accounts per IP (0 = unlimited).",
            "RU: Максимум аккаунтов с одного IP (0 = без лимита).");

        sb.append("\n");
        sb.append("  // ── Login / Вход ──\n");
        field(sb, "maxLoginTries", String.valueOf(c.maxLoginTries),
            "EN: Wrong password attempts before the player is kicked.",
            "RU: Число неверных попыток пароля до кика.");
        field(sb, "kickOnWrongPassword", String.valueOf(c.kickOnWrongPassword),
            "EN: Kick immediately on a wrong password.",
            "RU: Кикать сразу при неверном пароле.");

        sb.append("\n");
        sb.append("  // ── Sessions / Сессии ──\n");
        field(sb, "sessionsEnabled", String.valueOf(c.sessionsEnabled),
            "EN: Auto re-login when the same player reconnects from the same IP.",
            "RU: Автовход при переподключении того же игрока с того же IP.");
        field(sb, "sessionTimeoutMinutes", String.valueOf(c.sessionTimeoutMinutes),
            "EN: How long a session stays valid, in minutes.",
            "RU: Время жизни сессии в минутах.");

        sb.append("\n");
        sb.append("  // ── UI / Интерфейс ──\n");
        field(sb, "useUiMenus", String.valueOf(c.useUiMenus),
            "EN: Open native login/register UI on join. false = chat commands only.",
            "RU: Открывать UI входа/регистрации при входе. false = только команды в чате.");
        field(sb, "uiOpenDelayMs", String.valueOf(c.uiOpenDelayMs),
            "EN: Delay in ms before opening the menu (reduces UI texture glitches).",
            "RU: Задержка в мс перед открытием меню (меньше глитчей текстур UI).");

        sb.append("\n");
        sb.append("  // ── Premium (playerdb.co) ──\n");
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

        sb.append("\n");
        sb.append("  // ── Protection / Защита ──\n");
        field(sb, "protectChat", String.valueOf(c.protectChat),
            "EN: Block chat messages from players who are not logged in.",
            "RU: Блокировать чат игроков, которые ещё не вошли.");
        fieldLast(sb, "messageIntervalSeconds", String.valueOf(c.messageIntervalSeconds),
            "EN: Seconds between \"please login\" chat reminders (0 = disabled).",
            "RU: Интервал напоминаний «войдите» в чате в секундах (0 = выкл).");

        sb.append("}\n");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String key, String value, String en, String ru) {
        sb.append("  // ").append(en).append('\n');
        sb.append("  // ").append(ru).append('\n');
        sb.append("  \"").append(key).append("\": ").append(value).append(",\n");
        sb.append('\n');
    }

    private static void fieldLast(StringBuilder sb, String key, String value, String en, String ru) {
        sb.append("  // ").append(en).append('\n');
        sb.append("  // ").append(ru).append('\n');
        sb.append("  \"").append(key).append("\": ").append(value).append('\n');
    }

    private static String quote(String s) {
        return GSON.toJson(s == null ? "" : s);
    }

    private static String toJsonArray(String[] values) {
        return GSON.toJson(values == null ? new String[0] : values);
    }
}
