/*
 * ModifoldAnalytics is a Hytale mod analytics module for tracking your mod
 * directly on the Modifold website.
 *
 * You can conveniently view analytics for your mod, for example to know
 * how many players are currently playing with it.
 *
 * This file is designed to be copied into your mod's source code, so you
 * can easily integrate ModifoldAnalytics into your mod.
 *
 * Please do not modify the code in this file, except for the package name.
 *
 * This mod is built using code from https://hstats.dev/.
 * Special thanks to Al3xWarrior!
*/
package com.hlauth.hytale.metrics;

import com.google.gson.Gson;
import com.hypixel.hytale.server.core.universe.Universe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class ModifoldAnalytics implements AutoCloseable {
    private final String URL_BASE = "https://api.modifold.com/analytics/";
    private final boolean DEBUG = false; // This is for development purposes only
    private static final Gson GSON = new Gson();

    private final String projectSlug;
    private final String modVersion;
    private final String serverUUID;
    private final ScheduledExecutorService scheduler;

    /**
     * Initializes ModifoldAnalytics for your mod.
     *
     * @param projectSlug The unique slug of your project.
     * @param modVersion The current version of your mod. This is determined by you.
     */
    public ModifoldAnalytics(String projectSlug, String modVersion) {
        this.projectSlug = projectSlug;
        this.modVersion = modVersion;
        this.scheduler = createScheduler();

        // Get or create the server UUID
        this.serverUUID = getServerUUID();
        if(this.serverUUID == null) {
            System.out.println("[ModifoldAnalytics] Metrics are disabled on this server.");
            this.scheduler.shutdown();
            return; // Metrics disabled by server owner
        }

        // Avoid blocking plugin startup: all network calls run on dedicated scheduler thread.
        this.scheduler.execute(this::addModToServer);
        this.scheduler.scheduleAtFixedRate(this::logMetrics, 2, 2, TimeUnit.MINUTES);
    }

    public ModifoldAnalytics(String projectSlug) {
        this(projectSlug, "Unknown");
    }

    private void logMetrics() {
        Map<String, String> arguments = new HashMap<>();
        arguments.put("server_uuid", this.serverUUID);
        arguments.put("players_online", String.valueOf(getOnlinePlayerCount()));
        arguments.put("os_name", System.getProperty("os.name"));
        arguments.put("os_version", System.getProperty("os.version"));
        arguments.put("java_version", System.getProperty("java.version"));
        arguments.put("cores", String.valueOf(Runtime.getRuntime().availableProcessors()));

        sendRequest(buildProjectUrl("server/update-server"), arguments);
    }

    private void addModToServer() {
        System.out.println("[ModifoldAnalytics] Adding mod to server metrics...");

        Map<String, String> arguments = new HashMap<>();
        arguments.put("server_uuid", this.serverUUID);
        arguments.put("plugin_version", this.modVersion);

        sendRequest(buildProjectUrl("server/add-plugin"), arguments);
    }

    private String buildProjectUrl(String endpoint) {
        String encodedSlug = URLEncoder.encode(this.projectSlug, StandardCharsets.UTF_8).replace("+", "%20");
        return URL_BASE + encodedSlug + "/" + endpoint;
    }

    private String getServerUUID() {
        Path serverUUIDFile = Paths.get("modifold-analytics-server-uuid.txt");

        try {
            if(Files.exists(serverUUIDFile)) {
                String content = Files.readString(serverUUIDFile);
                content = content.trim();
                String[] lines = content.split("\n");
                if(lines.length < 5) {
                    return null;
                }

                String enabled = lines[3].split("=")[1].trim();
                if(!enabled.equalsIgnoreCase("true")) {
                    return null;
                }

                return lines[4];
            } else {
                String uuid = UUID.randomUUID().toString();
                Files.writeString(serverUUIDFile, "ModifoldAnalytics - Hytale Mod Metrics\nModifoldAnalytics is a simple metrics system for Hytale mods. This file is here because one of your mods/plugins uses it, please do not modify the UUID. ModifoldAnalytics will apply little to no effect on your server and analytics are anonymous, however you can still disable it.\n\nenabled=true\n" + uuid);
                return uuid;
            }
        } catch (IOException e) {
            return null;
        }
    }

    private void sendRequest(String urlString, Map<String, String> arguments) {
        try {
            URL url = URI.create(urlString).toURL();
            HttpURLConnection http = (HttpURLConnection) url.openConnection();

            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            http.setConnectTimeout(15_000);
            http.setReadTimeout(15_000);

            String jsonBody = GSON.toJson(arguments);
            byte[] out = jsonBody.getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(out.length);

            try(OutputStream os = http.getOutputStream()) {
                os.write(out);
            }

            int code = http.getResponseCode(); // forces request to actually send
            InputStream is = (code >= 200 && code < 300) ? http.getInputStream() : http.getErrorStream();
            String body = (is != null) ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";

            if(DEBUG) {
                System.out.println("Metrics POST -> " + code + " " + body);
            }

            http.disconnect();
        } catch (Exception e) {
            // pass
        }
    }

    private ScheduledExecutorService createScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "modifold-analytics-scheduler");
            thread.setDaemon(true);
            return thread;
        };

        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    @Override
    public void close() {
        this.scheduler.shutdown();
    }

    private int getOnlinePlayerCount() {
        return Universe.get().getPlayerCount();
    }
}
