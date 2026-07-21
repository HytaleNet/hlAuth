package com.authme.hytale;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * Copies config/accounts from legacy plugin data folders into the current one
 * so renames (AuthMe → hlAuth) do not wipe existing player accounts.
 *
 * <p>Does nothing if {@code accounts.json} already exists in the current directory.</p>
 */
final class DataDirectoryMigrator {

    private static final List<String> LEGACY_FOLDER_NAMES = List.of(
        "AuthMe_AuthMeHytale",
        "AuthMe_hlAuth",
        "HytaleNet_AuthMeHytale"
    );

    private DataDirectoryMigrator() {
    }

    static void migrateIfNeeded(Path currentDataDir, HytaleLogger logger) {
        Path accounts = currentDataDir.resolve("accounts.json");
        if (Files.exists(accounts)) {
            return;
        }

        Path modsRoot = currentDataDir.getParent();
        if (modsRoot == null) {
            return;
        }

        for (String legacyName : LEGACY_FOLDER_NAMES) {
            Path legacy = modsRoot.resolve(legacyName);
            if (!Files.isDirectory(legacy)) {
                continue;
            }
            Path legacyAccounts = legacy.resolve("accounts.json");
            if (!Files.exists(legacyAccounts)) {
                continue;
            }
            try {
                Files.createDirectories(currentDataDir);
                copyTree(legacy, currentDataDir);
                logger.atInfo().log(
                    "Migrated AuthMe data from '%s' to '%s' (accounts/config preserved).",
                    legacy.getFileName(), currentDataDir.getFileName());
                return;
            } catch (IOException e) {
                logger.atSevere().withCause(e).log(
                    "Failed to migrate data from %s", legacy);
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(dir).toString());
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file).toString());
                if (!Files.exists(dest)) {
                    Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
