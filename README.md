# hlAuth

Authentication plugin for **Hytale Server** with native UI menus.

**Author:** Chernyash · [hlauncher.com](https://hlauncher.com)

> Русская версия: [README-ru.md](README-ru.md)

## Features

- **Native UI menus** for login and registration (Hytale Custom UI, vanilla server page style). The menu cannot be closed until the player authenticates.
- **Registration and login** — via the menu or chat commands (the menu also closes when using a command).
- **Telegram / Discord** — link an account, confirm logins in the bot, optional confirm-only mode, slash commands and embeds on Discord.
- **Secure password storage** — bcrypt with a startup cost benchmark (highest cost that stays under `bcryptTargetMs`). Leftover PBKDF2 hashes from older hlAuth builds are upgraded to bcrypt on the next successful login.
- **Sessions** — reconnecting from the same IP within a configurable time does not require logging in again.
- **Limbo protection** — until login, the player cannot use chat; after `timeoutSeconds` they are kicked.
- **Premium check** (optional) — UUID verification via [playerdb.co](https://playerdb.co): premium accounts auto-login; with `premiumAutoRegister`, a new premium account is registered without a password; offline players cannot take a premium name and vice versa.
- **Localization** — texts in `messages/ru.yml` and `messages/en.yml`; language is selected in the config.
- **Two-factor authentication (optional)** — TOTP for Google Authenticator / Aegis / 2FAS: QR + secret, then one-time recovery codes to screenshot. Toggle with `twoFactorEnabled`; make it mandatory with `twoFactorRequired`.
- **LuckPerms permissions** — nodes `hlauth.player.*` / `hlauth.admin.*`.
- **Storage** — embedded **H2** (default), **SQLite**, or **MySQL**. JDBC drivers are downloaded into `lib/` on first start (not packed into the plugin jar). If `accounts.json` from an older version is present and the database is empty, accounts are imported automatically.

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
| `/link telegram` / `/link discord` | Link Telegram or Discord (then send the code to the bot) |
| `/link confirm <code>` | Confirm linking |
| `/unlink telegram` / `/unlink discord` | Unlink a messenger |
| `/hlauth register <name> <password>` | Admin: register a player |
| `/hlauth unregister <name>` | Admin: delete an account (kicks online player) |
| `/hlauth changepassword <name> <password>` | Admin: change password |
| `/hlauth info <name>` | Admin: account info |
| `/hlauth reload` | Admin: reload config and messages |
| `/hlauth backup` | Admin: write a JSON snapshot of all accounts |
| `/hlauth 2fareset <name>` | Admin: remove 2FA from an account |
| `/hlauth ipcheck <name>` | Admin: masked IPs and accounts sharing them (UI) |
| `/hlauth unlink <name> telegram\|discord` | Admin: unlink Telegram or Discord |

### Permissions (LuckPerms)

| Permission | Description |
|---|---|
| `hlauth.player.login` | `/login` |
| `hlauth.player.register` | `/register` |
| `hlauth.player.logout` | `/logout` |
| `hlauth.player.changepassword` | `/changepassword` |
| `hlauth.player.unregister` | `/unregister` |
| `hlauth.player.2fa` | `/2fa` |
| `hlauth.player.link` | `/link` |
| `hlauth.player.unlink` | `/unlink` |
| `hlauth.admin` | Root `/hlauth` |
| `hlauth.admin.register` | `/hlauth register` |
| `hlauth.admin.unregister` | `/hlauth unregister` |
| `hlauth.admin.changepassword` | `/hlauth changepassword` |
| `hlauth.admin.info` | `/hlauth info` |
| `hlauth.admin.reload` | `/hlauth reload` |
| `hlauth.admin.backup` | `/hlauth backup` |
| `hlauth.admin.2fareset` | `/hlauth 2fareset` |
| `hlauth.admin.ipcheck` | `/hlauth ipcheck` |
| `hlauth.admin.unlink` | `/hlauth unlink` |

Player commands are granted to the `hytale:Adventurer` group by default. Grant admin permissions via LuckPerms (`hlauth.admin` or `hlauth.admin.*`).

The `config.json` format stays compatible (new keys are added on reload). `storageType: json` is no longer used and is rewritten to `h2`; leftover `accounts.json` is imported into an empty database.

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

Built jar: `build/libs/hlAuth-1.1.0.jar`.

## Installation

Copy `hlAuth-X.X.X.jar` into your Hytale server’s `mods/` folder and restart the server.

On first start the plugin downloads the JDBC driver for the chosen `storageType` from Maven Central into `mods/HytaleNet_hlAuth/lib/` (H2 ≈ 2.5 MB, MySQL ≈ 2.5 MB, SQLite ≈ 14 MB). Later starts reuse that file. If the server has no outbound HTTPS, copy the matching jar there yourself (`h2-2.3.232.jar`, `sqlite-jdbc-3.49.1.0.jar`, or `mysql-connector-j-8.4.0.jar`).

## Configuration

After the first launch, `mods/HytaleNet_hlAuth/config.yml` is created. Telegram and Discord bots have their own files: `telegram-bot.yml` and `discord-bot.yml`.

| Option | Default | Description |
|---|---|---|
| `language` | `"en"` | Language file in `messages/` without `.yml` (`en`, `ru`, …) |
| `registrationEnabled` | `true` | Require new players to register |
| `timeoutSeconds` | `120` | Kick if the player does not authenticate in time |
| `passwordMinLength` / `passwordMaxLength` | `5` / `64` | Password length limits |
| `maxRegistrationsPerIp` | `2` | Max accounts per IP (0 = unlimited) |
| `maxLoginTries` | `5` | Kick after N wrong passwords |
| `kickOnWrongPassword` | `false` | Kick immediately on wrong password |
| `bcryptCost` | `0` | bcrypt log-rounds for new hashes. `0` = benchmark on start and store the chosen cost (10–14) |
| `bcryptTargetMs` | `250` | Target hash time in ms for the bcrypt cost benchmark |
| `registerCommands` / `loginCommands` | `[]` | Commands after register/login. Prefixes: `[CONSOLE]`, `[PLAYER]`, `[WAIT]` (ms). `{nick}` = player name. Example is in `config.json` as comments |
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
| `telegramEnabled` | `false` | Telegram bot for linking + login confirmation |
| `telegramBotToken` | `""` | Token from @BotFather |
| `telegramBotUsername` | `""` | Bot username without `@` (optional) |
| `discordEnabled` | `false` | Discord bot for linking + login confirmation |
| `discordBotToken` | `""` | Bot token (enable Message Content + Direct Messages intents) |
| `discordInviteUrl` | `""` | Invite so players can share a server with the bot (needed for DMs) |
| `messengerLoginMode` | `"password_and_confirm"` | Default mode for new links: `password_and_confirm` = password + bot confirm, `confirm_only` = only bot confirmation (players can switch later via bot command `/mode ...`) |
| `storageType` | `"h2"` | `h2` / `sqlite` / `mysql` |
| `databaseHost` | `"127.0.0.1"` | MySQL host (unused for embedded H2) |
| `databasePort` | `3306` | MySQL port |
| `databaseName` | `"hlauth"` | MySQL database (created if the user may `CREATE DATABASE`) |
| `databaseUsername` / `databasePassword` | `hlauth` / empty | DB login |
| `databaseTable` | `"hlauth_accounts"` | Accounts table |
| `databaseUseSsl` | `false` | MySQL SSL |
| `databasePoolSize` | `4` | JDBC pool size |
| `databaseJdbcUrl` | `""` | Optional full JDBC URL (overrides host/port/name). Example: `jdbc:mysql://db.example.com:3306/hlauth`, `jdbc:h2:tcp://127.0.0.1:9092/hlauth`, `jdbc:sqlite:/path/hlauth.db` |
| `backupEnabled` | `false` | Automatic account backups into `backups/` |
| `backupIntervalHours` | `6` | Auto-backup interval (hours), added to minutes |
| `backupIntervalMinutes` | `0` | Extra minutes (e.g. `0` + `30` = every 30 minutes) |
| `backupKeepCount` | `24` | How many backup files to keep (`0` = keep all) |

H2 file: `mods/HytaleNet_hlAuth/hlauth.mv.db`.  
SQLite file: `mods/HytaleNet_hlAuth/hlauth.db`.  
Forbidden passwords: `mods/HytaleNet_hlAuth/unsafePasswords.txt` (one per line).  
Messages: `mods/HytaleNet_hlAuth/messages/en.yml` (you can add `ru.yml`, `de.yml`, etc. and set `language` in the config).

If you still have `accounts.json` from 1.0.0/1.0.1, leave it in the data folder and start with empty H2/SQLite — accounts are imported once. For MySQL, create a user, set host/name/password (or `databaseJdbcUrl`) and restart.

Backups are `mods/HytaleNet_hlAuth/backups/hlauth-YYYY-MM-DD_HH-mm-ss.json`. Restore: put the file as `accounts.json` in the plugin data folder, empty/delete the SQL database, and restart so it imports.


[![hlAuth HStats](https://api.hstats.dev/api/embed/eb9de4a9-3201-4e10-a67e-df58c1e2e3d0/card.svg?theme=dark&layout=history&size=sm&dark=false&font=system&background=transparent)](https://hstats.dev/mods/eb9de4a9-3201-4e10-a67e-df58c1e2e3d0)
