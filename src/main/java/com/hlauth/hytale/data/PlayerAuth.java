package com.hlauth.hytale.data;

/**
 * Stored account data of a registered player.
 */
public final class PlayerAuth {

    /** Lowercase player name, primary key. */
    public String name;
    /** Name with original capitalization. */
    public String realName;
    /** Player UUID as string (may be null for imported accounts). */
    public String uuid;
    /** Password hash ({@code $2a$...} bcrypt; leftover {@code $PBKDF2$...} is upgraded on login). */
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
    /** True when TOTP 2FA is bound to this account. */
    public boolean totpEnabled;
    /** Base32 TOTP secret (stored so authenticator apps can keep working). */
    public String totpSecret;
    /** One-time recovery code hashes ({@code $REC$salt$hash}). */
    public String[] totpRecoveryHashes;
    /** Telegram user id as decimal string, or empty/null if not linked. */
    public String telegramId;
    /** Discord user snowflake, or empty/null if not linked. */
    public String discordId;
    /**
     * Login mode for linked messengers:
     * false = password + messenger confirmation, true = messenger confirmation only.
     */
    public boolean messengerConfirmOnly;
    /** Messenger 2FA challenge before login (when account is linked). */
    public boolean messengerTwoFactorEnabled = true;
    /** Send bot notifications about account events (e.g. successful login). */
    public boolean messengerNotificationsEnabled = true;
    /** Allow quick session login from same IP. */
    public boolean messengerSessionsEnabled = true;
    /** Manual account lock (player cannot log in until unlocked). */
    public boolean blocked;

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
