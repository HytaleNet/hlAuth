# hlAuth (AuthMe for Hytale)

Плагин авторизации для **Hytale Server API** с нативными UI-меню. Вдохновлён [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded).

**Авторы:** Chernyash, HytaleNet HLauncher · [github.com/HytaleNet/hlAuth](https://github.com/HytaleNet/hlAuth)

## Возможности

- **Нативные UI-меню** входа и регистрации (Hytale Custom UI, стиль ванильных страниц сервера). Меню нельзя закрыть, пока игрок не авторизуется.
- **Регистрация и вход** — через меню или командами в чате (меню закрывается и при команде).
- **Безопасное хранение паролей** — PBKDF2-HMAC-SHA256 (100 000 итераций, соль). Поддерживается проверка легаси-формата AuthMe `$SHA$` — базу с Minecraft-сервера можно перенести без сброса паролей.
- **Сессии** — при переподключении с того же IP в течение настраиваемого времени вход не требуется.
- **Лимбо-защита** — до входа игрок не может писать в чат, через `timeoutSeconds` секунд его кикает.
- **Premium-проверка** (опционально) — сверка UUID с [playerdb.co](https://playerdb.co): premium-аккаунты входят автоматически; при `premiumAutoRegister` новый premium регистрируется без пароля; offline не может занять premium-ник и наоборот.
- **Локализация** — тексты в `messages/ru.yml` и `messages/en.yml`, язык выбирается в конфиге.
- **Права LuckPerms** — узлы `hlauth.player.*` / `hlauth.admin.*`.
- **Хранилище** — JSON flat-file (`accounts.json`), атомарная асинхронная запись.


## Команды

| Команда | Описание |
|---|---|
| `/login <пароль>` (`/l`) | Вход |
| `/register <пароль> <пароль>` (`/reg`) | Регистрация |
| `/logout` | Выход (сессия сбрасывается) |
| `/changepassword <старый> <новый>` (`/cp`) | Смена пароля |
| `/unregister <пароль>` | Удаление своего аккаунта (кик с сервера) |
| `/hlauth register <ник> <пароль>` (`/authme …`) | Админ: зарегистрировать игрока |
| `/hlauth unregister <ник>` | Админ: удалить аккаунт (кик онлайн-игрока) |
| `/hlauth changepassword <ник> <пароль>` | Админ: сменить пароль |
| `/hlauth info <ник>` | Админ: информация об аккаунте |
| `/hlauth reload` | Админ: перезагрузка конфига и messages |

### Права (LuckPerms)

| Permission | Описание |
|---|---|
| `hlauth.player.login` | `/login` |
| `hlauth.player.register` | `/register` |
| `hlauth.player.logout` | `/logout` |
| `hlauth.player.changepassword` | `/changepassword` |
| `hlauth.player.unregister` | `/unregister` |
| `hlauth.admin` | корень `/hlauth` (алиас `/authme`) |
| `hlauth.admin.register` | `/hlauth register` |
| `hlauth.admin.unregister` | `/hlauth unregister` |
| `hlauth.admin.changepassword` | `/hlauth changepassword` |
| `hlauth.admin.info` | `/hlauth info` |
| `hlauth.admin.reload` | `/hlauth reload` |

Игровые команды по умолчанию выдаются группе `hytale:Adventurer`. Админ-права выдавайте через LuckPerms (`hlauth.admin` или `hlauth.admin.*`).

Формат `config.json` и `accounts.json` не менялся. При обновлении с папки `AuthMe_AuthMeHytale` данные автоматически переносятся в `HytaleNet_hlAuth`.

## Сборка

Требуется JDK 21+ (проверено на JDK 25) и локальный `HytaleServer.jar`.

**PowerShell (без Gradle):**

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
# или с явным путём к серверному jar:
powershell -ExecutionPolicy Bypass -File build.ps1 -ServerJar "D:\path\to\HytaleServer.jar"
```

**Gradle:**

```bash
gradle build -PhytaleServerJar="D:/path/to/HytaleServer.jar"
```

Готовый jar: `build/libs/hlAuth-1.0.0.jar`.

## Установка

Скопируйте `hlAuth-1.0.0.jar` в папку `mods/` вашего Hytale-сервера и перезапустите сервер.

## Конфигурация

После первого запуска создаётся `mods/HytaleNet_hlAuth/config.json`:

| Опция | По умолчанию | Описание |
|---|---|---|
| `language` | `"ru"` | Файл языка в `messages/` без `.yml` (`ru`, `en`, …) |
| `registrationEnabled` | `true` | Обязательная регистрация новых игроков |
| `timeoutSeconds` | `120` | Кик, если игрок не авторизовался за это время |
| `passwordMinLength` / `passwordMaxLength` | `5` / `64` | Ограничения длины пароля |
| `unsafePasswords` | `[...]` | Запрещённые пароли |
| `maxRegistrationsPerIp` | `2` | Максимум аккаунтов с одного IP (0 = без лимита) |
| `maxLoginTries` | `5` | Кик после N неверных паролей |
| `kickOnWrongPassword` | `false` | Кик сразу при неверном пароле |
| `sessionsEnabled` | `true` | Автовход по сессии |
| `sessionTimeoutMinutes` | `10` | Время жизни сессии |
| `useUiMenus` | `true` | UI-меню вместо только чат-команд |
| `uiOpenDelayMs` | `1000` | Задержка открытия меню (мс), снижает глитч текстур |
| `premiumCheckEnabled` | `false` | Проверка UUID через playerdb.co |
| `premiumCheckTimeoutSeconds` | `5` | Таймаут запроса premium |
| `premiumKickEnabled` | `false` | Кик при premium/offline mismatch (иначе UI-плашка) |
| `premiumAutoRegister` | `false` | Автовход и авторегистрация verified premium без пароля (нужен `premiumCheckEnabled`) |
| `protectChat` | `true` | Блокировать чат до входа |
| `messageIntervalSeconds` | `15` | Интервал напоминаний в чате |

Аккаунты: `mods/HytaleNet_hlAuth/accounts.json`.  
Сообщения: `mods/HytaleNet_hlAuth/messages/ru.yml` (можно добавить `en.yml`, `de.yml` и указать `language` в конфиге).

## Перенос базы с Minecraft (AuthMeReloaded)

Хэши формата `$SHA$соль$хэш` (SHA256 — алгоритм AuthMe по умолчанию) проверяются напрямую. Сконвертируйте вашу таблицу `authme` в `accounts.json` вида:

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
