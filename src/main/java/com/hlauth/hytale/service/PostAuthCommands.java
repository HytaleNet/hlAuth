package com.hlauth.hytale.service;

import com.hlauth.hytale.HlAuthPlugin;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs configured console/player commands after a successful registration or login.
 */
public final class PostAuthCommands {

    public enum Kind {
        REGISTER, LOGIN
    }

    private static final long MAX_WAIT_MS = 300_000L;

    private final HlAuthPlugin plugin;

    public PostAuthCommands(HlAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void run(PlayerRef player, Kind kind) {
        if (player == null || kind == null) {
            return;
        }
        String[] lines = kind == Kind.REGISTER
            ? plugin.getConfig().registerCommands
            : plugin.getConfig().loginCommands;
        runFrom(player, kind, lines, 0);
    }

    private void runFrom(PlayerRef player, Kind kind, String[] lines, int start) {
        if (lines == null || start >= lines.length || !player.isValid()) {
            return;
        }
        for (int i = start; i < lines.length; i++) {
            String raw = lines[i] == null ? "" : lines[i].trim();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) {
                continue;
            }
            int tagEnd = raw.startsWith("[") ? raw.indexOf(']') : -1;
            if (tagEnd <= 1) {
                plugin.getLogger().atWarning().log(
                    "Ignoring post-auth command without [CONSOLE]/[PLAYER]/[WAIT] tag: %s", raw);
                continue;
            }
            String tag = raw.substring(1, tagEnd).trim().toUpperCase(Locale.ROOT);
            String rest = raw.substring(tagEnd + 1).trim();
            if ("WAIT".equals(tag)) {
                long delay = parseWait(rest);
                int next = i + 1;
                HytaleServer.SCHEDULED_EXECUTOR.schedule(
                    () -> runFrom(player, kind, lines, next),
                    delay, TimeUnit.MILLISECONDS);
                return;
            }
            String command = substitute(rest, player);
            if (command.isEmpty()) {
                continue;
            }
            dispatch(player, tag, command);
        }
    }

    private void dispatch(PlayerRef player, String tag, String command) {
        CommandSender sender;
        if ("PLAYER".equals(tag)) {
            sender = player;
        } else if ("CONSOLE".equals(tag)) {
            sender = ConsoleSender.INSTANCE;
        } else {
            plugin.getLogger().atWarning().log("Unknown post-auth command tag [%s]", tag);
            return;
        }
        try {
            CommandManager.get().handleCommand(sender, command);
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e)
                .log("Failed to run post-auth command as %s: %s", tag, command);
        }
    }

    private static String substitute(String command, PlayerRef player) {
        String nick = player.getUsername();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        return command.replace("{nick}", nick).replace("{player}", nick);
    }

    private static long parseWait(String rest) {
        try {
            long ms = Long.parseLong(rest.trim());
            if (ms < 0) {
                return 0;
            }
            return Math.min(ms, MAX_WAIT_MS);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
