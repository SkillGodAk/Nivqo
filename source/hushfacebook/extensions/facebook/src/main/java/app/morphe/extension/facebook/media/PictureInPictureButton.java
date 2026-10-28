/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.media;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A small button over the screen Picture-in-picture has armed the window on, whose tap opens the
 * window right away (#44). It is a plain view added to the activity's content frame, so it needs no
 * hook into Facebook's own layouts and is the same on every build. It is there while the window is
 * armed on the screen, the switch is on, no window is open, and the reel on screen is a shape the
 * window takes; otherwise it is taken off again.
 */
public final class PictureInPictureButton {
    private static final String FAMILY = FamilyNames.PICTURE_IN_PICTURE;

    /** Counted each time the button is put on a screen. */
    static final String BUTTON_ADDED = "button added";

    private static final int SIZE_DP = 44;
    private static final int MARGIN_DP = 6;

    private static WeakReference<Button> button = new WeakReference<>(null);

    private PictureInPictureButton() {
    }

    /**
     * Brings the button in line with the window's state, on the main thread. Called wherever that
     * state changes. Does nothing, and posts nothing, while the button is off and none is showing.
     */
    static void sync() {
        try {
            if (button.get() == null && PictureInPicture.buttonScreen() == null) return;
            Utils.runOnMainThread(PictureInPictureButton::apply);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "button", failure);
        }
    }

    /** Puts the button on the screen it belongs on and takes it off any other. Never throws. */
    static void apply() {
        try {
            Activity target = PictureInPicture.buttonScreen();
            Button current = button.get();
            if (current != null && (target == null || current.screen != target || current.getParent() == null)) {
                ViewGroup parent = (ViewGroup) current.getParent();
                if (parent != null) parent.removeView(current);
                button = new WeakReference<>(null);
                current = null;
            }
            if (target == null || current != null) return;
            ViewGroup content = target.findViewById(android.R.id.content);
            if (content == null) return;
            Button added = new Button(target);
            float density = target.getResources().getDisplayMetrics().density;
            int size = Math.round(SIZE_DP * density);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.START | Gravity.CENTER_VERTICAL);
            params.leftMargin = Math.round(MARGIN_DP * density);
            // Entering the window resizes the screen: the next layout takes the button off.
            added.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (l != ol || t != ot || r != or || b != ob) sync();
            });
            content.addView(added, params);
            button = new WeakReference<>(added);
            HookStatus.bound(FAMILY, "button");
            HookStatus.counted(FAMILY, BUTTON_ADDED);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "button", failure);
        }
    }

    /** The button on screen, or null. Tests only. */
    @Nullable
    static View showing() {
        return button.get();
    }

    /** The button's title, also its content description. */
    public static String label() {
        try {
            return L10n.t("Picture-in-picture button");
        } catch (Throwable failure) {
            return "Picture-in-picture button";
        }
    }

    /** A dark disc with a window inside a window, drawn with no resources. */
    static final class Button extends View {
        final Activity screen;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Button(Activity screen) {
            super((Context) screen);
            this.screen = screen;
            setContentDescription(label());
            setAlpha(0.75f);
            setClickable(true);
            setOnClickListener(v -> Utils.runOnMainThread(() -> PictureInPicture.enterFromButton(screen)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC000000);
            canvas.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, paint);
            paint.setColor(0xFFFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w / 22f));
            rect.set(w * 0.25f, h * 0.30f, w * 0.75f, h * 0.70f);
            canvas.drawRoundRect(rect, w / 20f, w / 20f, paint);
            paint.setStyle(Paint.Style.FILL);
            rect.set(w * 0.45f, h * 0.48f, w * 0.70f, h * 0.65f);
            canvas.drawRect(rect, paint);
        }
    }
}
