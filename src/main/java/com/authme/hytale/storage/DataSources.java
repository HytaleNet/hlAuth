package com.authme.hytale.storage;

import com.authme.hytale.config.AuthMeConfig;
import com.authme.hytale.data.PlayerAuth;
import com.hypixel.hytale.logger.HytaleLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;

/** Picks JSON, H2 or MySQL and optionally imports {@code accounts.json} into SQL. */
public final class DataSources {

    private DataSources() {
    }

    public static DataSource open(AuthMeConfig config, Path dataDirectory, HytaleLogger logger) {
        String type = SqlDataSource.storageType(config);
        if ("json".equals(type)) {
            return new JsonDataSource(dataDirectory, logger);
        }
        try {
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
