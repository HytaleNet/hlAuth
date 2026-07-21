package com.authme.hytale.service;

import com.authme.hytale.AuthMePlugin;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies player UUIDs against the Hytale profile service
 * (<a href="https://playerdb.co">playerdb.co</a>).
 *
 * <p>A profile that resolves for the player's UUID means the player joined with
 * a licensed (premium) account. Offline clients use a different UUID, so the
 * lookup returns "not found" for them.</p>
 */
public final class PremiumService {

    public enum Status {
        /** Profile found: licensed account. */
        PREMIUM,
        /** Profile not found: offline/cracked account. */
        OFFLINE,
        /** Lookup failed (network error, timeout): fall back to the normal flow. */
        UNKNOWN
    }

    private static final String API_URL = "https://playerdb.co/api/player/hytale/";

    private final AuthMePlugin plugin;
    private final HttpClient httpClient;
    private final Map<UUID, CompletableFuture<Status>> pending = new ConcurrentHashMap<>();

    public PremiumService(AuthMePlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /** Starts the async lookup for a connecting player (call on connect). */
    public void startCheck(UUID uuid) {
        pending.computeIfAbsent(uuid, this::lookup);
    }

    /** Result of the lookup started by {@link #startCheck}. Never completes exceptionally. */
    public CompletableFuture<Status> getResult(UUID uuid) {
        return pending.computeIfAbsent(uuid, this::lookup);
    }

    /** True when the connected player was verified as premium in this session. */
    public boolean isVerifiedPremium(UUID uuid) {
        CompletableFuture<Status> future = pending.get(uuid);
        return future != null && future.getNow(Status.UNKNOWN) == Status.PREMIUM;
    }

    /** Forgets the session result (call on disconnect). */
    public void clear(UUID uuid) {
        pending.remove(uuid);
    }

    private CompletableFuture<Status> lookup(UUID uuid) {
        int timeoutSeconds = Math.max(1, plugin.getConfig().premiumCheckTimeoutSeconds);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL + uuid))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", "hlAuth/HytaleNet")
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                String body = response.body();
                if (body == null || body.isBlank()) {
                    plugin.getLogger().atWarning().log(
                        "Empty premium lookup response for %s (HTTP %d)", uuid, response.statusCode());
                    return Status.UNKNOWN;
                }
                return parse(uuid, body);
            })
            .exceptionally(e -> {
                plugin.getLogger().atWarning().log(
                    "Premium lookup failed for %s: %s", uuid, e.getMessage());
                return Status.UNKNOWN;
            });
    }

    private Status parse(UUID uuid, String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String code = json.has("code") ? json.get("code").getAsString() : "";
            if ("player.found".equals(code)
                    && json.has("success") && json.get("success").getAsBoolean()) {
                return Status.PREMIUM;
            }
            // e.g. "hytale.not_found" / "player.not_found"
            if (code.endsWith("not_found")) {
                return Status.OFFLINE;
            }
            plugin.getLogger().atWarning().log(
                "Unexpected premium lookup response for %s: %s", uuid, code);
            return Status.UNKNOWN;
        } catch (Exception e) {
            plugin.getLogger().atWarning().log(
                "Failed to parse premium lookup response for %s", uuid);
            return Status.UNKNOWN;
        }
    }
}
