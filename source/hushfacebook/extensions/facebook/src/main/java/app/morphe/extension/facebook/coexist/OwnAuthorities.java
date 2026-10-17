/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.coexist;

import android.app.Application;

import androidx.annotation.Nullable;

/**
 * Facebook's own content provider authorities, and the package its notifications' views name, as
 * the running app has them.
 *
 * <p>Facebook's code spells some of its authorities out, such as
 * {@code content://com.facebook.katana.ClientMessagePushDedupInfoProvider/mutestatus}, which it
 * clears at every start. Morphe's Clone app renames the package, and the clone's providers move
 * with it, so a clone reached the providers of the Facebook it was cloned from instead. That app
 * turned it away, and the clone crashed at launch (#16). Every place the code loads one of those
 * names hands it to {@link #name}, which answers the running app's.
 *
 * <p>Facebook fills in some of these names from static initialisers, which can run while its
 * content providers start and before Hushfacebook has a context. So the running package comes from
 * the process name, which Android sets before any of the app's code runs. Facebook names every
 * process after its package, a colon and a name of its own, and the patch's tests hold that. No
 * setting and no log is read here, for the same reason.
 */
@SuppressWarnings("unused")
public final class OwnAuthorities {
    static final String FACEBOOK = "com.facebook.katana";
    private static final String CONTENT = "content://";

    /** The running app's package, read off its process name the first time it's needed. */
    @Nullable
    static volatile String runningPackage;

    private OwnAuthorities() {
    }

    /**
     * [facebookName], one of Facebook's own authorities or a content:// address on one, as the
     * running app has it: unchanged in Facebook itself, under the clone's package in a clone. Any
     * other text comes back as it was.
     */
    public static String name(@Nullable String facebookName) {
        if (facebookName == null) return null;
        try {
            String running = runningPackage();
            if (running == null || FACEBOOK.equals(running)) return facebookName;
            int start = facebookName.startsWith(CONTENT) ? CONTENT.length() : 0;
            if (!facebookName.startsWith(FACEBOOK + ".", start)) return facebookName;
            return facebookName.substring(0, start) + running + facebookName.substring(start + FACEBOOK.length());
        } catch (Throwable failure) {
            // Facebook's own name is what every install but a clone uses.
            return facebookName;
        }
    }

    /**
     * [facebooksPackage], the package name Facebook's notification templates build their
     * RemoteViews with, as the running app's package. RemoteViews takes its layouts from the
     * package it names, so a clone handing over Facebook's name had its notifications drawn from
     * the stock app's layouts, or from an app that isn't there, and Android closed it for a bad
     * notification (#36). The same goes for the places Facebook names itself to Android: the intent
     * a notification's tap sends, the app a notification launches, and the meta-data Firebase reads
     * for a notification's icon. Anything but Facebook's package name exactly comes back as it was.
     */
    public static String packageName(@Nullable String facebooksPackage) {
        if (!FACEBOOK.equals(facebooksPackage)) return facebooksPackage;
        try {
            String running = runningPackage();
            return running == null ? facebooksPackage : running;
        } catch (Throwable failure) {
            // Facebook's own name is what every install but a clone uses.
            return facebooksPackage;
        }
    }

    /**
     * [packageName], the running app's package as Facebook reads it where it looks itself up among
     * Meta's apps, as the Facebook a clone stands in for. Facebook's encrypted chat backup picks its
     * preferences file by finding the running package among Messenger's and Facebook's names and
     * throws when none matches, so a clone crashed as soon as chats opened (#113). The running app's
     * own package comes back as Facebook's in a clone. Anything else, and every name in Facebook
     * itself, comes back as it was.
     */
    public static String asFacebook(@Nullable String packageName) {
        if (packageName == null || FACEBOOK.equals(packageName)) return packageName;
        try {
            return packageName.equals(runningPackage()) ? FACEBOOK : packageName;
        } catch (Throwable failure) {
            // The name as read is what every install but a clone uses.
            return packageName;
        }
    }

    @Nullable
    private static String runningPackage() {
        String known = runningPackage;
        if (known != null) return known;
        known = packageOf(Application.getProcessName());
        runningPackage = known;
        return known;
    }

    /** The package a process of this name belongs to: the name up to its first colon. */
    @Nullable
    static String packageOf(@Nullable String processName) {
        if (processName == null) return null;
        int colon = processName.indexOf(':');
        String name = colon < 0 ? processName : processName.substring(0, colon);
        return name.isEmpty() ? null : name;
    }
}
