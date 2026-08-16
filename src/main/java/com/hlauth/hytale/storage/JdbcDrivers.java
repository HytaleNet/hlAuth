package com.hlauth.hytale.storage;

import com.hlauth.hytale.config.HlAuthConfig;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Downloads the JDBC driver for the configured backend from Maven Central into
 * {@code lib/} on first start, then loads it. Drivers are not packed into the plugin jar.
 */
public final class JdbcDrivers {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final HexFormat HEX = HexFormat.of();

    private static volatile ClassLoader driverLoader;
    private static volatile String loadedDriverClass;

    private JdbcDrivers() {
    }

    public static void ensureLoaded(HlAuthConfig config, Path dataDirectory, HytaleLogger logger) {
        Artifact artifact = artifactFor(config);
        if (artifact.driverClass.equals(loadedDriverClass) && driverLoader != null) {
            return;
        }
        Path libDir = dataDirectory.resolve("lib");
        Path jar = libDir.resolve(artifact.fileName);
        try {
            Files.createDirectories(libDir);
            if (!Files.isRegularFile(jar) || !sha256(jar).equalsIgnoreCase(artifact.sha256)) {
                logger.atInfo().log("Downloading %s JDBC driver (%s)…", artifact.label, artifact.fileName);
                download(artifact.url, jar, artifact.sha256);
                logger.atInfo().log("Saved JDBC driver to %s", jar.getFileName());
            }
            register(jar, artifact.driverClass);
            loadedDriverClass = artifact.driverClass;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Could not load the " + artifact.label + " JDBC driver. "
                    + "The server needs HTTPS access to repo1.maven.org on first start "
                    + "(driver is cached in lib/ afterwards). " + e.getMessage(),
                e);
        }
    }

    private static Artifact artifactFor(HlAuthConfig config) {
        String url = config.databaseJdbcUrl == null ? "" : config.databaseJdbcUrl.toLowerCase(Locale.ROOT);
        if (url.contains("jdbc:sqlite:")) {
            return Artifact.SQLITE;
        }
        if (url.contains("jdbc:mysql:") || url.contains("jdbc:mariadb:")) {
            return Artifact.MYSQL;
        }
        if (url.contains("jdbc:h2:")) {
            return Artifact.H2;
        }
        return switch (SqlDataSource.storageType(config)) {
            case "sqlite" -> Artifact.SQLITE;
            case "mysql" -> Artifact.MYSQL;
            default -> Artifact.H2;
        };
    }

    private static void download(String url, Path dest, String expectedSha) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        HttpURLConnection http = (HttpURLConnection) URI.create(url).toURL()
            .openConnection(java.net.Proxy.NO_PROXY);
        http.setInstanceFollowRedirects(true);
        http.setConnectTimeout(CONNECT_TIMEOUT_MS);
        http.setReadTimeout(READ_TIMEOUT_MS);
        http.setRequestProperty("User-Agent", "hlAuth");
        int status = http.getResponseCode();
        if (status < 200 || status >= 300) {
            http.disconnect();
            throw new IOException("HTTP " + status + " downloading " + dest.getFileName());
        }
        try (InputStream in = http.getInputStream();
             OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        } finally {
            http.disconnect();
        }
        String actual = sha256(tmp);
        if (!expectedSha.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tmp);
            throw new IOException("JDBC driver checksum mismatch (expected " + expectedSha + ", got " + actual + ")");
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
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
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private static synchronized void register(Path jar, String driverClass) throws Exception {
        java.net.URL[] urls = {jar.toUri().toURL()};
        URLLoader loader = new URLLoader(urls, JdbcDrivers.class.getClassLoader());
        driverLoader = loader;
        Class<?> clazz = Class.forName(driverClass, true, loader);
        Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(new DriverShim(driver));
    }

    private enum Artifact {
        H2("H2",
            "h2-2.3.232.jar",
            "org.h2.Driver",
            "https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar",
            "8dae62d22db8982c3dcb3826edb9c727c5d302063a67eef7d63d82de401f07d3"),
        SQLITE("SQLite",
            "sqlite-jdbc-3.49.1.0.jar",
            "org.sqlite.JDBC",
            "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar",
            "5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291"),
        MYSQL("MySQL",
            "mysql-connector-j-8.4.0.jar",
            "com.mysql.cj.jdbc.Driver",
            "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar",
            "d77962877d010777cff997015da90ee689f0f4bb76848340e1488f2b83332af5");

        final String label;
        final String fileName;
        final String driverClass;
        final String url;
        final String sha256;

        Artifact(String label, String fileName, String driverClass, String url, String sha256) {
            this.label = label;
            this.fileName = fileName;
            this.driverClass = driverClass;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    /** Keeps the URLClassLoader reachable so DriverManager can still load driver classes. */
    private static final class URLLoader extends java.net.URLClassLoader {
        URLLoader(java.net.URL[] urls, ClassLoader parent) {
            super("hlAuth-jdbc", urls, parent);
        }
    }

    /**
     * DriverManager only accepts drivers loaded by the system/app classloader.
     * This shim is ours; it delegates to the real driver from the downloaded jar.
     */
    private static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
