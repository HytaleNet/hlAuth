package com.authme.hytale.message;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side message catalog. Messages live in the plugin data directory under
 * {@code messages/<language>.yml} (flat {@code key: value} YAML). The language
 * is selected with the {@code language} option in config.json; additional
 * languages can be added by dropping new .yml files into the folder.
 *
 * <p>On each load, missing keys from the jar defaults are appended to the
 * existing file so updates to the plugin pick up new strings without wiping
 * custom edits.</p>
 */
public final class Messages {

    /** Languages whose defaults are bundled inside the plugin jar. */
    private static final String[] BUNDLED_LANGUAGES = {"ru", "en"};

    private final HytaleLogger logger;
    private final Map<String, String> texts = new HashMap<>();
    private final Map<String, String> fallback = new HashMap<>();

    public Messages(Path dataDirectory, String language, HytaleLogger logger) {
        this.logger = logger;
        Path messagesDir = dataDirectory.resolve("messages");
        try {
            Files.createDirectories(messagesDir);
            for (String bundled : BUNDLED_LANGUAGES) {
                syncBundledFile(messagesDir, bundled);
            }
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to sync default message files");
        }

        // English is always loaded as fallback for missing keys (disk + jar)
        fallback.putAll(loadBundledFromJar("en"));
        fallback.putAll(parseYaml(messagesDir.resolve("en.yml")));

        Map<String, String> jarLang = loadBundledFromJar(language);
        Path languageFile = messagesDir.resolve(language + ".yml");
        if (Files.exists(languageFile)) {
            texts.putAll(jarLang);
            texts.putAll(parseYaml(languageFile));
        } else {
            logger.atWarning().log("Message file messages/%s.yml not found, falling back to en", language);
            texts.putAll(fallback);
        }
        // Ensure jar defaults fill any gaps even if sync failed
        for (Map.Entry<String, String> e : jarLang.entrySet()) {
            texts.putIfAbsent(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : fallback.entrySet()) {
            texts.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    /**
     * Creates the language file from the jar if missing, otherwise appends any
     * keys that exist in the jar but not yet on disk.
     */
    private void syncBundledFile(Path messagesDir, String language) throws IOException {
        Path target = messagesDir.resolve(language + ".yml");
        Map<String, String> jarDefaults = loadBundledFromJar(language);
        if (jarDefaults.isEmpty()) {
            return;
        }
        if (!Files.exists(target)) {
            try (InputStream in = Messages.class.getResourceAsStream("/messages/" + language + ".yml")) {
                if (in != null) {
                    Files.copy(in, target);
                }
            }
            return;
        }

        Map<String, String> onDisk = parseYaml(target);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : jarDefaults.entrySet()) {
            if (!onDisk.containsKey(e.getKey())) {
                missing.add(e.getKey() + ": \"" + escapeYaml(e.getValue()) + "\"");
            }
        }
        if (!missing.isEmpty()) {
            List<String> lines = new ArrayList<>(Files.readAllLines(target, StandardCharsets.UTF_8));
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add("# --- auto-added by AuthMe update ---");
            lines.addAll(missing);
            Files.write(target, lines, StandardCharsets.UTF_8);
            logger.atInfo().log("Added %d missing message key(s) to messages/%s.yml",
                missing.size(), language);
        }
    }

    private Map<String, String> loadBundledFromJar(String language) {
        Map<String, String> result = new LinkedHashMap<>();
        try (InputStream in = Messages.class.getResourceAsStream("/messages/" + language + ".yml")) {
            if (in == null) {
                return result;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseLine(line, result);
                }
            }
        } catch (IOException e) {
            logger.atWarning().withCause(e).log("Failed to read bundled messages/%s.yml", language);
        }
        return result;
    }

    private static String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Raw localized text with {placeholder} substitution. */
    public String text(String key, String... params) {
        String value = texts.get(key);
        if (value == null) {
            value = fallback.get(key);
        }
        if (value == null) {
            return key;
        }
        for (int i = 0; i + 1 < params.length; i += 2) {
            value = value.replace("{" + params[i] + "}", params[i + 1]);
        }
        return value;
    }

    /** Localized text wrapped as a Hytale {@link Message}. */
    public Message get(String key, String... params) {
        return Message.raw(text(key, params));
    }

    private Map<String, String> parseYaml(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return result;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                parseLine(line, result);
            }
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to read message file %s", file);
        }
        return result;
    }

    private static void parseLine(String line, Map<String, String> result) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String key = trimmed.substring(0, colon).trim();
        String value = trimmed.substring(colon + 1).trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1);
        }
        result.put(key, value);
    }
}
