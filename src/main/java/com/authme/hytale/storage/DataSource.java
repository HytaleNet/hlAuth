package com.authme.hytale.storage;

import com.authme.hytale.data.PlayerAuth;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Storage backend for registered accounts. All methods must be thread safe:
 * they are invoked from world threads, command threads and the async executor.
 */
public interface DataSource {

    String getName();

    boolean isRegistered(String name);

    @Nullable
    PlayerAuth getAuth(String name);

    void saveAuth(PlayerAuth auth);

    void updateAuth(PlayerAuth auth);

    boolean removeAuth(String name);

    int getAccountsCount();

    int countByRegistrationIp(String ip);

    Collection<PlayerAuth> getAllAuths();

    void close();
}
