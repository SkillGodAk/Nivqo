/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

/**
 * Which downloader fetches an app module, such as an instant game (#168). Facebook asks Google
 * Play's split install for a module when Play installed the app and a server flag allows it. Play
 * only serves the splits of the app it published, signed with Meta's key, so for a patched build it
 * answers "module unavailable" and the game's download screen closes a moment after it opens.
 * Facebook's own downloader fetches the same module for any build, so the patch hands this
 * Facebook's choice and the answer is always no.
 */
public final class ModuleDownloads {
    private ModuleDownloads() {}

    /** Called with whether Facebook would use Google Play for a module. Always false. Never throws. */
    public static boolean fromPlay(boolean wanted) {
        if (wanted) ModuleDownloadEvidence.playSkipped();
        return false;
    }
}
