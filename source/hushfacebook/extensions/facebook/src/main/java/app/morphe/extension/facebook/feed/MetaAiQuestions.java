/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The row of Meta AI questions Facebook puts under some posts, like "How to earn Mythic
 * Achievements?" under a news link (issue #48). Facebook calls it a deep dive pill. One socket
 * draws every kind of pill: it goes through the pill plugins in a fixed order, asks each whether
 * it applies to the post, and draws the first one that does. Meta AI's questions are
 * GenAiDeepDivePillPlugin, a class name Facebook keeps and loads into the same register for each
 * plugin it asks about. With the switch on, the answer for Meta AI's plugin is a no, so the socket
 * goes on to the next plugin, and a post with only Meta AI's pill gets no row. The post's link card,
 * text and buttons are other parts of the post and stay.
 *
 * <p>A plugin that says yes but has no look of its own, like the default click handler, gets the
 * socket's default way of drawing a pill, which gives a pill typed meta_ai Meta AI's icon. So the
 * same row could come back through a later plugin whose check doesn't look at the type. With the
 * switch on, that way draws no pill typed meta_ai either.
 *
 * <p>The same questions also come as a row of chips under a post's photo, from Meta AI's deep dive
 * plugin in the post call-to-action selector, and under a post's caption, from the deep dive plugin
 * of the socket that collects what goes there. Neither goes through the pill socket. With the switch
 * on, the selector hears the chips don't apply and the caption's deep dive reads as missing.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is
 * Facebook's own.
 */
public final class MetaAiQuestions {
    /** The class name of Meta AI's pill plugin, as the socket names each plugin it asks about. */
    public static final String META_AI_PILL =
            "com.facebook.feed.plugins.attachments.deepdivepill.impl.genai.GenAiDeepDivePillPlugin";

    /** The type the socket's default way of drawing a pill gives Meta AI's icon to. */
    public static final String META_AI_TYPE = "meta_ai";

    /**
     * The class name of Meta AI's deep dive in the post call-to-action selector, the row of question
     * chips it can put under a post's photo, as the selector's table names it.
     */
    public static final String QUESTION_BUTTON =
            "com.facebook.feed.plugins.calltoaction.impl.genaideepdive.GenAiDeepDiveCtaPlugin";

    /** Counted each time the call-to-action selector is told Meta AI's question chips don't apply. */
    static final String BUTTON_HIDDEN = "Meta AI question chips under a post kept out";

    /** Counted each time the deep dive under a caption is answered as missing. */
    static final String CAPTION_HIDDEN = "Meta AI questions under a caption kept out";

    /** The member the report names once the call-to-action selector has asked about Meta AI's chips. */
    static final String BUTTON_CHECK = "question chip check";

    /** The member the report names once the caption deep dive's getter has been asked. */
    static final String CAPTION_CHECK = "caption deep dive";

    /** Counted under the patch's name each time Meta AI's question row is answered away. */
    static final String HIDDEN = "Meta AI question row kept out";

    /** Counted under the patch's name each time the default way is kept from drawing a Meta AI pill. */
    static final String DEFAULT_HIDDEN = "Meta AI pill drawn the default way kept out";

    /** The member the report names once the socket has asked about Meta AI's plugin. */
    static final String CHECK = "pill check";

    /** The member the report names once the socket's default way has drawn a pill. */
    static final String DEFAULT_PILL = "default pill";

    private static final String FAMILY = FamilyNames.META_AI_QUESTIONS;

    private static volatile boolean logged;

    private static volatile boolean loggedDefault;

    private static volatile boolean loggedButton;

    private static volatile boolean loggedCaption;

    private MetaAiQuestions() {
    }

    /**
     * The hook, after each of the socket's checks of whether a pill plugin applies to a post, handed
     * the check's answer as an int (a boolean register the verifier may type as int) and the name of
     * the plugin it asked about. Answers false for Meta AI's plugin while the switch is on, and the
     * check's own answer for every other plugin and otherwise.
     */
    public static boolean keep(int applies, Object plugin) {
        boolean answer = applies != 0;
        try {
            HookStatus.invoked(FAMILY);
            if (!META_AI_PILL.equals(plugin)) return answer;
            HookStatus.bound(FAMILY, CHECK);
            if (!answer || !Utils.settingsReady() || !Settings.HIDE_META_AI_QUESTIONS.get()) return answer;
            HookStatus.counted(FAMILY, HIDDEN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Meta AI questions: a post's row of Meta AI questions was kept out");
            }
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CHECK, failure);
            return answer;
        }
    }

    /**
     * The hook in the socket's default way of drawing a pill, handed the pill's type once every
     * icon has been picked. True drops the pill: one typed meta_ai while the switch is on, whichever
     * plugin let it through. Any other type, or none, is drawn as Facebook would.
     */
    public static boolean dropsDefaultPill(@Nullable String type) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, DEFAULT_PILL);
            if (!META_AI_TYPE.equals(type) || !Utils.settingsReady() || !Settings.HIDE_META_AI_QUESTIONS.get()) {
                return false;
            }
            HookStatus.counted(FAMILY, DEFAULT_HIDDEN);
            if (!loggedDefault) {
                loggedDefault = true;
                Logger.printDebug(() -> "Meta AI questions: a Meta AI pill the socket drew the default way was kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, DEFAULT_PILL, failure);
            return false;
        }
    }

    /**
     * The hook first thing in the post call-to-action selector's check of whether a plugin applies,
     * handed the name the selector's table gives that plugin. True makes the check answer no: Meta
     * AI's question chips while the switch is on, so the selector goes on to the post's next button,
     * if it has one. Every other plugin, or none, is left to Facebook.
     */
    public static boolean hidesQuestionButton(@Nullable String plugin) {
        try {
            HookStatus.invoked(FAMILY);
            if (!QUESTION_BUTTON.equals(plugin)) return false;
            HookStatus.bound(FAMILY, BUTTON_CHECK);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_QUESTIONS.get()) return false;
            HookStatus.counted(FAMILY, BUTTON_HIDDEN);
            if (!loggedButton) {
                loggedButton = true;
                Logger.printDebug(() -> "Meta AI questions: a row of Meta AI question chips under a post was kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, BUTTON_CHECK, failure);
            return false;
        }
    }

    /**
     * The hook first thing in the getter Facebook reads Meta AI's deep dive under a post's caption
     * through. The socket that collects what goes under a caption asks it whether to draw the
     * questions and the plugin asks it again to build them, and both stop at a null. True answers
     * null while the switch is on; false leaves the getter to Facebook.
     */
    public static boolean hidesQuestionsBelowCaption() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, CAPTION_CHECK);
            if (!Utils.settingsReady() || !Settings.HIDE_META_AI_QUESTIONS.get()) return false;
            HookStatus.counted(FAMILY, CAPTION_HIDDEN);
            if (!loggedCaption) {
                loggedCaption = true;
                Logger.printDebug(() -> "Meta AI questions: Meta AI's questions under a caption were kept out");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, CAPTION_CHECK, failure);
            return false;
        }
    }
}
