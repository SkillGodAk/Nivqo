/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Facebook asking whether the account may use its Facebook Plus app icons: yes while the switch is
 * on, for the app icon benefit alone, and Facebook's own answer otherwise.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AppIconsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The patch is in Morphe Manager's default selection with its switch off; these tests turn it on. */
    @Before
    public void turnTheSwitchOn() {
        Settings.UNLOCK_APP_ICONS.save(true);
        Settings.USE_APP_ICON_IN_HEADER.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.UNLOCK_APP_ICONS.resetToDefault();
        Settings.USE_APP_ICON_IN_HEADER.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String counterLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(AppIcons.ROUTE + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndOnAnswersBothChecksYes() {
        assertFalse("the switch starts off", Settings.UNLOCK_APP_ICONS.defaultValue);
        assertEquals("the benefit name Facebook 582 checks", "CUSTOM_APP_ICON", AppIcons.BENEFIT);
        assertTrue(AppIcons.unlocked(AppIcons.BENEFIT));
        assertTrue(AppIcons.entitled(false));
        String line = counterLine();
        assertTrue(line, line.startsWith(AppIcons.ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: picker's benefit set"));
        assertTrue(line, line.contains("benefit check 1"));
        assertTrue(HookStatus.report("").toString(), HookStatus.report("").toString()
                .contains(FamilyNames.APP_ICONS + ": invoked"));
    }

    /** The provider answers every other benefit, Facebook's paid ones included, as Facebook does. */
    @Test
    public void everyOtherBenefitIsFacebooksAnswer() {
        assertFalse(AppIcons.unlocked("CUSTOM_STICKERS"));
        assertFalse(AppIcons.unlocked("custom_app_icon"));
        assertFalse(AppIcons.unlocked(""));
        assertFalse(AppIcons.unlocked(null));
        assertNull("other benefits aren't counted", counterLine());
    }

    /** An account that already has the benefit keeps it, whatever the switch says. */
    @Test
    public void facebooksYesStandsOffOrPaused() {
        Settings.UNLOCK_APP_ICONS.save(false);
        assertTrue(AppIcons.entitled(true));
        Settings.UNLOCK_APP_ICONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertTrue(AppIcons.entitled(true));
        assertNull("Facebook's own yes isn't counted", counterLine());
    }

    @Test
    public void offOrPausedFacebooksNoStands() {
        Settings.UNLOCK_APP_ICONS.save(false);
        assertFalse(AppIcons.unlocked(AppIcons.BENEFIT));
        assertFalse(AppIcons.entitled(false));
        assertEquals(AppIcons.ROUTE + ": 2 lists, 2 items, 0 removed", counterLine());
        Settings.UNLOCK_APP_ICONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(AppIcons.unlocked(AppIcons.BENEFIT));
        assertFalse(AppIcons.entitled(false));
        PauseForTests.resume();
        assertTrue(AppIcons.unlocked(AppIcons.BENEFIT));
    }

    private static ComponentName component(String className) {
        return new ComponentName(RuntimeEnvironment.getApplication().getPackageName(), className);
    }

    private static ComponentName enableIcon(String suffix, Drawable drawable) {
        Context context = RuntimeEnvironment.getApplication();
        ComponentName alias = component(AppIcons.LAUNCHER + "." + suffix);
        ShadowPackageManager packages = shadowOf(context.getPackageManager());
        packages.addActivityIfNotPresent(alias);
        packages.addActivityIcon(alias, drawable);
        context.getPackageManager().setComponentEnabledSetting(
                alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        return alias;
    }

    /** The update reset keeps the launcher entry and every alias of it, and nothing else. */
    @Test
    public void theUpdateResetKeepsTheLauncherEntryAndItsAliases() {
        assertEquals("Facebook 582's launcher entry", "com.facebook.katana.LoginActivity", AppIcons.LAUNCHER);
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER)));
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + ".vaporwave_ic")));
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + ".blue_fb")));
        assertTrue(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + ".katana")));
        String line = counterLine();
        assertTrue(line, line.startsWith(AppIcons.ROUTE + ": 4 lists, 4 items, 4 removed. Last reason: update reset"));

        assertFalse(AppIcons.keepsIcon(component(AppIcons.LAUNCHER + "Alias")));
        assertFalse(AppIcons.keepsIcon(component("com.facebook.katana.activity.FbMainTabActivity")));
        assertFalse(AppIcons.keepsIcon(component("com.facebook.composer.shareintent.AddToStoryAlias")));
        assertFalse(AppIcons.keepsIcon(null));
        assertTrue("other components aren't counted", counterLine().startsWith(AppIcons.ROUTE + ": 4 lists, 4 items, 4 removed"));
    }

    /** Off or paused, the manager's reset reaches the icons as it does in Facebook. Other components always reach it. */
    @Test
    public void theManagersCallGoesThroughUnlessAnIconIsKept() {
        PackageManager manager = RuntimeEnvironment.getApplication().getPackageManager();
        ComponentName alias = component(AppIcons.LAUNCHER + ".vaporwave_ic");
        ComponentName other = component("com.facebook.composer.shareintent.AddToStoryAlias");
        int off = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        AppIcons.componentState(manager, alias, off, PackageManager.DONT_KILL_APP);
        AppIcons.componentState(manager, other, off, PackageManager.DONT_KILL_APP);
        assertEquals("a picked icon stays", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(alias));
        assertEquals("another component is set", off, manager.getComponentEnabledSetting(other));

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        AppIcons.componentState(manager, alias, off, PackageManager.DONT_KILL_APP);
        assertEquals("paused, Facebook resets the icon", off, manager.getComponentEnabledSetting(alias));
        PauseForTests.resume();

        ComponentName entry = component(AppIcons.LAUNCHER);
        Settings.UNLOCK_APP_ICONS.save(false);
        AppIcons.componentState(manager, entry, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        assertEquals("off, Facebook resets the launcher entry", PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                manager.getComponentEnabledSetting(entry));
    }

    /**
     * Picking an icon turns the launcher entry off and the icon's alias on. The patches' own intents
     * named the entry and started nothing after that (#119), so they ask for the one that's on. A
     * launcher activity that only starts with the entry's name isn't one of its aliases.
     */
    @Test
    public void theLauncherEntryThatsOnIsThePickedIcons() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ComponentName entry = component(AppIcons.LAUNCHER);
        assertEquals("none listed: the entry Facebook ships", entry, AppIcons.launcher(context));

        ComponentName icon = component(AppIcons.LAUNCHER + ".vaporwave_ic");
        ComponentName lookalike = component(AppIcons.LAUNCHER + "Alias");
        ShadowPackageManager packages = shadowOf(context.getPackageManager());
        IntentFilter main = new IntentFilter(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ComponentName launcher : new ComponentName[] {lookalike, entry, icon}) {
            packages.addActivityIfNotPresent(launcher);
            packages.addIntentFilterForActivity(launcher, main);
        }
        PackageManager manager = context.getPackageManager();
        manager.setComponentEnabledSetting(icon, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        assertEquals("the default icon", entry, AppIcons.launcher(context));

        manager.setComponentEnabledSetting(icon, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        manager.setComponentEnabledSetting(entry, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        assertEquals("a picked icon", icon, AppIcons.launcher(context));
    }

    @Test
    public void anAlternateIconReplacesTheHeaderWordmarkAtItsExistingHeight() {
        Context context = RuntimeEnvironment.getApplication();
        Drawable stock = new ColorDrawable(Color.BLUE) {
            @Override
            public int getIntrinsicWidth() {
                return 160;
            }

            @Override
            public int getIntrinsicHeight() {
                return 40;
            }
        };
        Drawable picked = new BitmapDrawable(context.getResources(),
                Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888));
        enableIcon("vaporwave_ic", picked);
        ImageView logo = new ImageView(context);
        logo.setImageDrawable(stock);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        logo.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        logo.measure(unspecified, unspecified);
        int stockHeight = logo.getMeasuredHeight();

        AppIcons.dressLogo(logo);
        logo.measure(unspecified, unspecified);

        assertNotSame("the stock wordmark was replaced", stock, logo.getDrawable());
        assertEquals("the icon fits inside the wordmark's height", ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
        assertEquals("the header keeps Facebook's measured height", stockHeight, logo.getMeasuredHeight());
        assertEquals("the replacement reports the stock height", stock.getIntrinsicHeight(),
                logo.getDrawable().getIntrinsicHeight());
        assertEquals("the replacement is square", stock.getIntrinsicHeight(), logo.getDrawable().getIntrinsicWidth());
        logo.setColorFilter(Color.MAGENTA);
        assertNull("Facebook's wordmark tint does not recolor the app icon", picked.getColorFilter());
        assertTrue(counterLine(), counterLine().contains("Last reason: header logo"));
    }

    /** #172: the unlock alone no longer swaps the wordmark. The header choice is its own switch. */
    @Test
    public void theHeaderKeepsFacebooksWordmarkUntilItsOwnSwitchIsOn() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse("the header switch starts off", Settings.USE_APP_ICON_IN_HEADER.defaultValue);
        ImageView logo = new ImageView(context);
        Drawable stock = new ColorDrawable(Color.BLUE);
        logo.setImageDrawable(stock);
        enableIcon("vaporwave_ic", new ColorDrawable(Color.MAGENTA));

        Settings.USE_APP_ICON_IN_HEADER.save(false);
        AppIcons.dressLogo(logo);
        assertSame("a picked icon with the header switch off keeps the wordmark", stock, logo.getDrawable());
        String line = counterLine();
        assertTrue("nothing was replaced, so no header logo is counted: " + line,
                line == null || !line.contains("header logo"));

        Settings.USE_APP_ICON_IN_HEADER.save(true);
        AppIcons.dressLogo(logo);
        assertNotSame("the header switch on puts the icon in", stock, logo.getDrawable());

        Settings.UNLOCK_APP_ICONS.save(false);
        ImageView other = new ImageView(context);
        other.setImageDrawable(stock);
        AppIcons.dressLogo(other);
        assertSame("the header switch needs the unlock", stock, other.getDrawable());
    }

    @Test
    public void theHeaderSwitchNeverChangesTheLauncherAlias() {
        Context context = RuntimeEnvironment.getApplication();
        enableIcon("vaporwave_ic", new ColorDrawable(Color.MAGENTA));
        PackageManager manager = context.getPackageManager();
        ComponentName alias = component(AppIcons.LAUNCHER + ".vaporwave_ic");
        Settings.USE_APP_ICON_IN_HEADER.save(false);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(alias));
        Settings.USE_APP_ICON_IN_HEADER.save(true);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(alias));
    }

    @Test
    public void theNormalBlueIconAndNoPickKeepTheWordmark() {
        Context context = RuntimeEnvironment.getApplication();
        ImageView logo = new ImageView(context);
        Drawable stock = new ColorDrawable(Color.BLUE);
        logo.setImageDrawable(stock);
        logo.setScaleType(ImageView.ScaleType.MATRIX);

        AppIcons.dressLogo(logo);
        assertSame("the launcher entry means Facebook's normal icon", stock, logo.getDrawable());

        enableIcon("blue_fb", new ColorDrawable(Color.CYAN));
        AppIcons.dressLogo(logo);
        assertSame("the picker's normal blue icon keeps the wordmark", stock, logo.getDrawable());
        assertEquals(ImageView.ScaleType.MATRIX, logo.getScaleType());
    }

    @Test
    public void offPausedWrongViewOrMissingIconKeepsFacebooksHeader() {
        Context context = RuntimeEnvironment.getApplication();
        ImageView logo = new ImageView(context);
        Drawable stock = new ColorDrawable(Color.BLUE);
        logo.setImageDrawable(stock);
        enableIcon("vaporwave_ic", new ColorDrawable(Color.MAGENTA));

        Settings.UNLOCK_APP_ICONS.save(false);
        AppIcons.dressLogo(logo);
        assertSame("off", stock, logo.getDrawable());

        Settings.UNLOCK_APP_ICONS.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        AppIcons.dressLogo(logo);
        assertSame("paused", stock, logo.getDrawable());
        PauseForTests.resume();

        AppIcons.dressLogo(new View(context));
        assertSame("a non-image view is left alone", stock, logo.getDrawable());

        ComponentName missing = component(AppIcons.LAUNCHER + ".worldcup_ic");
        context.getPackageManager().setComponentEnabledSetting(
                missing, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        AppIcons.dressLogo(logo);
        assertSame("an icon Android cannot load leaves the wordmark", stock, logo.getDrawable());
    }
}
