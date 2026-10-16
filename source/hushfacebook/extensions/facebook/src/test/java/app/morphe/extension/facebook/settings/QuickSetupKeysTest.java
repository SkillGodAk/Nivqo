/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;

/** Quick setups' lists name real switches, none twice, and only the feature switches a settings file carries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class QuickSetupKeysTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void everyKeyIsASwitchInSettingsAndNoListIsEmpty() throws Exception {
        Set<BooleanSetting> declared = new HashSet<>();
        for (Field field : Settings.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())
                    && field.getType() == BooleanSetting.class) {
                declared.add((BooleanSetting) field.get(null));
            }
        }
        assertTrue("found too few switches to compare against: " + declared.size(), declared.size() > 20);
        for (List<BooleanSetting> list : Arrays.asList(QuickSetupKeys.QUIET, QuickSetupKeys.PRIVACY, QuickSetupKeys.DEFAULTS)) {
            assertFalse(list.isEmpty());
            assertEquals("a switch is listed twice: " + list, list.size(), new HashSet<>(list).size());
            for (BooleanSetting setting : list) {
                assertTrue(setting.key + " isn't one of Settings' switches", declared.contains(setting));
                assertSame(setting, Setting.getSettingFromPath(setting.key));
            }
        }
    }

    @Test
    public void quietAndPrivacyOnlyTurnFeatureSwitchesOn() {
        for (List<BooleanSetting> list : Arrays.asList(QuickSetupKeys.QUIET, QuickSetupKeys.PRIVACY)) {
            assertTrue("a choice reaches past the feature switches: " + list, SettingsBackup.ALLOWLIST.containsAll(list));
            assertFalse("a choice turns on Lock Facebook", list.contains(Settings.APP_LOCK));
        }
        for (Map<BooleanSetting, Boolean> choice : Arrays.asList(QuickSetupKeys.quiet(), QuickSetupKeys.privacy())) {
            for (Boolean value : choice.values()) assertTrue(value);
        }
    }

    @Test
    public void backToDefaultsLeavesTheLockAndTheScreensOwnSwitchesAlone() {
        Map<BooleanSetting, Boolean> defaults = QuickSetupKeys.defaults();
        // Every switch a settings file carries but Lock Facebook: one tap never takes the lock off.
        assertEquals(SettingsBackup.ALLOWLIST.size() - 1, defaults.size());
        assertTrue(SettingsBackup.ALLOWLIST.containsAll(defaults.keySet()));
        assertFalse(defaults.containsKey(Settings.APP_LOCK));
        for (Map.Entry<BooleanSetting, Boolean> value : defaults.entrySet()) {
            assertEquals(value.getKey().key, value.getKey().defaultValue, value.getValue());
        }
        // Pause, debug and the release check are nobody's quick setup.
        for (BooleanSetting kept : Arrays.asList(BaseSettings.PAUSED, BaseSettings.DEBUG, Settings.CHECK_FOR_RELEASES)) {
            assertFalse(kept.key, defaults.containsKey(kept));
        }
    }

    /** Hiding the home row is the person's own call: no choice, and no settings file, reaches it. */
    @Test
    public void hidingTheQuickSetupsRowIsInNoChoiceAndNoFile() {
        BooleanSetting hidden = BaseSettings.HIDE_QUICK_SETUPS;
        assertFalse(hidden.rebootApp);
        assertFalse(hidden.includeWithImportExport);
        assertTrue(hidden.isKeptWhenPaused());
        assertFalse(SettingsBackup.ALLOWLIST.contains(hidden));
        for (List<BooleanSetting> list : Arrays.asList(QuickSetupKeys.QUIET, QuickSetupKeys.PRIVACY, QuickSetupKeys.DEFAULTS)) {
            assertFalse(list.contains(hidden));
        }
        for (Map<BooleanSetting, Boolean> choice : Arrays.asList(
                QuickSetupKeys.quiet(), QuickSetupKeys.privacy(), QuickSetupKeys.defaults())) {
            assertFalse(choice.containsKey(hidden));
        }
    }
}
