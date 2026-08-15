package com.authme.hytale.service;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.config.AuthMeConfig;
import com.authme.hytale.data.PlayerAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Writes timestamped JSON snapshots of every account (works for json / H2 / MySQL)
 * and optionally runs them on a timer.
 */
public final class BackupService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String PREFIX = "hlauth-";
    private static final String SUFFIX = ".json";

    public record Result(boolean success, @Nullable Path file, int count, @Nullable String error) {
        static Result ok(Path file, int count) {
            return new Result(true, file, count, null);
        }

        static Result fail(String error) {
            return new Result(false, null, 0, error);
        }
    }

    private final AuthMePlugin plugin;
    private ScheduledFuture<?> task;

    public BackupService(AuthMePlugin plugin) {
        this.plugin = plugin;
    }

    public Path backupDirectory() {
        return plugin.getDataDirectory().resolve("backups");
    }

    public Result createBackup() {
        try {
            Path dir = backupDirectory();
            Files.createDirectories(dir);
            List<PlayerAuth> accounts = new ArrayList<>(plugin.getDataSource().getAllAuths());
            String name = PREFIX + LocalDateTime.now().format(STAMP) + SUFFIX;
            Path target = dir.resolve(name);
            Path temp = dir.resolve(name + ".tmp");
            Files.writeString(temp, GSON.toJson(accounts), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            pruneOldBackups(dir);
            plugin.getLogger().atInfo().log("Database backup saved: %s (%d account(s))",
                target.getFileName(), accounts.size());
            return Result.ok(target, accounts.size());
        } catch (IOException e) {
            plugin.getLogger().atSevere().withCause(e).log("Database backup failed");
            return Result.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public void start() {
        stop();
        AuthMeConfig config = plugin.getConfig();
        if (!config.backupEnabled) {
            return;
        }
        long minutes = intervalMinutes(config);
        if (minutes < 1) {
            plugin.getLogger().atWarning().log(
                "Automatic backups are enabled but the interval is 0; set backupIntervalHours / backupIntervalMinutes");
            return;
        }
        task = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
            this::runScheduled, minutes, minutes, TimeUnit.MINUTES);
        plugin.getLogger().atInfo().log("Automatic database backups every %d minute(s) (keep %d)",
            minutes, config.backupKeepCount);
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private void runScheduled() {
        try {
            Result result = createBackup();
            if (!result.success()) {
                plugin.getLogger().atWarning().log("Scheduled backup failed: %s", result.error());
            }
        } catch (Exception e) {
            plugin.getLogger().atSevere().withCause(e).log("Scheduled backup crashed");
        }
    }

    static long intervalMinutes(AuthMeConfig config) {
        long hours = Math.max(0, config.backupIntervalHours);
        long minutes = Math.max(0, config.backupIntervalMinutes);
        return hours * 60L + minutes;
    }

    private void pruneOldBackups(Path dir) throws IOException {
        int keep = plugin.getConfig().backupKeepCount;
        if (keep <= 0) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        }
        files.sort(Comparator.comparingLong(BackupService::mtime).reversed());
        for (int i = keep; i < files.size(); i++) {
            Files.deleteIfExists(files.get(i));
        }
    }

    private static long mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
