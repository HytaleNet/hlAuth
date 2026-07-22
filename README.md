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
- **LuckPerms permissions** — nodes `hlauth.player.*` / `hlauth.admin.*`.
- **Storage** — JSON flat-file (`accounts.json`), atomic asynchronous writes.

## Commands

| Command | Description |
|---|---|
| `/login <password>` (`/l`) | Log in |
| `/register <password> <password>` (`/reg`) | Register |
| `/logout` | Log out (session is cleared) |
| `/changepassword <old> <new>` (`/cp`) | Change password |
| `/unregister <password>` | Delete your own account (kicked from the server) |
| `/hlauth register <name> <password>` (`/authme …`) | Admin: register a player |
| `/hlauth unregister <name>` | Admin: delete an account (kicks online player) |
| `/hlauth changepassword <name> <password>` | Admin: change password |
| `/hlauth info <name>` | Admin: account info |
| `/hlauth reload` | Admin: reload config and messages |

### Permissions (LuckPerms)

| Permission | Description |
|---|---|
| `hlauth.player.login` | `/login` |
| `hlauth.player.register` | `/register` |
| `hlauth.player.logout` | `/logout` |
| `hlauth.player.changepassword` | `/changepassword` |
| `hlauth.player.unregister` | `/unregister` |
| `hlauth.admin` | Root `/hlauth` (alias `/authme`) |
| `hlauth.admin.register` | `/hlauth register` |
| `hlauth.admin.unregister` | `/hlauth unregister` |
| `hlauth.admin.changepassword` | `/hlauth changepassword` |
| `hlauth.admin.info` | `/hlauth info` |
| `hlauth.admin.reload` | `/hlauth reload` |

Player commands are granted to the `hytale:Adventurer` group by default. Grant admin permissions via LuckPerms (`hlauth.admin` or `hlauth.admin.*`).

The `config.json` and `accounts.json` formats are unchanged. When updating from the `AuthMe_AuthMeHytale` folder, data is migrated automatically to `HytaleNet_hlAuth`.

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

Built jar: `build/libs/hlAuth-1.0.0.jar`.

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

Accounts: `mods/HytaleNet_hlAuth/accounts.json`.  
Messages: `mods/HytaleNet_hlAuth/messages/en.yml` (you can add `ru.yml`, `de.yml`, etc. and set `language` in the config).

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
