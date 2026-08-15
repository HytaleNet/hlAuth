package com.authme.hytale.storage;

import com.authme.hytale.config.AuthMeConfig;
import com.authme.hytale.data.PlayerAuth;
import com.google.gson.Gson;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * JDBC storage for H2 (embedded file or TCP) and MySQL (local or remote).
 *
 * <p>Reads/writes go to the database immediately. A small connection pool is used
 * so login does not block on a single shared connection.</p>
 */
public final class SqlDataSource implements DataSource {

    private static final Gson GSON = new Gson();
    private static final int POOL_WAIT_MS = 5_000;

    private final HytaleLogger logger;
    private final String displayName;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String table;
    private final BlockingQueue<Connection> pool;
    private final int poolSize;
    private volatile boolean closed;

    static {
        loadDriver("org.h2.Driver");
        loadDriver("com.mysql.cj.jdbc.Driver");
    }

    private static void loadDriver(String className) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            // Driver may be absent if that backend is unused
        }
    }

    public SqlDataSource(AuthMeConfig config, Path dataDirectory, HytaleLogger logger) throws SQLException {
        this.logger = logger;
        this.table = sanitizeIdent(config.databaseTable, "hlauth_accounts");
        this.username = nullToEmpty(config.databaseUsername);
        this.password = nullToEmpty(config.databasePassword);
        String type = storageType(config);
        this.jdbcUrl = resolveJdbcUrl(config, dataDirectory, type);
        this.displayName = describe(type, this.jdbcUrl);
        this.poolSize = Math.max(1, Math.min(16, config.databasePoolSize <= 0 ? 4 : config.databasePoolSize));
        this.pool = new ArrayBlockingQueue<>(poolSize);

        if ("mysql".equals(type) && isBlank(config.databaseJdbcUrl)) {
            ensureMysqlDatabase(config);
        }

        for (int i = 0; i < poolSize; i++) {
            pool.add(newConnection());
        }
        createTable();
        logger.atInfo().log("SQL storage ready: %s (pool=%d, table=%s)", displayName, poolSize, table);
    }

    static String storageType(AuthMeConfig config) {
        String raw = config.storageType == null ? "json" : config.storageType.trim().toLowerCase(Locale.ROOT);
        if ("h2".equals(raw) || "mysql".equals(raw) || "json".equals(raw)) {
            return raw;
        }
        return "json";
    }

    private static String resolveJdbcUrl(AuthMeConfig config, Path dataDirectory, String type) {
        if (!isBlank(config.databaseJdbcUrl)) {
            return config.databaseJdbcUrl.trim();
        }
        if ("h2".equals(type)) {
            String file = dataDirectory.toAbsolutePath().normalize().resolve("hlauth")
                .toString().replace('\\', '/');
            return "jdbc:h2:file:" + file + ";MODE=MySQL;DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE";
        }
        String host = isBlank(config.databaseHost) ? "127.0.0.1" : config.databaseHost.trim();
        int port = config.databasePort <= 0 ? 3306 : config.databasePort;
        String db = sanitizeIdent(config.databaseName, "hlauth");
        String ssl = config.databaseUseSsl ? "true" : "false";
        return "jdbc:mysql://" + host + ":" + port + "/" + db
            + "?useUnicode=true&characterEncoding=utf8&useSSL=" + ssl
            + "&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    private void ensureMysqlDatabase(AuthMeConfig config) throws SQLException {
        String host = isBlank(config.databaseHost) ? "127.0.0.1" : config.databaseHost.trim();
        int port = config.databasePort <= 0 ? 3306 : config.databasePort;
        String db = sanitizeIdent(config.databaseName, "hlauth");
        String ssl = config.databaseUseSsl ? "true" : "false";
        String adminUrl = "jdbc:mysql://" + host + ":" + port
            + "/?useSSL=" + ssl + "&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection c = DriverManager.getConnection(adminUrl, username, password);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + db
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            logger.atWarning().withCause(e).log(
                "Could not CREATE DATABASE `%s` (create it manually if the plugin cannot connect)", db);
        }
    }

    private Connection newConnection() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl, username, password);
        c.setAutoCommit(true);
        return c;
    }

    private Connection take() throws SQLException {
        if (closed) {
            throw new SQLException("SQL storage is closed");
        }
        try {
            Connection c = pool.poll(POOL_WAIT_MS, TimeUnit.MILLISECONDS);
            if (c == null) {
                throw new SQLException("Timed out waiting for a database connection");
            }
            if (c.isClosed() || !c.isValid(2)) {
                silentClose(c);
                return newConnection();
            }
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted waiting for a database connection", e);
        }
    }

    private void give(Connection c) {
        if (c == null) {
            return;
        }
        if (closed) {
            silentClose(c);
            return;
        }
        if (!pool.offer(c)) {
            silentClose(c);
        }
    }

    private void createTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS `" + table + "` ("
            + "name VARCHAR(64) NOT NULL PRIMARY KEY,"
            + "real_name VARCHAR(64) NOT NULL,"
            + "uuid VARCHAR(36) NULL,"
            + "password VARCHAR(255) NOT NULL,"
            + "registration_ip VARCHAR(45) NULL,"
            + "registration_date BIGINT NOT NULL,"
            + "last_ip VARCHAR(45) NULL,"
            + "last_login BIGINT NOT NULL DEFAULT 0,"
            + "premium TINYINT NOT NULL DEFAULT 0,"
            + "totp_enabled TINYINT NOT NULL DEFAULT 0,"
            + "totp_secret VARCHAR(64) NULL,"
            + "totp_recovery TEXT NULL"
            + ")";
        Connection c = take();
        try (Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        } finally {
            give(c);
        }
    }

    @Override
    public String getName() {
        return displayName;
    }

    @Override
    public boolean isRegistered(String name) {
        return getAuth(name) != null;
    }

    @Override
    @Nullable
    public PlayerAuth getAuth(String name) {
        if (name == null) {
            return null;
        }
        String sql = "SELECT * FROM `" + table + "` WHERE name = ?";
        try {
            Connection c = take();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return fromRow(rs);
                    }
                }
            } finally {
                give(c);
            }
        } catch (SQLException e) {
            logger.atSevere().withCause(e).log("Failed to read account %s", name);
        }
        return null;
    }

    @Override
    public void saveAuth(PlayerAuth auth) {
        upsert(auth);
    }

    @Override
    public void updateAuth(PlayerAuth auth) {
        upsert(auth);
    }

    private void upsert(PlayerAuth auth) {
        if (auth == null || auth.name == null) {
            return;
        }
        auth.name = auth.name.toLowerCase(Locale.ROOT);
        String update = "UPDATE `" + table + "` SET real_name=?, uuid=?, password=?, registration_ip=?, "
            + "registration_date=?, last_ip=?, last_login=?, premium=?, totp_enabled=?, totp_secret=?, "
            + "totp_recovery=? WHERE name=?";
        String insert = "INSERT INTO `" + table + "` (name, real_name, uuid, password, registration_ip, "
            + "registration_date, last_ip, last_login, premium, totp_enabled, totp_secret, totp_recovery) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try {
            Connection c = take();
            try {
                int updated;
                try (PreparedStatement ps = c.prepareStatement(update)) {
                    bindBody(ps, auth, 1);
                    ps.setString(12, auth.name);
                    updated = ps.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement ps = c.prepareStatement(insert)) {
                        ps.setString(1, auth.name);
                        bindBody(ps, auth, 2);
                        ps.executeUpdate();
                    }
                }
            } finally {
                give(c);
            }
        } catch (SQLException e) {
            logger.atSevere().withCause(e).log("Failed to save account %s", auth.name);
        }
    }

    @Override
    public boolean removeAuth(String name) {
        String sql = "DELETE FROM `" + table + "` WHERE name = ?";
        try {
            Connection c = take();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name.toLowerCase(Locale.ROOT));
                return ps.executeUpdate() > 0;
            } finally {
                give(c);
            }
        } catch (SQLException e) {
            logger.atSevere().withCause(e).log("Failed to delete account %s", name);
            return false;
        }
    }

    @Override
    public int getAccountsCount() {
        String sql = "SELECT COUNT(*) FROM `" + table + "`";
        try {
            Connection c = take();
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            } finally {
                give(c);
            }
        } catch (SQLException e) {
            logger.atSevere().withCause(e).log("Failed to count accounts");
            return 0;
        }
    }

    @Override
    public int countByRegistrationIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM `" + table + "` WHERE registration_ip = ?";
        try {
            Connection c = take();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } finally {
                give(c);
            }
        } catch (SQLException e) {
            logger.atSevere().withCause(e).log("Failed to count accounts by IP");
            return 0;
        }
    }

    @Override
    public Collection<PlayerAuth> getAllAuths() {
        List<PlayerAuth> list = new ArrayList<>();
        String sql = "SELECT * FROM `" + table + "`";
        try {
            Connection c = take();
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(fromRow(rs));
                }
            } finally {
                give(c);
            }
        } catch (SQLException e) {
            logger.atSevere().withCause(e).log("Failed to list accounts");
        }
        return list;
    }

    @Override
    public void close() {
        closed = true;
        Connection c;
        while ((c = pool.poll()) != null) {
            silentClose(c);
        }
    }

    private static void bindBody(PreparedStatement ps, PlayerAuth auth, int start) throws SQLException {
        int i = start;
        ps.setString(i++, orEmpty(auth.realName, auth.name));
        ps.setString(i++, auth.uuid);
        ps.setString(i++, orEmpty(auth.password, ""));
        ps.setString(i++, auth.registrationIp);
        ps.setLong(i++, auth.registrationDate);
        ps.setString(i++, auth.lastIp);
        ps.setLong(i++, auth.lastLogin);
        ps.setInt(i++, auth.premium ? 1 : 0);
        ps.setInt(i++, auth.totpEnabled ? 1 : 0);
        ps.setString(i++, auth.totpSecret);
        ps.setString(i, auth.totpRecoveryHashes == null ? null : GSON.toJson(auth.totpRecoveryHashes));
    }

    private static PlayerAuth fromRow(ResultSet rs) throws SQLException {
        PlayerAuth auth = new PlayerAuth();
        auth.name = rs.getString("name");
        auth.realName = rs.getString("real_name");
        auth.uuid = rs.getString("uuid");
        auth.password = rs.getString("password");
        auth.registrationIp = rs.getString("registration_ip");
        auth.registrationDate = rs.getLong("registration_date");
        auth.lastIp = rs.getString("last_ip");
        auth.lastLogin = rs.getLong("last_login");
        auth.premium = rs.getInt("premium") != 0;
        auth.totpEnabled = rs.getInt("totp_enabled") != 0;
        auth.totpSecret = rs.getString("totp_secret");
        String recovery = rs.getString("totp_recovery");
        if (recovery != null && !recovery.isBlank()) {
            auth.totpRecoveryHashes = GSON.fromJson(recovery, String[].class);
        }
        return auth;
    }

    private static String sanitizeIdent(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return fallback;
        }
        return trimmed;
    }

    private static String describe(String type, String url) {
        if ("h2".equals(type)) {
            return url.contains(":tcp:") ? "H2 (remote)" : "H2 (file)";
        }
        if ("mysql".equals(type)) {
            return "MySQL";
        }
        return "JDBC";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String orEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static void silentClose(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // closing
        }
    }
}
