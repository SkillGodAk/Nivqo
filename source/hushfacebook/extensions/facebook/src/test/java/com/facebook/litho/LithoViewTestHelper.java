package com.facebook.litho;

/** Stands in for Litho's test helper, whose name and viewToString Redex keeps. */
public final class LithoViewTestHelper {
    public static int calls;

    private LithoViewTestHelper() {
    }

    public static String viewToString(BaseMountingView view) {
        calls++;
        return view.description;
    }
}
