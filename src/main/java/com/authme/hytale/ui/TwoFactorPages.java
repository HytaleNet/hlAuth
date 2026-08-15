package com.authme.hytale.ui;

import com.authme.hytale.AuthMePlugin;
import com.authme.hytale.service.AuthService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

/** Opens the 2FA custom page that matches an {@link AuthService.Result}. */
public final class TwoFactorPages {

    private TwoFactorPages() {
    }

    /**
     * @return true when a 2FA page was opened (caller should not close/finish auth)
     */
    public static boolean open(AuthMePlugin plugin,
                               PlayerRef playerRef,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               AuthService.Result result) {
        if (result == null || playerRef == null || store == null || ref == null || !ref.isValid()) {
            return false;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return false;
        }
        CustomUIPage page = switch (result.status()) {
            case TOTP_VERIFY -> new TotpVerifyPage(playerRef, plugin);
            case TOTP_SETUP -> new TotpSetupPage(playerRef, plugin);
            case TOTP_RECOVERY -> new TotpRecoveryPage(playerRef, plugin);
            default -> null;
        };
        if (page == null) {
            return false;
        }
        player.getPageManager().openCustomPage(ref, store, page);
        if (page instanceof TotpVerifyPage verify) {
            verify.refreshTexts();
        } else if (page instanceof TotpSetupPage setup) {
            setup.refreshTexts();
        } else if (page instanceof TotpRecoveryPage recovery) {
            recovery.refreshTexts();
        }
        return true;
    }

    public static boolean open(AuthMePlugin plugin, PlayerRef playerRef, AuthService.Result result) {
        if (result == null) {
            return false;
        }
        return switch (result.status()) {
            case TOTP_VERIFY, TOTP_SETUP, TOTP_RECOVERY ->
                AuthUi.runOnWorldThread(playerRef, live ->
                    open(plugin, live.playerRef(), live.store(), live.ref(), result));
            default -> false;
        };
    }

    public static void sendRecoveryToChat(AuthMePlugin plugin, PlayerRef playerRef) {
        String[] codes = plugin.getTotpService().pendingRecoveryCodes(playerRef.getUuid());
        if (codes == null) {
            return;
        }
        playerRef.sendMessage(plugin.getMessages().get("2fa.recovery.header"));
        for (String code : codes) {
            playerRef.sendMessage(plugin.getMessages().get("2fa.recovery.line", "code", code));
        }
        playerRef.sendMessage(plugin.getMessages().get("2fa.recovery.footer"));
    }

    /** Cancel button on setup/verify: close the page, or kick if login is unfinished. */
    public static void cancel(AuthMePlugin plugin,
                              PlayerRef playerRef,
                              Store<EntityStore> store,
                              Ref<EntityStore> ref) {
        boolean kick = plugin.getTotpService().cancelUi(playerRef);
        if (kick) {
            AuthUi.closeAndDisconnect(playerRef, plugin.getMessages().get("2fa.cancelled"));
            return;
        }
        AuthUi.close(store, ref);
        playerRef.sendMessage(plugin.getMessages().get("2fa.setup.cancelled"));
    }

    public static boolean isTwoFactorPage(@Nullable CustomUIPage page) {
        return page instanceof TotpVerifyPage
            || page instanceof TotpSetupPage
            || page instanceof TotpRecoveryPage;
    }
}
