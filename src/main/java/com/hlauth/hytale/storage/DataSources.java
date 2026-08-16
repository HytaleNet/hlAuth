package com.hlauth.hytale.storage;

import com.hlauth.hytale.config.HlAuthConfig;
import com.hlauth.hytale.data.PlayerAuth;
import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;

/** Picks H2, SQLite or MySQL and imports {@code accounts.json} if the SQL table is empty. */
public final class DataSources {

    private DataSources() {
    }

    public static DataSource open(HlAuthConfig config, Path dataDirectory, HytaleLogger logger) {
        if (config.storageType != null && "json".equalsIgnoreCase(config.storageType.trim())) {
            logger.atInfo().log("storageType json is no longer supported; using H2. "
                + "Existing accounts.json will be imported if the database is empty.");
        }
        String type = SqlDataSource.storageType(config);
        try {
            JdbcDrivers.ensureLoaded(config, dataDirectory, logger);
            SqlDataSource sql = new SqlDataSource(config, dataDirectory, logger);
            importJsonIfEmpty(sql, dataDirectory, logger);
            return sql;
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Failed to open " + type + " storage. Check databaseHost / databaseName / "
                    + "databaseUsername / databasePassword, or set databaseJdbcUrl. " + e.getMessage(),
                e);
        }
    }

    private static void importJsonIfEmpty(SqlDataSource sql, Path dataDirectory, HytaleLogger logger) {
        if (sql.getAccountsCount() > 0) {
            return;
        }
        Path jsonFile = dataDirectory.resolve("accounts.json");
        if (!Files.exists(jsonFile)) {
            return;
        }
        JsonDataSource json = new JsonDataSource(dataDirectory, logger);
        Collection<PlayerAuth> accounts = json.getAllAuths();
        json.close();
        if (accounts.isEmpty()) {
            return;
        }
        int n = 0;
        for (PlayerAuth auth : accounts) {
            sql.saveAuth(auth);
            n++;
        }
        logger.atInfo().log("Imported %d account(s) from accounts.json into %s", n, sql.getName());
    }
}
