/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.chats;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Optional bridge from Facebook's own Chats surface to the installed Messenger app.
 *
 * <p>The bytecode patch calls this from the same 577/580 Chats plugin question already used by
 * {@link MessengerCard}. With the redirect switch off it behaves exactly like the original
 * Hushfacebook hook. With it on, an enabled {@code com.facebook.orca} is launched and the current
 * Facebook Chats activity is finished so Back returns to the screen that opened Chats.
 *
 * <p>The package's signing certificate is deliberately not compared. Starting another app does not
 * require a shared signer, and this is specifically useful when Facebook was re-signed by Morphe.
 */
public final class MessengerRedirect {
    private MessengerRedirect() {
    }

    /**
     * True tells Facebook that this top-banner plugin need not continue: either Messenger was
     * opened, or the original card-hiding rule says the card should be hidden.
     */
    public static boolean hideOrRedirect(Context context) {
        try {
            if (Utils.settingsReady() && Settings.OPEN_CHATS_IN_MESSENGER.get()) {
                if (MessengerCard.messengerInstalled() && openMessenger(context)) {
                    return true;
                }
                // Redirect was requested, but Messenger could not be opened from this context.
                // Fall through to Facebook's own Chats path instead of consuming the entry.
                return false;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSENGER_CARD, "Messenger redirect", failure);
            return false;
        }
        return MessengerCard.hide();
    }

    /** Opens Messenger's launcher activity. Failure leaves Facebook's own Chats in place. */
    public static boolean openMessenger(Context context) {
        if (context == null) return false;
        Intent launch = null;
        try {
            PackageManager packages = context.getPackageManager();
            if (packages != null) launch = packages.getLaunchIntentForPackage(MessengerCard.MESSENGER);
        } catch (Throwable ignored) {
            launch = null;
        }
        if (launch == null) {
            launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(MessengerCard.MESSENGER);
        }
        return start(context, launch);
    }

    /**
     * Starts [launch] without mutating the PackageManager-owned Intent. Package-private so tests can
     * prove the navigation and Activity finishing independently of Robolectric's launcher resolver.
     */
    static boolean start(Context context, Intent launch) {
        if (context == null || launch == null) return false;
        Intent copy = new Intent(launch);
        if (!(context instanceof Activity)) copy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(copy);
        if (context instanceof Activity) ((Activity) context).finish();
        Logger.printDebug(() -> "Chats: opened Messenger");
        return true;
    }
}
