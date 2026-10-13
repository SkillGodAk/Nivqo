/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import com.facebook.graphservice.tree.TreeJNI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Default comment order as the request's constructor and the sort menu ask it, for tests in any package. */
public final class DefaultCommentOrderForTests {
    private DefaultCommentOrderForTests() {
    }

    /** Says the patch is in the build, or with null, asks SettingsStatus again. */
    public static void inBuild(Boolean inBuild) {
        DefaultCommentOrder.inBuildForTests = inBuild;
    }

    /** Chooses an order without saving one, or with null, reads the saved one again. */
    public static void order(CommentOrder order) {
        DefaultCommentOrder.orderForTests = order;
    }

    /** Forgets the picks and the log count, as a new process would. */
    public static void forget() {
        DefaultCommentOrder.forget();
    }

    /**
     * Asks the hook about an ordinary request for a post's comments, one that names no order, with
     * the patch in the build and Newest chosen. True when it names an order, which is the switch
     * changing what Facebook would have done.
     */
    public static boolean asksForTheChosenOrder() {
        Boolean before = DefaultCommentOrder.inBuildForTests;
        CommentOrder chosen = DefaultCommentOrder.orderForTests;
        DefaultCommentOrder.inBuildForTests = Boolean.TRUE;
        DefaultCommentOrder.orderForTests = CommentOrder.NEWEST;
        try {
            return DefaultCommentOrder.requestedOrder(null, "ZmVlZGJhY2s6MTAx", null) != null;
        } finally {
            DefaultCommentOrder.inBuildForTests = before;
            DefaultCommentOrder.orderForTests = chosen;
        }
    }

    /**
     * A post's feedback tree as the sort menu's header gets it: its id, and its answer's rendering
     * instance with the order Facebook [selected] and the orders [offered].
     */
    public static TreeJNI feedback(String post, String selected, String... offered) {
        List<TreeJNI> options = new ArrayList<>();
        for (String token : offered) options.add(new TreeJNI().with("intent_token", token));
        TreeJNI rendering = new TreeJNI()
                .with("selected_intent", new TreeJNI().with("intent_token", selected))
                .with("selectable_intents", options);
        return new TreeJNI().with("id", post).with("comment_rendering_instance", rendering);
    }

    /**
     * Asks the hooks about an ordinary request for a post's comments, with the patch in the build
     * and All comments chosen, then about the menu its answer draws, which offers All comments.
     * True when the menu was picked in, which is the switch changing what Facebook would have done.
     */
    public static boolean picksInTheMenu() {
        Boolean before = DefaultCommentOrder.inBuildForTests;
        CommentOrder chosen = DefaultCommentOrder.orderForTests;
        Executor main = DefaultCommentOrder.mainForTests;
        DefaultCommentOrder.inBuildForTests = Boolean.TRUE;
        DefaultCommentOrder.orderForTests = CommentOrder.ALL_COMMENTS;
        DefaultCommentOrder.mainForTests = Runnable::run;
        try {
            String post = "ZmVlZGJhY2s6MzAz";
            String all = CommentOrder.ALL_COMMENTS.token;
            DefaultCommentOrder.requestedOrder(null, post, null);
            List<Object> tapped = new ArrayList<>();
            Function1<Object, Unit> pick = token -> {
                tapped.add(token);
                return Unit.INSTANCE;
            };
            DefaultCommentOrder.sortMenu(feedback(post, CommentOrder.MOST_RELEVANT.token,
                    CommentOrder.MOST_RELEVANT.token, all), pick);
            return !tapped.isEmpty();
        } finally {
            DefaultCommentOrder.inBuildForTests = before;
            DefaultCommentOrder.orderForTests = chosen;
            DefaultCommentOrder.mainForTests = main;
        }
    }
}
