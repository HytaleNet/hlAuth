# hlAuth (AuthMe for Hytale)

Authentication plugin for **Hytale Server** with native UI menus. Inspired by [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded).

**Authors:** Chernyash, HytaleNet HLauncher · [github.com/HytaleNet/hlAuth](https://github.com/HytaleNet/hlAuth)

> Русская версия: [README-ru.md](README-ru.md)

## Features

- **Native UI menus** for login and registration (Hytale Custom UI, vanilla server page style). The menu cannot be closed until the player authenticates.
- **Registration and login** — via the menu or chat commands (the menu also closes when using a command).
- **Secure password storage** — PBKDF2-HMAC-SHA256 (100,000 iterations, salted). Supports checking the legacy AuthMe `$SHA$` format — you can migrate a Minecraft server database without resetting passwords.
- **Sessions** — reconnecting from the same IP within a configurable time does not require logging in again.
- **Limbo protection** — until login, the player cannot use chat; after `timeoutSeconds` they are kicked.
- **Premium check** (optional) — UUID verification via [playerdb.co](https://playerdb.co): premium accounts auto-login; with `premiumAutoRegister`, a new premium account is registered without a password; offline players cannot take a premium name and vice versa.
- **Localization** — texts in `messages/ru.yml` and `messages/en.yml`; language is selected in the config.
- **Two-factor authentication (optional)** — TOTP for Google Authenticator / Aegis / 2FAS: QR + secret, then one-time recovery codes to screenshot. Toggle with `twoFactorEnabled`; make it mandatory with `twoFactorRequired`.
- **LuckPerms permissions** — nodes `hlauth.player.*` / `hlauth.admin.*`.
- **Storage** — `json` (`accounts.json`), embedded **H2** file, or **MySQL** (local or remote JDBC). Switching from JSON to H2/MySQL imports existing accounts automatically.

## Commands

| Command | Description |
|---|---|
| `/login <password>` (`/l`) | Log in |
| `/register <password> <password>` (`/reg`) | Register |
| `/logout` | Log out (session is cleared) |
| `/changepassword <old> <new>` (`/cp`) | Change password |
| `/unregister <password>` | Delete your own account (kicked from the server) |
| `/2fa` (`/totp`) | Enable 2FA (QR / secret) after login |
| `/2fa <code>` | Confirm setup or enter the authenticator code at login |
| `/2fa recover <code>` | Log in with a one-time recovery code |
| `/2fa disable <code>` | Disable 2FA (authenticator or recovery code; not if 2FA is required) |
| `/2fa done` | Continue after saving recovery codes |
| `/hlauth register <name> <password>` (`/authme …`) | Admin: register a player |
| `/hlauth unregister <name>` | Admin: delete an account (kicks online player) |
| `/hlauth changepassword <name> <password>` | Admin: change password |
| `/hlauth info <name>` | Admin: account info |
| `/hlauth reload` | Admin: reload config and messages |
| `/hlauth backup` | Admin: write a JSON snapshot of all accounts |
| `/hlauth 2fareset <name>` | Admin: remove 2FA from an account |

### Permissions (LuckPerms)

| Permission | Description |
|---|---|
| `hlauth.player.login` | `/login` |
| `hlauth.player.register` | `/register` |
| `hlauth.player.logout` | `/logout` |
| `hlauth.player.changepassword` | `/changepassword` |
| `hlauth.player.unregister` | `/unregister` |
| `hlauth.player.2fa` | `/2fa` |
| `hlauth.admin` | Root `/hlauth` (alias `/authme`) |
| `hlauth.admin.register` | `/hlauth register` |
| `hlauth.admin.unregister` | `/hlauth unregister` |
| `hlauth.admin.changepassword` | `/hlauth changepassword` |
| `hlauth.admin.info` | `/hlauth info` |
| `hlauth.admin.reload` | `/hlauth reload` |
| `hlauth.admin.backup` | `/hlauth backup` |
| `hlauth.admin.2fareset` | `/hlauth 2fareset` |

Player commands are granted to the `hytale:Adventurer` group by default. Grant admin permissions via LuckPerms (`hlauth.admin` or `hlauth.admin.*`).

The `config.json` format stays compatible (new keys are added on reload). `accounts.json` is still used when `storageType` is `json`. H2/MySQL also store TOTP fields.

## Building

Requires JDK 21+ (tested on JDK 25) and a local `HytaleServer.jar`.

**PowerShell (no Gradle):**

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
# or with an explicit path to the server jar:
powershell -ExecutionPolicy Bypass -File build.ps1 -ServerJar "D:\path\to\HytaleServer.jar"
```

**Gradle:**

```bash
gradle build -PhytaleServerJar="D:/path/to/HytaleServer.jar"
```

Built jar: `build/libs/hlAuth-1.0.1.jar`.

## Installation

Copy `hlAuth-X.X.X.jar` into your Hytale server’s `mods/` folder and restart the server.

## Configuration

After the first launch, `mods/HytaleNet_hlAuth/config.json` is created:

| Option | Default | Description |
|---|---|---|
| `language` | `"ru"` | Language file in `messages/` without `.yml` (`ru`, `en`, …) |
| `registrationEnabled` | `true` | Require new players to register |
| `timeoutSeconds` | `120` | Kick if the player does not authenticate in time |
| `passwordMinLength` / `passwordMaxLength` | `5` / `64` | Password length limits |
| `unsafePasswords` | `[...]` | Forbidden passwords |
| `maxRegistrationsPerIp` | `2` | Max accounts per IP (0 = unlimited) |
| `maxLoginTries` | `5` | Kick after N wrong passwords |
| `kickOnWrongPassword` | `false` | Kick immediately on wrong password |
| `sessionsEnabled` | `true` | Auto re-login via session |
| `sessionTimeoutMinutes` | `10` | Session lifetime |
| `useUiMenus` | `true` | UI menus instead of chat commands only |
| `uiOpenDelayMs` | `1000` | Menu open delay (ms); reduces UI texture glitches |
| `premiumCheckEnabled` | `false` | UUID check via playerdb.co |
| `premiumCheckTimeoutSeconds` | `5` | Premium request timeout |
| `premiumKickEnabled` | `false` | Kick on premium/offline mismatch (otherwise Access Denied UI) |
| `premiumAutoRegister` | `false` | Auto-login and auto-register verified premium without a password (requires `premiumCheckEnabled`) |
| `protectChat` | `true` | Block chat until login |
| `messageIntervalSeconds` | `15` | Chat reminder interval |
| `twoFactorEnabled` | `false` | TOTP 2FA (Authenticator / Aegis / 2FAS) |
| `twoFactorRequired` | `false` | Force every player to bind 2FA after the password (needs `twoFactorEnabled`) |
| `twoFactorRequiredOnSession` | `false` | Also ask for 2FA on session auto-login |
| `twoFactorIssuer` | `"hlAuth"` | Name shown in the authenticator app |
| `storageType` | `"json"` | `json` / `h2` / `mysql` |
| `databaseHost` | `"127.0.0.1"` | MySQL host (unused for embedded H2) |
| `databasePort` | `3306` | MySQL port |
| `databaseName` | `"hlauth"` | MySQL database (created if the user may `CREATE DATABASE`) |
| `databaseUsername` / `databasePassword` | `hlauth` / empty | DB login |
| `databaseTable` | `"hlauth_accounts"` | Accounts table |
| `databaseUseSsl` | `false` | MySQL SSL |
| `databasePoolSize` | `4` | JDBC pool size |
| `databaseJdbcUrl` | `""` | Optional full JDBC URL (overrides host/port/name). Example: `jdbc:mysql://db.example.com:3306/hlauth` or `jdbc:h2:tcp://127.0.0.1:9092/hlauth` |
| `backupEnabled` | `false` | Automatic account backups into `backups/` |
| `backupIntervalHours` | `6` | Auto-backup interval (hours), added to minutes |
| `backupIntervalMinutes` | `0` | Extra minutes (e.g. `0` + `30` = every 30 minutes) |
| `backupKeepCount` | `24` | How many backup files to keep (`0` = keep all) |

Accounts (JSON mode): `mods/HytaleNet_hlAuth/accounts.json`.  
H2 file: `mods/HytaleNet_hlAuth/hlauth.mv.db`.  
Messages: `mods/HytaleNet_hlAuth/messages/en.yml` (you can add `ru.yml`, `de.yml`, etc. and set `language` in the config).

To move from JSON to H2: set `storageType` to `"h2"` and restart — accounts are imported if the table is empty. For MySQL, create a user, set host/name/password (or `databaseJdbcUrl`) and restart.

Backups are `mods/HytaleNet_hlAuth/backups/hlauth-YYYY-MM-DD_HH-mm-ss.json` (same format as `accounts.json`). Restore: copy the file over `accounts.json` (JSON mode) or empty the SQL table / delete the H2 file so the next start imports `accounts.json`.

## Migrating a database from Minecraft (AuthMeReloaded)

Hashes in the `$SHA$salt$hash` format (SHA-256 — AuthMe’s default algorithm) are verified directly. Convert your `authme` table into an `accounts.json` like this:

```json
[
  {
    "name": "player",
    "realName": "Player",
    "password": "$SHA$1234abcd$....",
    "registrationDate": 0,
    "lastLogin": 0
  }
]
```
