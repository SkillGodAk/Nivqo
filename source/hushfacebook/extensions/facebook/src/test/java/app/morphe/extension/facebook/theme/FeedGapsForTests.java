/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

import org.robolectric.RuntimeEnvironment;

/** Rows of a list for Lines between posts, also asked from tests in other packages. */
public final class FeedGapsForTests {
    private FeedGapsForTests() {
    }

    /** A canvas that keeps each rectangle drawn on it and its colour. */
    static final class RecordingCanvas extends Canvas {
        final List<RectF> rects = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();

        @Override
        public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            rects.add(new RectF(left, top, right, bottom));
            colours.add(paint.getColor());
        }
    }

    /**
     * A host that leaves its children where they're put, as a Litho host does: a FrameLayout would
     * lay its unmeasured children out again at no size on each layout call.
     */
    static final class Host extends ViewGroup {
        Host(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        }
    }

    /** A list, android:id/list as Facebook's feed is. */
    static FrameLayout list(Context context) {
        FrameLayout list = new FrameLayout(context);
        list.setId(android.R.id.list);
        return list;
    }

    /** A row of [height] px whose one child, its content, is [contentHeight] px from the top. */
    static Host row(Context context, int width, int height, int contentHeight) {
        Host row = new Host(context);
        View content = new View(context);
        row.addView(content);
        row.layout(0, 0, width, height);
        content.layout(0, 0, width, contentHeight);
        return row;
    }

    /** Draws [row]'s gap, as the hook does last in its dispatchDraw, and gives back the canvas. */
    static RecordingCanvas draw(ViewGroup row) {
        RecordingCanvas canvas = new RecordingCanvas();
        FeedGaps.drawRow(row, canvas);
        return canvas;
    }

    /**
     * Whether a feed row in a list gets its gap painted. The content stops 2 px short, under the
     * 4 dp cap at any density.
     */
    public static boolean drawsTheGap() {
        Context context = RuntimeEnvironment.getApplication();
        Host row = row(context, 1080, 1374, 1372);
        list(context).addView(row);
        return !draw(row).rects.isEmpty();
    }
}
