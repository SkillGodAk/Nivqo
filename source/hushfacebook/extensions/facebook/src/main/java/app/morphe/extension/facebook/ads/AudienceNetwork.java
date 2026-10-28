/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Keeps the Audience Network components Disable Audience Network switched off in the manifest off
 * on the phone too (#168).
 *
 * <p>Facebook turned them back on two ways. Its component manager turns on whatever the manifest
 * declares off after an install, which the patch's default-state meta-data now stops, but a
 * component an earlier install turned on can stay on, so {@link #keepOff} puts any that's on back
 * to the manifest's state at each start. And a startup task turns the ipc components on in a state
 * the server picks, so its calls come through {@link #componentState}.
 */
public final class AudienceNetwork {
    /** The components the patch switches off, as AUDIENCE_NETWORK_COMPONENTS in the patch holds them. */
    static final String[] COMPONENTS = {
            "com.facebook.ads.internal.ipc.AudienceNetworkRemoteService",
            "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity",
            "com.facebook.ads.internal.ipc.AudienceNetworkExportedActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "com.facebook.audiencenetwork.AudienceNetworkService",
    };

    /** The HookStatus count of components found on at a start and put back off. */
    static final String SWITCHED_OFF = "components found on and switched back off";

    /** The HookStatus count of Facebook's requests to turn one on, kept off. */
    static final String KEPT_OFF = "requests to turn one on kept off";

    private AudienceNetwork() {
    }

    /**
     * Injection point, in place of the Audience Network startup task's
     * PackageManager.setComponentEnabledSetting call. Makes that call, with the manifest's state in
     * place of on for an Audience Network component, so the call's own errors still reach Facebook.
     */
    public static void componentState(PackageManager manager, ComponentName component, int state, int flags) {
        manager.setComponentEnabledSetting(component, kept(component, state), flags);
    }

    /** The state to set [component] to when Facebook asks for [state]. Never throws. */
    static int kept(@Nullable ComponentName component, int state) {
        try {
            HookStatus.invoked(FamilyNames.AUDIENCE_NETWORK);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED || !isAudienceNetwork(component)) return state;
            HookStatus.counted(FamilyNames.AUDIENCE_NETWORK, KEPT_OFF);
            Logger.printDebug(() -> "Audience Network: kept " + component.getClassName() + " off");
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AUDIENCE_NETWORK, "component state", failure);
            return state;
        }
    }

    /**
     * Called as Facebook starts, in its main process: on a worker, puts each Audience Network
     * component that's on back to the manifest's state, which is off. Never throws.
     */
    public static void keepOff(Context context) {
        try {
            Utils.runOnBackgroundThread(() -> switchOff(context));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AUDIENCE_NETWORK, "start", failure);
        }
    }

    static void switchOff(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            for (String name : COMPONENTS) {
                ComponentName component = new ComponentName(context.getPackageName(), name);
                try {
                    if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                        continue;
                    }
                } catch (IllegalArgumentException notInThisBuild) {
                    continue;
                }
                manager.setComponentEnabledSetting(component,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
                HookStatus.counted(FamilyNames.AUDIENCE_NETWORK, SWITCHED_OFF);
                Logger.printDebug(() -> "Audience Network: switched " + name + " back off");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AUDIENCE_NETWORK, "start", failure);
        }
    }

    private static boolean isAudienceNetwork(@Nullable ComponentName component) {
        String name = component == null ? null : component.getClassName();
        if (name == null) return false;
        for (String known : COMPONENTS) {
            if (known.equals(name)) return true;
        }
        return false;
    }
}
