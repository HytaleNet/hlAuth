# hlAuth (AuthMe for Hytale)

Плагин авторизации для **Hytale Server** с нативными UI-меню. Вдохновлён [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded).

**Авторы:** Chernyash, HytaleNet HLauncher · [github.com/HytaleNet/hlAuth](https://github.com/HytaleNet/hlAuth)

> English version: [README.md](README.md)

## Возможности

- **Нативные UI-меню** входа и регистрации (Hytale Custom UI, стиль ванильных страниц сервера). Меню нельзя закрыть, пока игрок не авторизуется.
- **Регистрация и вход** — через меню или командами в чате (меню закрывается и при команде).
- **Безопасное хранение паролей** — PBKDF2-HMAC-SHA256 (100 000 итераций, соль). Поддерживается проверка легаси-формата AuthMe `$SHA$` — базу с Minecraft-сервера можно перенести без сброса паролей.
- **Сессии** — при переподключении с того же IP в течение настраиваемого времени вход не требуется.
- **Лимбо-защита** — до входа игрок не может писать в чат, через `timeoutSeconds` секунд его кикает.
- **Premium-проверка** (опционально) — сверка UUID с [playerdb.co](https://playerdb.co): premium-аккаунты входят автоматически; при `premiumAutoRegister` новый premium регистрируется без пароля; offline не может занять premium-ник и наоборот.
- **Локализация** — тексты в `messages/ru.yml` и `messages/en.yml`, язык выбирается в конфиге.
- **Двухфакторная аутентификация (опционально)** — TOTP для Google Authenticator / Aegis / 2FAS: QR + секрет, затем одноразовые коды восстановления на скриншот. Вкл/выкл: `twoFactorEnabled`; обязательность: `twoFactorRequired`.
- **Права LuckPerms** — узлы `hlauth.player.*` / `hlauth.admin.*`.
- **Хранилище** — `json` (`accounts.json`), встроенный **H2** или **MySQL** (локальный или внешний JDBC). При переключении JSON → H2/MySQL аккаунты импортируются автоматически.

## Команды

| Команда | Описание |
|---|---|
| `/login <пароль>` (`/l`) | Вход |
| `/register <пароль> <пароль>` (`/reg`) | Регистрация |
| `/logout` | Выход (сессия сбрасывается) |
| `/changepassword <старый> <новый>` (`/cp`) | Смена пароля |
| `/unregister <пароль>` | Удаление своего аккаунта (кик с сервера) |
| `/2fa` (`/totp`) | Включить 2FA (QR / секрет) после входа |
| `/2fa <код>` | Подтвердить привязку или ввести код authenticator при входе |
| `/2fa recover <код>` | Войти одноразовым кодом восстановления |
| `/2fa disable <код>` | Отключить 2FA (код из приложения или recovery; нельзя, если 2FA обязательна) |
| `/2fa done` | Продолжить после сохранения кодов восстановления |
| `/hlauth register <ник> <пароль>` (`/authme …`) | Админ: зарегистрировать игрока |
| `/hlauth unregister <ник>` | Админ: удалить аккаунт (кик онлайн-игрока) |
| `/hlauth changepassword <ник> <пароль>` | Админ: сменить пароль |
| `/hlauth info <ник>` | Админ: информация об аккаунте |
| `/hlauth reload` | Админ: перезагрузка конфига и messages |
| `/hlauth backup` | Админ: снимок всех аккаунтов в JSON |
| `/hlauth 2fareset <ник>` | Админ: снять 2FA с аккаунта |

### Права (LuckPerms)

| Permission | Описание |
|---|---|
| `hlauth.player.login` | `/login` |
| `hlauth.player.register` | `/register` |
| `hlauth.player.logout` | `/logout` |
| `hlauth.player.changepassword` | `/changepassword` |
| `hlauth.player.unregister` | `/unregister` |
| `hlauth.player.2fa` | `/2fa` |
| `hlauth.admin` | корень `/hlauth` (алиас `/authme`) |
| `hlauth.admin.register` | `/hlauth register` |
| `hlauth.admin.unregister` | `/hlauth unregister` |
| `hlauth.admin.changepassword` | `/hlauth changepassword` |
| `hlauth.admin.info` | `/hlauth info` |
| `hlauth.admin.reload` | `/hlauth reload` |
| `hlauth.admin.backup` | `/hlauth backup` |
| `hlauth.admin.2fareset` | `/hlauth 2fareset` |

Игровые команды по умолчанию выдаются группе `hytale:Adventurer`. Админ-права выдавайте через LuckPerms (`hlauth.admin` или `hlauth.admin.*`).

Формат `config.json` совместим (новые ключи дописываются при reload). При `storageType: json` по-прежнему используется `accounts.json`. H2/MySQL тоже хранят поля TOTP.

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

Готовый jar: `build/libs/hlAuth-1.0.1.jar`.

## Установка

Скопируйте `hlAuth-X.X.X.jar` в папку `mods/` вашего Hytale-сервера и перезапустите сервер.

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
| `twoFactorEnabled` | `false` | TOTP 2FA (Authenticator / Aegis / 2FAS) |
| `twoFactorRequired` | `false` | Обязать всех привязать 2FA после пароля (нужен `twoFactorEnabled`) |
| `twoFactorRequiredOnSession` | `false` | Спрашивать 2FA и при автовходе по сессии |
| `twoFactorIssuer` | `"hlAuth"` | Имя сервера в приложении-аутентификаторе |
| `storageType` | `"json"` | `json` / `h2` / `mysql` |
| `databaseHost` | `"127.0.0.1"` | Хост MySQL (для встроенного H2 не нужен) |
| `databasePort` | `3306` | Порт MySQL |
| `databaseName` | `"hlauth"` | Имя базы MySQL (создаётся, если есть `CREATE DATABASE`) |
| `databaseUsername` / `databasePassword` | `hlauth` / пусто | Логин БД |
| `databaseTable` | `"hlauth_accounts"` | Таблица аккаунтов |
| `databaseUseSsl` | `false` | SSL для MySQL |
| `databasePoolSize` | `4` | Размер пула JDBC |
| `databaseJdbcUrl` | `""` | Полный JDBC URL (перебивает host/port/name). Пример: `jdbc:mysql://db.example.com:3306/hlauth` или `jdbc:h2:tcp://127.0.0.1:9092/hlauth` |
| `backupEnabled` | `false` | Автобекапы аккаунтов в `backups/` |
| `backupIntervalHours` | `6` | Интервал автобекапа, часы (плюсуются к минутам) |
| `backupIntervalMinutes` | `0` | Дополнительные минуты (например `0` + `30` = каждые 30 минут) |
| `backupKeepCount` | `24` | Сколько файлов бекапа хранить (`0` = все) |

Аккаунты (режим JSON): `mods/HytaleNet_hlAuth/accounts.json`.  
Файл H2: `mods/HytaleNet_hlAuth/hlauth.mv.db`.  
Сообщения: `mods/HytaleNet_hlAuth/messages/ru.yml` (можно добавить `en.yml`, `de.yml` и указать `language` в конфиге).

Чтобы перейти с JSON на H2: поставьте `storageType` в `"h2"` и перезапустите — аккаунты импортируются, если таблица пустая. Для MySQL задайте пользователя, host/name/пароль (или `databaseJdbcUrl`) и перезапустите.

Бекапы: `mods/HytaleNet_hlAuth/backups/hlauth-YYYY-MM-DD_HH-mm-ss.json` (тот же формат, что `accounts.json`). Восстановление: скопировать файл в `accounts.json` (режим JSON) либо очистить SQL-таблицу / удалить файл H2, чтобы при старте снова импортировался `accounts.json`.

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
