package com.hlauth.hytale.service;

import com.hlauth.hytale.HlAuthPlugin;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime GeoIP loader: downloads GeoLite MMDB + lightweight reader jars and resolves city/ASN.
 */
public final class GeoIpService {

    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public java.util.List<Proxy> select(URI uri) {
            return java.util.List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    };

    private static final HexFormat HEX = HexFormat.of();

    private static final String CITY_DB_URL =
        "https://github.com/P3TERX/GeoLite.mmdb/raw/download/GeoLite2-City.mmdb";
    private static final String ASN_DB_URL =
        "https://github.com/P3TERX/GeoLite.mmdb/raw/download/GeoLite2-ASN.mmdb";

    private static final Artifact GEOIP2 = new Artifact(
        "geoip2-4.2.1.jar",
        "https://repo1.maven.org/maven2/com/maxmind/geoip2/geoip2/4.2.1/geoip2-4.2.1.jar",
        "1492bf8d29f8059c9e2f6ec2ce85c5e05e2d379a366427c8923fef124801c118");
    private static final Artifact MAXMIND_DB = new Artifact(
        "maxmind-db-3.1.0.jar",
        "https://repo1.maven.org/maven2/com/maxmind/db/maxmind-db/3.1.0/maxmind-db-3.1.0.jar",
        "2c8033081b1215dc57f5ae0e0de95cf91eb54144fccae4db35a47eb5d1101afc");

    private final HlAuthPlugin plugin;
    private final Map<String, GeoInfo> cache = new ConcurrentHashMap<>();

    private volatile Object cityReader;
    private volatile Object asnReader;
    private volatile Method cityLookup;
    private volatile Method asnLookup;
    private volatile boolean ready;

    public record GeoInfo(String city, String country, String asn) {
        public String toOneLine() {
            String c = city == null || city.isBlank() ? "unknown city" : city;
            String k = country == null || country.isBlank() ? "unknown country" : country;
            String a = asn == null || asn.isBlank() ? "unknown ASN" : asn;
            return c + ", " + k + ", " + a;
        }
    }

    private record Artifact(String fileName, String url, String sha256) {
    }

    public GeoIpService(HlAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Thread t = new Thread(this::load, "hlAuth-GeoIP");
        t.setDaemon(true);
        t.start();
    }

    private void load() {
        try {
            Path dataDir = plugin.getDataDirectory();
            Path geoDir = dataDir.resolve("geoip");
            Path libDir = dataDir.resolve("lib");
            Files.createDirectories(geoDir);
            Files.createDirectories(libDir);

            Path geoip2Jar = ensureArtifact(libDir, GEOIP2);
            Path maxmindJar = ensureArtifact(libDir, MAXMIND_DB);
            Path cityDb = ensureMmdb(geoDir.resolve("GeoLite2-City.mmdb"), CITY_DB_URL);
            Path asnDb = ensureMmdb(geoDir.resolve("GeoLite2-ASN.mmdb"), ASN_DB_URL);
            if (cityDb == null) {
                plugin.getLogger().atWarning().log(
                    "GeoIP City database missing (download failed). Admin geo fields will show unknown.");
                return;
            }

            URLClassLoader loader = new URLClassLoader(
                new URL[] {geoip2Jar.toUri().toURL(), maxmindJar.toUri().toURL()},
                GeoIpService.class.getClassLoader());
            Class<?> builderClass = Class.forName("com.maxmind.geoip2.DatabaseReader$Builder", true, loader);
            Method buildMethod = builderClass.getMethod("build");

            Object cityBuilder = builderClass.getConstructor(java.io.File.class).newInstance(cityDb.toFile());
            Object cityReaderObj = buildMethod.invoke(cityBuilder);
            cityLookup = cityReaderObj.getClass().getMethod("city", InetAddress.class);
            cityReader = cityReaderObj;

            if (asnDb != null) {
                Object asnBuilder = builderClass.getConstructor(java.io.File.class).newInstance(asnDb.toFile());
                Object asnReaderObj = buildMethod.invoke(asnBuilder);
                asnLookup = asnReaderObj.getClass().getMethod("asn", InetAddress.class);
                asnReader = asnReaderObj;
            } else {
                plugin.getLogger().atWarning().log(
                    "GeoIP ASN database missing; city/country will still work.");
            }

            ready = true;
            plugin.getLogger().atInfo().log("GeoIP ready: GeoLite2 City%s loaded",
                asnReader == null ? "" : " + ASN");
        } catch (Exception e) {
            ready = false;
            plugin.getLogger().atWarning().log(
                "GeoIP initialization failed (%s). Admin geo fields will show unknown.",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    public void stop() {
        ready = false;
        cache.clear();
    }

    public String describe(@Nullable String ip) {
        GeoInfo info = lookup(ip);
        return info == null ? "unknown city, unknown country, unknown ASN" : info.toOneLine();
    }

    @Nullable
    public GeoInfo lookup(@Nullable String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String key = ip.trim();
        GeoInfo cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        GeoInfo resolved = resolve(key);
        if (resolved != null) {
            cache.put(key, resolved);
        }
        return resolved;
    }

    private GeoInfo resolve(String ip) {
        if (!ready || cityReader == null || cityLookup == null) {
            return new GeoInfo("unknown city", "unknown country", "unknown ASN");
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            Object cityResponse = cityLookup.invoke(cityReader, address);
            Object asnResponse = (asnReader != null && asnLookup != null)
                ? asnLookup.invoke(asnReader, address) : null;
            String city = nestedName(cityResponse, "getCity");
            String country = nestedName(cityResponse, "getCountry");
            String asn = asnResponse == null ? "unknown ASN" : asnText(asnResponse);
            return new GeoInfo(city, country, asn);
        } catch (Exception e) {
            return new GeoInfo("unknown city", "unknown country", "unknown ASN");
        }
    }

    @Nullable
    private static String nestedName(Object root, String method) {
        try {
            Object part = root.getClass().getMethod(method).invoke(root);
            Object name = part.getClass().getMethod("getName").invoke(part);
            return name == null ? null : name.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String asnText(Object asnResponse) {
        try {
            Object number = asnResponse.getClass().getMethod("getAutonomousSystemNumber").invoke(asnResponse);
            Object org = asnResponse.getClass().getMethod("getAutonomousSystemOrganization").invoke(asnResponse);
            String n = number == null ? "AS?" : "AS" + number;
            String o = org == null ? "unknown org" : org.toString();
            return n + " " + o;
        } catch (Exception e) {
            return "unknown ASN";
        }
    }

    private Path ensureArtifact(Path libDir, Artifact artifact) throws IOException {
        Path jar = libDir.resolve(artifact.fileName);
        if (!Files.isRegularFile(jar) || !sha256(jar).equalsIgnoreCase(artifact.sha256)) {
            download(artifact.url, jar);
            String actual = sha256(jar);
            if (!artifact.sha256.equalsIgnoreCase(actual)) {
                throw new IOException("Checksum mismatch for " + artifact.fileName);
            }
        }
        return jar;
    }

    @Nullable
    private Path ensureMmdb(Path target, String url) {
        try {
            if (Files.isRegularFile(target) && Files.size(target) > 1_000_000) {
                return target;
            }
            download(url, target);
            if (Files.isRegularFile(target) && Files.size(target) > 1_000_000) {
                return target;
            }
        } catch (Exception e) {
            plugin.getLogger().atWarning().log("GeoIP download of %s failed: %s",
                target.getFileName(),
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return null;
    }

    /**
     * Downloads via java.net.http (NIO). Unlike HttpURLConnection, this ignores
     * the JVM-wide socksProxyHost that xray/VPN sets — that was causing Connect timed out.
     * Falls back to the Discord HTTP proxy from discord-bot.yml if DIRECT fails.
     */
    private void download(String url, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        IOException last = null;
        for (java.net.http.HttpClient client : downloadClients()) {
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("User-Agent", "hlAuth/1.1.0")
                    .GET()
                    .build();
                java.net.http.HttpResponse<Path> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofFile(tmp));
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String loc = response.headers().firstValue("location").orElse("");
                    if (!loc.isEmpty()) {
                        request = java.net.http.HttpRequest.newBuilder(URI.create(loc))
                            .timeout(java.time.Duration.ofSeconds(60))
                            .header("User-Agent", "hlAuth/1.1.0")
                            .GET()
                            .build();
                        response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(tmp));
                        status = response.statusCode();
                    }
                }
                if (status < 200 || status >= 300) {
                    Files.deleteIfExists(tmp);
                    last = new IOException("HTTP " + status + " for " + target.getFileName());
                    continue;
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Files.deleteIfExists(tmp);
                throw new IOException("Download interrupted", e);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                last = e;
            }
        }
        throw last == null ? new IOException("Download failed for " + target.getFileName()) : last;
    }

    private java.util.List<java.net.http.HttpClient> downloadClients() {
        java.util.List<java.net.http.HttpClient> clients = new java.util.ArrayList<>(2);
        java.net.http.HttpClient.Builder direct = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .proxy(DIRECT);
        clients.add(direct.build());
        java.net.InetSocketAddress proxyAddr = discordHttpProxy();
        if (proxyAddr != null) {
            clients.add(java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(8))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .proxy(java.net.ProxySelector.of(proxyAddr))
                .build());
        }
        return clients;
    }

    @Nullable
    private InetSocketAddress discordHttpProxy() {
        try {
            Path file = plugin.getDataDirectory().resolve("discord-bot.yml");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Map<String, Object> y = com.hlauth.hytale.config.YamlConfig.parse(file);
            if (!com.hlauth.hytale.config.YamlConfig.bool(y, "proxyEnabled", false)) {
                return null;
            }
            String type = com.hlauth.hytale.config.YamlConfig.str(y, "proxyType", "http");
            if (type != null && "socks".equalsIgnoreCase(type.trim())) {
                return null;
            }
            String host = com.hlauth.hytale.config.YamlConfig.str(y, "proxyHost", "");
            int port = com.hlauth.hytale.config.YamlConfig.integer(y, "proxyPort", 0);
            if (host == null || host.isBlank() || port <= 0) {
                return null;
            }
            return new InetSocketAddress(host.trim(), port);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    md.update(buf, 0, n);
                }
            }
            return HEX.formatHex(md.digest());
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}
