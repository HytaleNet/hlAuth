package com.authme.hytale.data;

/**
 * Stored account data of a registered player. Mirrors the essential columns
 * of the original AuthMe database schema.
 */
public final class PlayerAuth {

    /** Lowercase player name, primary key. */
    public String name;
    /** Name with original capitalization. */
    public String realName;
    /** Player UUID as string (may be null for imported accounts). */
    public String uuid;
    /** Password hash, format depends on the algorithm (e.g. {@code $PBKDF2$...} or {@code $SHA$...}). */
    public String password;
    /** IP the account was registered from. */
    public String registrationIp;
    /** Registration timestamp (epoch millis). */
    public long registrationDate;
    /** IP of the last successful login. */
    public String lastIp;
    /** Timestamp of the last successful login or quit while logged in (epoch millis). */
    public long lastLogin;
    /** True when the account was registered by a verified premium (licensed) player. */
    public boolean premium;

    public PlayerAuth() {
    }

    public PlayerAuth(String realName, String uuid, String password, String registrationIp) {
        this.name = realName.toLowerCase();
        this.realName = realName;
        this.uuid = uuid;
        this.password = password;
        this.registrationIp = registrationIp;
        this.registrationDate = System.currentTimeMillis();
    }
}
