package com.authme.hytale.storage;

import com.authme.hytale.data.PlayerAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flat-file JSON storage ({@code accounts.json} in the plugin data directory).
 *
 * <p>All accounts are kept in memory in a {@link ConcurrentHashMap}; writes are
 * flushed asynchronously and atomically (write to temp file, then move).</p>
 */
public final class JsonDataSource implements DataSource {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<PlayerAuth>>() { }.getType();

    private final Path file;
    private final Path tempFile;
    private final HytaleLogger logger;
    private final Map<String, PlayerAuth> accounts = new ConcurrentHashMap<>();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hlAuth-Storage");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean savePending = new AtomicBoolean(false);

    public JsonDataSource(Path dataDirectory, HytaleLogger logger) {
        this.file = dataDirectory.resolve("accounts.json");
        this.tempFile = dataDirectory.resolve("accounts.json.tmp");
        this.logger = logger;
        load(dataDirectory);
    }

    private void load(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                List<PlayerAuth> list = GSON.fromJson(json, LIST_TYPE);
                if (list != null) {
                    for (PlayerAuth auth : list) {
                        if (auth != null && auth.name != null) {
                            accounts.put(auth.name, auth);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to read accounts.json");
        }
    }

    @Override
    public String getName() {
        return "JSON flat-file";
    }

    @Override
    public boolean isRegistered(String name) {
        return accounts.containsKey(name.toLowerCase());
    }

    @Override
    @Nullable
    public PlayerAuth getAuth(String name) {
        return accounts.get(name.toLowerCase());
    }

    @Override
    public void saveAuth(PlayerAuth auth) {
        accounts.put(auth.name, auth);
        scheduleSave();
    }

    @Override
    public void updateAuth(PlayerAuth auth) {
        accounts.put(auth.name, auth);
        scheduleSave();
    }

    @Override
    public boolean removeAuth(String name) {
        boolean removed = accounts.remove(name.toLowerCase()) != null;
        if (removed) {
            scheduleSave();
        }
        return removed;
    }

    @Override
    public int getAccountsCount() {
        return accounts.size();
    }

    @Override
    public int countByRegistrationIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (PlayerAuth auth : accounts.values()) {
            if (ip.equals(auth.registrationIp)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Collection<PlayerAuth> getAllAuths() {
        return new ArrayList<>(accounts.values());
    }

    /** Coalesces bursts of changes into a single asynchronous write. */
    private void scheduleSave() {
        if (savePending.compareAndSet(false, true)) {
            saveExecutor.submit(() -> {
                savePending.set(false);
                writeToDisk();
            });
        }
    }

    private synchronized void writeToDisk() {
        try {
            String json = GSON.toJson(new ArrayList<>(accounts.values()), LIST_TYPE);
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.atSevere().withCause(e).log("Failed to write accounts.json");
        }
    }

    @Override
    public void close() {
        writeToDisk();
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveExecutor.shutdownNow();
        }
    }
}
