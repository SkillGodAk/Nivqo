/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package androidx.recyclerview.widget;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Stands in for AndroidX's list in tests. The extension doesn't compile against it and finds
 * Facebook's lists by this kept class name, so a view of this name is all a test needs.
 */
public class RecyclerView extends FrameLayout {
    private final boolean vertical;

    public RecyclerView(Context context, boolean vertical) {
        super(context);
        this.vertical = vertical;
    }

    @Override
    public boolean canScrollVertically(int direction) {
        return vertical;
    }
}
