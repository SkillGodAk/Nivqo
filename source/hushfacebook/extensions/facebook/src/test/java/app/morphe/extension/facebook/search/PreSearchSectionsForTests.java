/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.search;

/** Asks the search screen hook about a For you entry the way Facebook's section would. */
public final class PreSearchSectionsForTests {
    private PreSearchSectionsForTests() {
    }

    /** Whether the For you entry is drawn as Facebook's empty row. */
    public static boolean drawsForYouEmpty() {
        PreSearchSectionsTest.Tree node = new PreSearchSectionsTest.Tree(PreSearchSections.NULL_STATE_NODE, true,
                PreSearchSections.FOR_YOU_MODULE);
        return PreSearchSections.entry(node, new Object()) == null;
    }
}
