package app.hushmessenger.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Opens and installs embedded HushMessenger entry points inside Messenger. */
public final class SettingsEntry {
    private static final String ENTRY_TAG = "hushmessenger_settings_entry";
    private static final String[] AFTER_ANCHORS = {
        "Photos and media", "Photos & media", "Photos and videos", "Photos & videos",
        "Media and content", "Photo and video content"
    };
    private static final String[] BEFORE_ANCHORS = {
        "Security", "Safety"
    };
    private static final Set<View> LOGO_HOOKED = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static volatile boolean lifecycleInstalled;

    private SettingsEntry() {}

    public static void installProcess(Context context) {
        if (context == null || lifecycleInstalled) return;
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) return;
        lifecycleInstalled = true;
        ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { install(activity); }
            @Override public void onActivityStarted(Activity activity) { install(activity); }
            @Override public void onActivityResumed(Activity activity) { install(activity); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    public static boolean open(Context context) {
        if (context == null) return false;
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), "app.hushmessenger.extension.SettingsActivity");
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            try {
                Toast.makeText(context, "HushMessenger settings couldn't open here.", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Opening the settings is best-effort; never let the host settings screen crash.
            }
            return false;
        }
    }

    public static void install(Context context) {
        Activity activity = activityFrom(context);
        if (activity == null) return;
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        install(root);
    }

    public static void install(View view) {
        if (view == null) return;
        View root = view.getRootView();
        if (root == null) root = view;
        schedule(root, 80L);
        schedule(root, 350L);
        schedule(root, 900L);
    }

    private static void schedule(final View root, long delayMs) {
        try {
            root.postDelayed(new Runnable() {
                @Override public void run() { tryInstall(root); }
            }, delayMs);
        } catch (Throwable ignored) {
            // Best-effort. Never break Messenger's own UI.
        }
    }

    private static void tryInstall(View root) {
        try {
            if (!(root instanceof ViewGroup)) return;
            installLogoLongPress(root);
            installSettingsRow(root);
        } catch (Throwable ignored) {
            // Target UI changes often. Failing to add an entry point must not crash Messenger.
        }
    }

    private static void installLogoLongPress(View root) {
        View logo = findMessengerLogo(root);
        if (logo == null || LOGO_HOOKED.contains(logo)) return;
        LOGO_HOOKED.add(logo);
        logo.setLongClickable(true);
        logo.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                return open(v.getContext());
            }
        });
    }

    private static View findMessengerLogo(View view) {
        if (view != null && view.isShown()) {
            CharSequence description = view.getContentDescription();
            if (looksLikeMessengerLogo(description == null ? null : description.toString())) return view;
            if (view instanceof TextView) {
                CharSequence cs = ((TextView) view).getText();
                if (looksLikeMessengerLogo(cs == null ? null : cs.toString())) return view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findMessengerLogo(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean looksLikeMessengerLogo(String value) {
        if (value == null) return false;
        String s = value.trim().toLowerCase(java.util.Locale.ROOT);
        return "messenger".equals(s) || s.contains("messenger");
    }

    private static void installSettingsRow(View root) {
        if (containsTag(root, ENTRY_TAG)) return;
        TextView after = findText(root, AFTER_ANCHORS);
        if (after != null && insertNear(after, true)) return;
        TextView before = findText(root, BEFORE_ANCHORS);
        if (before != null) insertNear(before, false);
    }

    private static boolean insertNear(TextView anchorText, boolean after) {
        View row = candidateRow(anchorText);
        if (row == null) return false;
        ViewGroup parent = parentOf(row);
        if (parent == null || !canAddInto(parent)) return false;
        if (containsTag(parent, ENTRY_TAG)) return true;
        int index = parent.indexOfChild(row);
        if (index < 0) return false;
        View entry = buildEntry(anchorText.getContext(), anchorText);
        parent.addView(entry, after ? index + 1 : index);
        return true;
    }

    private static View buildEntry(final Context context, TextView anchor) {
        SettingsText text = new SettingsText(context);
        int padH = Math.max(dp(context, 24), anchor.getPaddingLeft());
        int padV = Math.max(dp(context, 12), anchor.getPaddingTop());

        LinearLayout row = new LinearLayout(context);
        row.setTag(ENTRY_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(padH, padV, padH, padV);
        row.setMinimumHeight(dp(context, 64));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(context); }
        });

        TextView icon = new TextView(context);
        icon.setText("H");
        icon.setGravity(android.view.Gravity.CENTER);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setTextSize(22f);
        icon.setTextColor(anchor.getCurrentTextColor());
        row.addView(icon, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(context, 16), 0, 0, 0);

        TextView title = new TextView(context);
        title.setText(text.get("settings"));
        title.setTextColor(anchor.getCurrentTextColor());
        title.setTextSize(anchor.getTextSize() / context.getResources().getDisplayMetrics().scaledDensity);
        title.setTypeface(anchor.getTypeface());
        labels.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setText(text.get("access_help"));
        subtitle.setTextColor(anchor.getCurrentTextColor());
        subtitle.setAlpha(0.72f);
        subtitle.setTextSize(Math.max(12f, title.getTextSize() / context.getResources().getDisplayMetrics().scaledDensity - 3f));
        labels.addView(subtitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static boolean canAddInto(ViewGroup parent) {
        String name = parent.getClass().getName();
        if (name.contains("RecyclerView") || name.contains("ListView") || name.contains("AdapterView")) return false;
        return true;
    }

    private static View candidateRow(View view) {
        View current = view;
        View best = view;
        int maxDepth = 0;
        while (current != null && current.getParent() instanceof ViewGroup && maxDepth++ < 8) {
            ViewGroup parent = (ViewGroup) current.getParent();
            if (current.getHeight() >= dp(current.getContext(), 40) || current.isClickable()) best = current;
            if (parent.getChildCount() > 1 && current.getWidth() > 0) return best;
            current = parent;
        }
        return best;
    }

    private static ViewGroup parentOf(View view) {
        return view != null && view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
    }

    private static TextView findText(View view, String[] needles) {
        if (view instanceof TextView && view.isShown()) {
            CharSequence cs = ((TextView) view).getText();
            String s = cs == null ? "" : cs.toString().trim();
            for (String needle : needles) {
                if (s.equalsIgnoreCase(needle) || s.contains(needle)) return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), needles);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsTag(View view, String tag) {
        Object current = view.getTag();
        if (tag.equals(current)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsTag(group.getChildAt(i), tag)) return true;
            }
        }
        return false;
    }

    private static Activity activityFrom(Context context) {
        Context current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}