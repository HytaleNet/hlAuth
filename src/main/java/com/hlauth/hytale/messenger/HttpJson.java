package com.hlauth.hytale.messenger;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Small JSON HTTP helper (Telegram Bot API / Discord REST). */
final class HttpJson {

    private HttpJson() {
    }

    static String get(String url, int connectMs, int readMs, @Nullable String authorization) throws IOException {
        return exchange("GET", url, null, connectMs, readMs, authorization, null);
    }

    static String get(String url, int connectMs, int readMs, @Nullable String authorization,
                      @Nullable java.net.Proxy proxy) throws IOException {
        return exchange("GET", url, null, connectMs, readMs, authorization, proxy);
    }

    static String post(String url, String jsonBody, int connectMs, int readMs, @Nullable String authorization)
            throws IOException {
        return exchange("POST", url, jsonBody, connectMs, readMs, authorization, null);
    }

    static String post(String url, String jsonBody, int connectMs, int readMs, @Nullable String authorization,
                       @Nullable java.net.Proxy proxy) throws IOException {
        return exchange("POST", url, jsonBody, connectMs, readMs, authorization, proxy);
    }

    static String put(String url, String jsonBody, int connectMs, int readMs, @Nullable String authorization,
                      @Nullable java.net.Proxy proxy) throws IOException {
        return exchange("PUT", url, jsonBody, connectMs, readMs, authorization, proxy);
    }

    static JsonElement parse(String json) {
        return JsonParser.parseString(json == null || json.isBlank() ? "{}" : json);
    }

    private static String exchange(String method, String url, @Nullable String jsonBody,
                                   int connectMs, int readMs, @Nullable String authorization,
                                   @Nullable java.net.Proxy proxy) throws IOException {
        HttpURLConnection http = (HttpURLConnection) URI.create(url).toURL()
            .openConnection(proxy == null ? java.net.Proxy.NO_PROXY : proxy);
        http.setRequestMethod(method);
        http.setConnectTimeout(connectMs);
        http.setReadTimeout(readMs);
        http.setInstanceFollowRedirects(true);
        http.setRequestProperty("Accept", "application/json");
        // Discord rejects non-conforming user agents; the DiscordBot format is required for bots
        http.setRequestProperty("User-Agent",
            authorization != null && authorization.startsWith("Bot ")
                ? "DiscordBot (https://hlauncher.com, 1.1.0)"
                : "hlAuth/1.1.0");
        if (authorization != null && !authorization.isBlank()) {
            http.setRequestProperty("Authorization", authorization);
        }
        if (jsonBody != null) {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            http.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream out = http.getOutputStream()) {
                out.write(bytes);
            }
        }
        int status = http.getResponseCode();
        InputStream stream = status >= 400 ? http.getErrorStream() : http.getInputStream();
        if (stream == null) {
            http.disconnect();
            if (status >= 400) {
                throw new IOException("HTTP " + status);
            }
            return "";
        }
        try (InputStream in = stream) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= 400) {
                throw new IOException("HTTP " + status + ": " + body);
            }
            return body;
        } finally {
            http.disconnect();
        }
    }
}
