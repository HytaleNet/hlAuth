package com.authme.hytale;

import com.authme.hytale.command.AuthMeAdminCommand;
import com.authme.hytale.command.ChangePasswordCommand;
import com.authme.hytale.command.LoginCommand;
import com.authme.hytale.command.LogoutCommand;
import com.authme.hytale.command.RegisterCommand;
import com.authme.hytale.command.UnregisterCommand;
import com.authme.hytale.config.AuthMeConfig;
import com.authme.hytale.listener.LimboDamageSystem;
import com.authme.hytale.listener.PlayerListener;
import com.authme.hytale.message.Messages;
import com.authme.hytale.security.PasswordSecurity;
import com.authme.hytale.service.AuthService;
import com.authme.hytale.service.LimboService;
import com.authme.hytale.service.PremiumService;
import com.authme.hytale.storage.DataSource;
import com.authme.hytale.storage.JsonDataSource;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * AuthMe for Hytale - authentication with native UI menus.
 *
 * <p>Players joining the server are put into "limbo" until they authenticate
 * through the login/register UI pages (or the equivalent chat commands).</p>
 */
public final class AuthMePlugin extends JavaPlugin {

    private static AuthMePlugin instance;

    private AuthMeConfig config;
    private Messages messages;
    private DataSource dataSource;
    private PasswordSecurity passwordSecurity;
    private LimboService limboService;
    private AuthService authService;
    private PremiumService premiumService;

    public AuthMePlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static AuthMePlugin get() {
        return instance;
    }

    @Override
    public CompletableFuture<Void> preLoad() {
        getLogger().atInfo().log("hlAuth is preloading...");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected void setup() {
        // Keep existing accounts/config when renaming from AuthMe_* folders
        DataDirectoryMigrator.migrateIfNeeded(getDataDirectory(), getLogger());

        this.config = AuthMeConfig.load(getDataDirectory(), getLogger());
        this.messages = new Messages(getDataDirectory(), config.language, getLogger());
        this.dataSource = new JsonDataSource(getDataDirectory(), getLogger());
        this.passwordSecurity = new PasswordSecurity(config);
        this.limboService = new LimboService(this);
        this.authService = new AuthService(this);
        this.premiumService = new PremiumService(this);

        registerCommands();
        new PlayerListener(this).register();
        getEntityStoreRegistry().registerSystem(new LimboDamageSystem(this));

        getLogger().atInfo().log("hlAuth enabled. %d account(s) loaded, storage: %s, language: %s",
            dataSource.getAccountsCount(), dataSource.getName(), config.language);
    }

    @Override
    protected void start() {
        limboService.startTimeoutTask();
    }

    @Override
    protected void shutdown() {
        if (limboService != null) {
            limboService.stop();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        getLogger().atInfo().log("hlAuth disabled.");
    }

    private void registerCommands() {
        var registry = getCommandRegistry();
        registry.registerCommand(new LoginCommand(this));
        registry.registerCommand(new RegisterCommand(this));
        registry.registerCommand(new LogoutCommand(this));
        registry.registerCommand(new ChangePasswordCommand(this));
        registry.registerCommand(new UnregisterCommand(this));
        registry.registerCommand(new AuthMeAdminCommand(this));
    }

    public AuthMeConfig getConfig() {
        return config;
    }

    public Messages getMessages() {
        return messages;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public PasswordSecurity getPasswordSecurity() {
        return passwordSecurity;
    }

    public LimboService getLimboService() {
        return limboService;
    }

    public AuthService getAuthService() {
        return authService;
    }

    public PremiumService getPremiumService() {
        return premiumService;
    }

    /** Reloads config.json and messages/&lt;language&gt;.yml. */
    public void reloadConfig() {
        this.config = AuthMeConfig.load(getDataDirectory(), getLogger());
        this.messages = new Messages(getDataDirectory(), config.language, getLogger());
        this.passwordSecurity = new PasswordSecurity(config);
    }
}
