# hlAuth

Плагин авторизации для **Hytale Server** с нативными UI-меню.

**Автор:** Chernyash · [hlauncher.com](https://hlauncher.com)

> English version: [README.md](README.md)

## Возможности

- **Нативные UI-меню** входа и регистрации (Hytale Custom UI, стиль ванильных страниц сервера). Меню нельзя закрыть, пока игрок не авторизуется.
- **Регистрация и вход** — через меню или командами в чате (меню закрывается и при команде).
- **Telegram / Discord** — привязка аккаунта, подтверждение входа в боте, режим «только подтверждение», slash-команды и embed в Discord.
- **Безопасное хранение паролей** — bcrypt с бенчмарком cost при старте (максимальный cost, который укладывается в `bcryptTargetMs`). Старые хеши PBKDF2 с прошлых сборок hlAuth при успешном входе переписываются на bcrypt.
- **Сессии** — при переподключении с того же IP в течение настраиваемого времени вход не требуется.
- **Лимбо-защита** — до входа игрок не может писать в чат, через `timeoutSeconds` секунд его кикает.
- **Premium-проверка** (опционально) — сверка UUID с [playerdb.co](https://playerdb.co): premium-аккаунты входят автоматически; при `premiumAutoRegister` новый premium регистрируется без пароля; offline не может занять premium-ник и наоборот.
- **Локализация** — тексты в `messages/ru.yml` и `messages/en.yml`, язык выбирается в конфиге.
- **Двухфакторная аутентификация (опционально)** — TOTP для Google Authenticator / Aegis / 2FAS: QR + секрет, затем одноразовые коды восстановления на скриншот. Вкл/выкл: `twoFactorEnabled`; обязательность: `twoFactorRequired`.
- **Права LuckPerms** — узлы `hlauth.player.*` / `hlauth.admin.*`.
- **Хранилище** — встроенный **H2** (по умолчанию), **SQLite** или **MySQL**. JDBC-драйверы скачиваются в `lib/` при первом запуске (не упакованы в jar плагина). Если остался `accounts.json` со старой версии и база пустая, аккаунты импортируются автоматически.

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
| `/link telegram` / `/link discord` | Привязать Telegram или Discord (затем отправить код боту) |
| `/link confirm <код>` | Подтвердить привязку |
| `/unlink telegram` / `/unlink discord` | Отвязать мессенджер |
| `/hlauth register <ник> <пароль>` | Админ: зарегистрировать игрока |
| `/hlauth unregister <ник>` | Админ: удалить аккаунт (кик онлайн-игрока) |
| `/hlauth changepassword <ник> <пароль>` | Админ: сменить пароль |
| `/hlauth info <ник>` | Админ: информация об аккаунте |
| `/hlauth reload` | Админ: перезагрузка конфига и messages |
| `/hlauth backup` | Админ: снимок всех аккаунтов в JSON |
| `/hlauth 2fareset <ник>` | Админ: снять 2FA с аккаунта |
| `/hlauth ipcheck <ник>` | Админ: скрытые IP и связанные аккаунты (меню) |
| `/hlauth unlink <ник> telegram\|discord` | Админ: отвязать Telegram или Discord |

### Права (LuckPerms)

| Permission | Описание |
|---|---|
| `hlauth.player.login` | `/login` |
| `hlauth.player.register` | `/register` |
| `hlauth.player.logout` | `/logout` |
| `hlauth.player.changepassword` | `/changepassword` |
| `hlauth.player.unregister` | `/unregister` |
| `hlauth.player.2fa` | `/2fa` |
| `hlauth.player.link` | `/link` |
| `hlauth.player.unlink` | `/unlink` |
| `hlauth.admin` | корень `/hlauth` |
| `hlauth.admin.register` | `/hlauth register` |
| `hlauth.admin.unregister` | `/hlauth unregister` |
| `hlauth.admin.changepassword` | `/hlauth changepassword` |
| `hlauth.admin.info` | `/hlauth info` |
| `hlauth.admin.reload` | `/hlauth reload` |
| `hlauth.admin.backup` | `/hlauth backup` |
| `hlauth.admin.2fareset` | `/hlauth 2fareset` |
| `hlauth.admin.ipcheck` | `/hlauth ipcheck` |
| `hlauth.admin.unlink` | `/hlauth unlink` |

Игровые команды по умолчанию выдаются группе `hytale:Adventurer`. Админ-права выдавайте через LuckPerms (`hlauth.admin` или `hlauth.admin.*`).

Формат `config.json` совместим (новые ключи дописываются при reload). `storageType: json` больше не используется и переписывается в `h2`; оставшийся `accounts.json` импортируется в пустую базу.

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

Готовый jar: `build/libs/hlAuth-1.1.0.jar`.

## Установка

Скопируйте `hlAuth-X.X.X.jar` в папку `mods/` вашего Hytale-сервера и перезапустите сервер.

При первом запуске плагин скачивает JDBC-драйвер для выбранного `storageType` с Maven Central в `mods/HytaleNet_hlAuth/lib/` (H2 ≈ 2.5 МБ, MySQL ≈ 2.5 МБ, SQLite ≈ 14 МБ). Дальше используется уже скачанный файл. Если у сервера нет исходящего HTTPS, положите нужный jar туда вручную (`h2-2.3.232.jar`, `sqlite-jdbc-3.49.1.0.jar` или `mysql-connector-j-8.4.0.jar`).

## Конфигурация

После первого запуска создаётся `mods/HytaleNet_hlAuth/config.yml`. Боты вынесены в отдельные файлы: `telegram-bot.yml` и `discord-bot.yml`.

| Опция | По умолчанию | Описание |
|---|---|---|
| `language` | `"en"` | Файл языка в `messages/` без `.yml` (`en`, `ru`, …) |
| `registrationEnabled` | `true` | Обязательная регистрация новых игроков |
| `timeoutSeconds` | `120` | Кик, если игрок не авторизовался за это время |
| `passwordMinLength` / `passwordMaxLength` | `5` / `64` | Ограничения длины пароля |
| `maxRegistrationsPerIp` | `2` | Максимум аккаунтов с одного IP (0 = без лимита) |
| `maxLoginTries` | `5` | Кик после N неверных паролей |
| `kickOnWrongPassword` | `false` | Кик сразу при неверном пароле |
| `bcryptCost` | `0` | log-rounds bcrypt для новых хешей. `0` = бенчмарк при старте и запись выбранного cost (10–14) |
| `bcryptTargetMs` | `250` | Целевое время хеша в мс для бенчмарка bcrypt |
| `registerCommands` / `loginCommands` | `[]` | Команды после регистрации/входа. Префиксы: `[CONSOLE]`, `[PLAYER]`, `[WAIT]` (мс). `{nick}` = ник. Пример в `config.json` закомментирован |
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
| `telegramEnabled` | `false` | Telegram-бот: привязка + подтверждение входа |
| `telegramBotToken` | `""` | Токен от @BotFather |
| `telegramBotUsername` | `""` | Username бота без `@` (необязательно) |
| `discordEnabled` | `false` | Discord-бот: привязка + подтверждение входа |
| `discordBotToken` | `""` | Токен бота (включите Message Content и Direct Messages intents) |
| `discordInviteUrl` | `""` | Инвайт на сервер с ботом (нужен для личных сообщений) |
| `messengerLoginMode` | `"password_and_confirm"` | Режим по умолчанию для новых привязок: `password_and_confirm` = пароль + подтверждение в боте, `confirm_only` = только подтверждение в боте (игрок потом может сменить через `/mode ...` в боте) |
| `storageType` | `"h2"` | `h2` / `sqlite` / `mysql` |
| `databaseHost` | `"127.0.0.1"` | Хост MySQL (для встроенного H2 не нужен) |
| `databasePort` | `3306` | Порт MySQL |
| `databaseName` | `"hlauth"` | Имя базы MySQL (создаётся, если есть `CREATE DATABASE`) |
| `databaseUsername` / `databasePassword` | `hlauth` / пусто | Логин БД |
| `databaseTable` | `"hlauth_accounts"` | Таблица аккаунтов |
| `databaseUseSsl` | `false` | SSL для MySQL |
| `databasePoolSize` | `4` | Размер пула JDBC |
| `databaseJdbcUrl` | `""` | Полный JDBC URL (перебивает host/port/name). Пример: `jdbc:mysql://db.example.com:3306/hlauth`, `jdbc:h2:tcp://127.0.0.1:9092/hlauth`, `jdbc:sqlite:/path/hlauth.db` |
| `backupEnabled` | `false` | Автобекапы аккаунтов в `backups/` |
| `backupIntervalHours` | `6` | Интервал автобекапа, часы (плюсуются к минутам) |
| `backupIntervalMinutes` | `0` | Дополнительные минуты (например `0` + `30` = каждые 30 минут) |
| `backupKeepCount` | `24` | Сколько файлов бекапа хранить (`0` = все) |

Файл H2: `mods/HytaleNet_hlAuth/hlauth.mv.db`.  
Файл SQLite: `mods/HytaleNet_hlAuth/hlauth.db`.  
Запрещённые пароли: `mods/HytaleNet_hlAuth/unsafePasswords.txt` (по одному на строку).  
Сообщения: `mods/HytaleNet_hlAuth/messages/ru.yml` (можно добавить `en.yml`, `de.yml` и указать `language` в конфиге).

Если остался `accounts.json` с 1.0.0/1.0.1, оставьте его в папке данных и запустите с пустой H2/SQLite — аккаунты импортируются один раз. Для MySQL задайте пользователя, host/name/пароль (или `databaseJdbcUrl`) и перезапустите.

Бекапы: `mods/HytaleNet_hlAuth/backups/hlauth-YYYY-MM-DD_HH-mm-ss.json`. Восстановление: положить файл как `accounts.json` в папку плагина, очистить/удалить SQL-базу и перезапустить, чтобы снова импортировался.
