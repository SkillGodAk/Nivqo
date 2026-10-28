/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Audience Network components stay off (#168): Facebook's startup task can't turn one on, and one
 * an earlier install turned on goes back to the manifest's off at the next start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AudienceNetworkTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        HookStatus.clear();
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static ComponentName component(String name) {
        return new ComponentName(context().getPackageName(), name);
    }

    @Test
    public void facebooksRequestToTurnOneOnKeepsTheManifestsOff() {
        int on = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        int manifest = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        for (String name : AudienceNetwork.COMPONENTS) {
            assertEquals(name, manifest, AudienceNetwork.kept(component(name), on));
            assertEquals(name, off, AudienceNetwork.kept(component(name), off));
        }
        assertEquals("another component", on, AudienceNetwork.kept(component("com.facebook.katana.LoginActivity"), on));
        assertEquals("nothing named", on, AudienceNetwork.kept(null, on));
        String report = HookStatus.report("").toString();
        assertTrue(report, report.contains(FamilyNames.AUDIENCE_NETWORK + ": invoked 12"));
        assertTrue(report, report.contains(AudienceNetwork.KEPT_OFF + " 5"));
    }

    @Test
    public void theStartupTasksCallStillReachesAndroid() {
        PackageManager manager = context().getPackageManager();
        ComponentName remote = component(AudienceNetwork.COMPONENTS[0]);
        AudienceNetwork.componentState(manager, remote, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, manager.getComponentEnabledSetting(remote));
        AudienceNetwork.componentState(manager, remote, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(remote));
    }

    @Test
    public void aComponentFoundOnAtStartGoesBackOff() {
        PackageManager manager = context().getPackageManager();
        ComponentName activity = component(AudienceNetwork.COMPONENTS[3]);
        ComponentName service = component(AudienceNetwork.COMPONENTS[4]);
        ComponentName login = component("com.facebook.katana.LoginActivity");
        manager.setComponentEnabledSetting(activity, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(service, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(login, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        AudienceNetwork.switchOff(context());

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(activity));
        assertEquals("one already off stays as it is", PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                manager.getComponentEnabledSetting(service));
        assertEquals("Facebook's own", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(login));
        String report = HookStatus.report("").toString();
        assertTrue(report, report.contains(AudienceNetwork.SWITCHED_OFF + " 1"));
    }
}
