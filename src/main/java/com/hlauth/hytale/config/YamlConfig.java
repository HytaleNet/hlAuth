package com.hlauth.hytale.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal flat-YAML reader/writer used for all plugin config files.
 * Supports {@code key: value} scalars and {@code key:} followed by {@code - item} lists.
 * Comments ({@code #}) and blank lines are ignored on read.
 */
public final class YamlConfig {

    private YamlConfig() {
    }

    /** Parses a flat YAML file into scalars (String) and lists (List&lt;String&gt;). */
    public static Map<String, Object> parse(Path file) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return result;
        }
        String pendingListKey = null;
        List<String> pendingList = null;
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ") || line.equals("-")) {
                if (pendingListKey != null) {
                    String item = line.length() > 1 ? line.substring(1).strip() : "";
                    pendingList.add(unquote(item));
                }
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (pendingListKey != null) {
                result.put(pendingListKey, pendingList);
                pendingListKey = null;
                pendingList = null;
            }
            String key = line.substring(0, colon).strip();
            String value = stripInlineComment(line.substring(colon + 1).strip());
            if (value.isEmpty()) {
                pendingListKey = key;
                pendingList = new ArrayList<>();
            } else if (value.equals("[]")) {
                result.put(key, new ArrayList<String>());
            } else {
                result.put(key, unquote(value));
            }
        }
        if (pendingListKey != null) {
            result.put(pendingListKey, pendingList);
        }
        return result;
    }

    private static String stripInlineComment(String value) {
        // Only strip comments outside of quotes
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '#' && !inDouble && !inSingle && i > 0 && value.charAt(i - 1) == ' ') {
                return value.substring(0, i).strip();
            }
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            String inner = value.substring(1, value.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    public static String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v instanceof String s ? s : def;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.equals("true") || t.equals("yes") || t.equals("on")) {
                return true;
            }
            if (t.equals("false") || t.equals("no") || t.equals("off")) {
                return false;
            }
        }
        return def;
    }

    public static int integer(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static String[] list(Map<String, Object> map, String key, String[] def) {
        Object v = map.get(key);
        if (v instanceof List<?> l) {
            return ((List<String>) l).toArray(String[]::new);
        }
        return def;
    }

    /** Quotes a string value for writing ("..." with backslash escaping). */
    public static String quote(String s) {
        String v = s == null ? "" : s;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
