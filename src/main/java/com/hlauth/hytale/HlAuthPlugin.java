package com.hlauth.hytale;

import com.hlauth.hytale.command.HlAuthAdminCommand;
import com.hlauth.hytale.command.ChangePasswordCommand;
import com.hlauth.hytale.command.LinkCommand;
import com.hlauth.hytale.command.LoginCommand;
import com.hlauth.hytale.command.LogoutCommand;
import com.hlauth.hytale.command.RegisterCommand;
import com.hlauth.hytale.command.TwoFactorCommand;
import com.hlauth.hytale.command.UnlinkCommand;
import com.hlauth.hytale.command.UnregisterCommand;
import com.hlauth.hytale.config.HlAuthConfig;
import com.hlauth.hytale.listener.LimboCancelSystem;
import com.hlauth.hytale.listener.LimboDamageSystem;
import com.hlauth.hytale.listener.PlayerListener;
import com.hlauth.hytale.message.Messages;
import com.hlauth.hytale.security.PasswordSecurity;
import com.hlauth.hytale.service.AuthService;
import com.hlauth.hytale.service.BackupService;
import com.hlauth.hytale.service.GeoIpService;
import com.hlauth.hytale.service.LimboService;
import com.hlauth.hytale.service.PostAuthCommands;
import com.hlauth.hytale.service.PremiumService;
import com.hlauth.hytale.service.TotpService;
import com.hlauth.hytale.messenger.MessengerService;
import com.hlauth.hytale.metrics.HStats;
import com.hlauth.hytale.metrics.ModifoldAnalytics;
import com.hlauth.hytale.storage.DataSource;
import com.hlauth.hytale.storage.DataSources;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * hlAuth — authentication with native UI menus.
 *
 * <p>Players joining the server are put into "limbo" until they authenticate
 * through the login/register UI pages (or the equivalent chat commands).</p>
 */
public final class HlAuthPlugin extends JavaPlugin {

    private static HlAuthPlugin instance;

    private HlAuthConfig config;
    private Messages messages;
    private DataSource dataSource;
    private PasswordSecurity passwordSecurity;
    private LimboService limboService;
    private AuthService authService;
    private PremiumService premiumService;
    private TotpService totpService;
    private MessengerService messengerService;
    private GeoIpService geoIpService;
    private PostAuthCommands postAuthCommands;
    private BackupService backupService;
    private HStats hStats;
    private ModifoldAnalytics modifoldAnalytics;

    /**
     * Private HStats server reporting key from the dashboard (hstats.dev).
     * The public Mod ID is for pages, embeds, and API lookups only.
     */
    private static final String HSTATS_REPORTING_KEY = "6a750963-cedd-46f5-b020-5622431c9b2c";
    /** Modifold project slug: https://modifold.com/mod/hlauth */
    private static final String MODIFOLD_SLUG = "hlauth";

    public HlAuthPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static HlAuthPlugin get() {
        return instance;
    }

    @Override
    public CompletableFuture<Void> preLoad() {
        getLogger().atInfo().log("hlAuth is preloading...");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected void setup() {
        super.setup();
        // Keep existing accounts/config when renaming from older data folders
        DataDirectoryMigrator.migrateIfNeeded(getDataDirectory(), getLogger());

        this.config = HlAuthConfig.load(getDataDirectory(), getLogger());
        this.messages = new Messages(getDataDirectory(), config.language, getLogger());
        this.dataSource = DataSources.open(config, getDataDirectory(), getLogger());
        this.passwordSecurity = new PasswordSecurity(config, getLogger());
        if (passwordSecurity.didCalibrate()) {
            HlAuthConfig.save(getDataDirectory(), config, getLogger());
        }
        this.limboService = new LimboService(this);
        this.authService = new AuthService(this);
        this.premiumService = new PremiumService(this);
        this.totpService = new TotpService(this);
        this.messengerService = new MessengerService(this);
        this.geoIpService = new GeoIpService(this);
        this.postAuthCommands = new PostAuthCommands(this);
        this.backupService = new BackupService(this);

        registerCommands();
        new PlayerListener(this).register();
        var entities = getEntityStoreRegistry();
        entities.registerSystem(new LimboDamageSystem(this));
        entities.registerSystem(new LimboCancelSystem.BreakBlock(this));
        entities.registerSystem(new LimboCancelSystem.PlaceBlock(this));
        entities.registerSystem(new LimboCancelSystem.PickupItem(this));
        entities.registerSystem(new LimboCancelSystem.DropItem(this));
        entities.registerSystem(new LimboCancelSystem.UseBlockPre(this));
        entities.registerSystem(new LimboCancelSystem.DropItemRequest(this));

        this.hStats = new HStats(HSTATS_REPORTING_KEY, getManifest().getVersion().toString());
        this.modifoldAnalytics = new ModifoldAnalytics(MODIFOLD_SLUG, getManifest().getVersion().toString());

        getLogger().atInfo().log("hlAuth enabled. %d account(s) loaded, storage: %s, language: %s",
            dataSource.getAccountsCount(), dataSource.getName(), config.language);
    }

    @Override
    protected void start() {
        limboService.startTimeoutTask();
        backupService.start();
        messengerService.start();
        geoIpService.start();
    }

    @Override
    protected void shutdown() {
        if (backupService != null) {
            backupService.stop();
        }
        if (limboService != null) {
            limboService.stop();
        }
        if (modifoldAnalytics != null) {
            modifoldAnalytics.close();
        }
        if (messengerService != null) {
            messengerService.stop();
        }
        if (geoIpService != null) {
            geoIpService.stop();
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
        registry.registerCommand(new TwoFactorCommand(this));
        registry.registerCommand(new LinkCommand(this));
        registry.registerCommand(new UnlinkCommand(this));
        registry.registerCommand(new HlAuthAdminCommand(this));
    }

    public HlAuthConfig getConfig() {
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

    public TotpService getTotpService() {
        return totpService;
    }

    public MessengerService getMessengerService() {
        return messengerService;
    }

    public GeoIpService getGeoIpService() {
        return geoIpService;
    }

    public PostAuthCommands getPostAuthCommands() {
        return postAuthCommands;
    }

    public BackupService getBackupService() {
        return backupService;
    }

    /** Reloads config.yml, bot configs and messages/&lt;language&gt;.yml. */
    public void reloadConfig() {
        this.config = HlAuthConfig.load(getDataDirectory(), getLogger());
        this.messages = new Messages(getDataDirectory(), config.language, getLogger());
        this.passwordSecurity = new PasswordSecurity(config, getLogger());
        if (passwordSecurity.didCalibrate()) {
            HlAuthConfig.save(getDataDirectory(), config, getLogger());
        }
        if (messengerService != null) {
            messengerService.reloadBots();
        }
        if (backupService != null) {
            backupService.start();
        }
    }
}
