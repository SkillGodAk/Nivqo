/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.freeLocalsAt
import app.morphe.patches.facebook.misc.extension.liveAcrossInjection
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Keep the progress bar"

/** The name the Reels viewer's bottom progress bar plugin gives itself, a kept literal. */
internal const val REEL_SEEK_BAR_PLUGIN = "FbShortsViewerBottomSeekBarPlugin"

/**
 * The trace names the unified video scrubber (the Reels viewer's bar on 581) gives its two looks:
 * full size, with the thumb showing, and the thin passive line. Kept literals, one per method.
 */
internal const val SCRUBBER_ACTIVE = "VDDScrubberPlugin.updateToActiveScrubber"
internal const val SCRUBBER_PASSIVE = "VDDScrubberPlugin.updateToPassiveScrubber"

/** The trace name of the scrubber base's progress update, a kept literal. */
internal const val SCRUBBER_UPDATE = "UDDScrubberBasePlugin.updateProgress"

/** The trace name of the scrubber base's light mount, run as it takes each video, a kept literal. */
internal const val SCRUBBER_LIGHT_MOUNT = "UDDScrubberBasePlugin.onLightMount"

/** The surface name both of the scrubber's `(IZ)Z` length checks compare first, a kept literal. */
internal const val LENGTH_CHECK_SURFACE = "ai_styles_drafts"

/** The names of the Reels footer's seek bar status values, kept enum literals (582 `LX/AGw;`). */
internal const val STATUS_HIDDEN = "HIDDEN"
internal const val STATUS_VISIBLE = "VISIBLE"
internal const val STATUS_DRAGGING = "DRAGGING"

internal const val FUNCTION1_INVOKE = "Lkotlin/jvm/functions/Function1;->invoke(Ljava/lang/Object;)Ljava/lang/Object;"

/** How long, in ms, the kept bar waits between moves: about a frame, where Facebook's default is 100. */
internal const val SMOOTH_FRAME_DELAY_MS = 16

internal const val TEXT_VIEW_SET_TEXT = "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V"

/** A kept class whose superclass is the full-screen video controls every such player builds on. */
internal const val FULLSCREEN_CONTROLS = "Lcom/facebook/feed/video/fullscreen/orion/FeedFullscreenVideoControlsPlugin;"

/** The constructor of a reel's bar info: has a bar, scrubbable, faded, length, video id (582 `LX/AGr;`). */
internal const val BAR_INFO_INIT = "<init>(ZZZILjava/lang/String;)V"

internal const val SEEK_BAR = "Landroid/widget/SeekBar;"
internal const val GET_PROGRESS = "Landroid/widget/ProgressBar;->getProgress()I"
internal const val SET_PROGRESS = "Landroid/widget/ProgressBar;->setProgress(I)V"
internal const val SET_ALPHA = "Landroid/graphics/drawable/Drawable;->setAlpha(I)V"
internal const val SET_ENABLED = "Landroid/view/View;->setEnabled(Z)V"
internal const val SEND_DELAYED = "Landroid/os/Handler;->sendEmptyMessageDelayed(IJ)Z"
internal const val REMOVE_MESSAGES = "Landroid/os/Handler;->removeMessages(I)V"

/** The name the newer player's video controls extension gives itself, a kept literal. */
internal const val CONTROLS_EXTENSION = "VideoControlsExtension"
internal const val POST_DELAYED = "Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z"
internal const val REMOVE_CALLBACKS = "Landroid/os/Handler;->removeCallbacksAndMessages(Ljava/lang/Object;)V"
internal const val RUNNABLE = "Ljava/lang/Runnable;"
internal const val PLAYER_CONTROL_UPDATE = "updateState:FbShortsVideoControlComponent.updatePlayerControl"

internal const val PROGRESS_BAR = "$EXTENSION_PACKAGE/media/ProgressBar;"
internal const val KEEPS_REEL_BAR = "$PROGRESS_BAR->keepsReelBar()Z"
internal const val CLEARS_LAST_REEL = "$PROGRESS_BAR->clearsLastReel()Z"
internal const val LIST_CLEAR = "Ljava/util/List;->clear()V"
internal const val SET_VISIBILITY = "Landroid/view/View;->setVisibility(I)V"
internal const val SET_VIEW_ALPHA = "Landroid/view/View;->setAlpha(F)V"
/** 1.0f's bits, the `const/high16` an alpha back to full loads. */
internal const val FULL_ALPHA = 0x3f800000
internal const val KEEPS_CONTROLS = "$PROGRESS_BAR->keepsControls()Z"
internal const val HIDE_TIME_LABEL = "$PROGRESS_BAR->hideTimeLabel(Landroid/view/ViewGroup;)V"
internal const val BARS_EVERY_REEL = "$PROGRESS_BAR->barsEveryReel()Z"
internal const val HAS_BAR = "$PROGRESS_BAR->hasBar(ZI)Z"
internal const val REFRESHES_TIME = "$PROGRESS_BAR->refreshesTime(Landroid/view/ViewGroup;Landroid/view/View;)Z"
internal const val SMOOTHS_BAR = "$PROGRESS_BAR->smoothsBar()Z"
internal const val LIFTS_FOOTER = "$PROGRESS_BAR->liftsFooter()Z"
internal const val NO_FADED_BAR = "$PROGRESS_BAR->noFadedBar()Z"
internal const val COUNTS_LOOK = "$PROGRESS_BAR->look(Ljava/lang/Integer;)V"
internal const val REPLACES_PARKED = "$PROGRESS_BAR->replacesParkedLook(Ljava/lang/Integer;Ljava/lang/Integer;)Z"
internal const val TAKES_TOUCH =
    "Lapp/morphe/extension/facebook/media/TapToSeek;->takesTouch(${SEEK_BAR}Landroid/view/MotionEvent;)Z"
internal const val VIEW_GROUP = "Landroid/view/ViewGroup;"

internal const val ON_TOUCH = "onTouchEvent(Landroid/view/MotionEvent;)Z"
internal const val ON_DRAW = "onDraw(Landroid/graphics/Canvas;)V"
internal const val INVALIDATE = "Landroid/view/View;->invalidate()V"

/**
 * Keeps a video's progress bar on screen, in the players that hide theirs a few seconds in.
 *
 * The unified video scrubber, which is the bar of 581's Reels viewer, has an "active" look (full
 * height track, thumb at full alpha) and a "passive" one (a 2 dp line, thumb hidden) that Facebook
 * switches to before a touch and again about a second after a drag ends. Both are `()V` methods
 * that begin by tracing their own name, [SCRUBBER_ACTIVE] and [SCRUBBER_PASSIVE] (582 `LX/9d9;`
 * `A19` and `A1B`, 581 `LX/RV4;` `A18` and `A19`, 580 `LX/Rln;` `A15` and `A16`, 577 `LX/SHD;` `A18` and `A19`). The extension
 * goes first in the passive one, and while the switch is on it runs the active one instead, then
 * hides the scrubber's time label and returns. The passive look hides that label (a ViewGroup
 * holding the elapsed and total time, `A09` of the scrubber's views holder) and the active one
 * shows it, and only a drag updates the times, so without the hide the label would sit frozen at
 * 0:00 over the reel's author row. A drag still shows it, as the touch path calls the active look
 * itself and the seek bar listener sets the label's visibility. The hook hides the "most replayed"
 * graph over the bar too, the way the passive look does: the active look draws it for the reel the
 * scrubber is on, and only the passive look takes it away, so one reel's graph stayed over the next
 * reel's bar until a drag. A drag shows it as before. Facebook also hands its one Reels scrubber
 * reel after reel without asking for a look again, so a hook in the scrubber base's light mount, on
 * its branch for another video, hides the graph and runs Facebook's own reset of the chapter marks
 * (582 `LX/9d9;->A0C`), which brings back the bar where the last reel's marks had drawn it (seen on
 * the emulator: a 2:15 clip's marks and graph on the 0:16 reel after it, which then had no bar).
 * With the time switch on, the label
 * stays instead, and the scrubber's progress update has Facebook's own time writer refresh it a few
 * times a second, which Facebook does only during a drag. That call goes where the update asks
 * whether you're dragging, which it reaches only after it has found the reel's position and length
 * usable and moved the bar. The writer checks neither and moves the bar too, so asked any earlier
 * it put the bar back at the start whenever the player wasn't ready, as on the first reel while
 * its player is handed over (issue #120). The active look starts with that writer too, so the hook
 * puts the bar back where it was after running it, as the passive look never moves the bar. Letting
 * go of a drag switches to the passive look just before Facebook reads the bar to seek, so on a reel
 * showing preview frames during the drag, whose player hasn't moved, the seek went back to where the
 * drag began, and where the reel's length wasn't known yet the bar went to the start.
 *
 * More hooks finish the Reels bar (582, issue #73). The scrubber's bar info says first whether a
 * reel has a bar at all, and Facebook says no for an ad, for what the viewer's config rules out and
 * for some reels it flags on its servers, so the info's builder asks the extension, and while kept
 * a reel with a known length gets one. The scrubber's minimum length check answers
 * yes, so a reel Facebook finds too short for a bar gets one. Its other length check, which gives a
 * short reel the look that fades the bar out (alpha 0) when a server flag is on, answers no. Both
 * hooks go on the Reels tab's scrubber too, which overrides the two checks. The scrubber's look
 * selector reports the looks that still hide the bar, for the diagnostic report, and skips a look
 * the bar has only when no hide is parked: a hide parked during a player change otherwise landed on
 * the next reel to reuse the scrubber.
 * And the wait before the scrubber's next progress update, Facebook's `defaultFrameDelayMs` of
 * 100 ms, drops to about a frame, so the bar moves smoothly instead of in visible steps.
 *
 * The Reels footer lays the caption out from a seek bar status (582 `LX/AGv;` holding a `LX/AGw;`):
 * HIDDEN while a reel plays, VISIBLE while it's paused, DRAGGING during a drag. Only VISIBLE lifts
 * the caption, by about 35 dp, above where the bar and its time go. While playing it sat laid out
 * for Facebook's 2 dp line, so the kept bar and its time landed on the caption. So the holder starts
 * at VISIBLE instead of HIDDEN, and its update stores VISIBLE where it would store HIDDEN and stops
 * there when VISIBLE was already the status. DRAGGING still applies, and the HIDDEN after a drag
 * becomes VISIBLE, so the footer doesn't stay in its drag layout.
 *
 * Older builds' Reels viewer used a bottom bar plugin, named [REEL_SEEK_BAR_PLUGIN], sizes its SeekBar with two
 * static (SeekBar, plugin) methods (581 `LX/8sg;->A00` and `A01`, still present on every declared
 * build): one shrinks it to a 2 dp line,
 * hides the thumb and turns drags off, the other makes it full size with the thumb at full alpha
 * and drags on. Facebook shrinks it when a reel's controls go away and when the reel plays on, so
 * the extension goes first in the shrinking one, and while the switch is on it calls the other
 * instead and returns.
 *
 * A full-screen video's controls are a plugin built on one class, the superclass of the kept
 * [FULLSCREEN_CONTROLS] (581 `LX/ReC;`), which also carries Orion, live and Watch and more
 * controls. Its one method that calls [SEND_DELAYED] (581 `A16`) sets the timer whose message fades
 * the controls out, each time they show and each time they're touched. The extension goes first
 * there, and while the switch is on no timer is set, so the controls and their bar stay until a tap
 * hides them the way it always has.
 *
 * The newer Litho player hides its controls through an extension named [CONTROLS_EXTENSION] (581
 * `LX/RbF;`). Its one method that drops its pending callbacks and posts a runnable (581 `A0N`) sets
 * the hide 3 seconds after the controls are touched, 10 with accessibility on. The same hook goes
 * first there.
 *
 * The player the video viewer's Enter fullscreen landscape mode opens on 577 to 581 runs on the
 * Reels controls component instead, whose updater traces [PLAYER_CONTROL_UPDATE] (581
 * `LX/87v;->Don`). A controller built with that updater's interface (581 `LX/87w;`) shows the
 * controls on a tap and posts its hide runnable (581 `LX/87x;`), whose `run()` asks the controller
 * to hide them about 3 seconds later. On the S25 (581, 2026-10-08) a stack trace of that hide went
 * from this `run()` through the controller to the updater, and none of the hooks above ran in that
 * player. So the same hook goes first in this `run()`. A tap still hides the controls, as the tap
 * handler calls the controller's hide itself.
 *
 * Tap the bar to seek (issue #125) is its own switch. The scrubber's SeekBar (582 `LX/9A0;`, found
 * from the base's `instance-of` on the bar it reads) runs one of two ways in its own
 * `onTouchEvent`, and a server setting picks which per account. Thumb-only turns away a press that
 * misses the thumb. Drag-anywhere hands every touch to Android's SeekBar, yet a tap there still
 * doesn't seek, as Facebook passes it on to the video. A drag that starts on the thumb seeks either
 * way. So the extension sees each touch first, holds a press that misses the thumb, and plays it
 * back to the handler as a drag from the thumb to where it lifted. On the emulator (2026-10-12)
 * the account ran drag-anywhere, where a hook at the thumb check never ran.
 *
 * In the default selection with its switch off: it only acts once the switch is turned on.
 */
@Suppress("unused")
val keepProgressBarPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Keep the progress bar",
    description = "Keeps every reel's progress bar full size and moving smoothly, with the time above it, so you can " +
        "drag it without tapping first. Also keeps a full-screen video's controls on screen until you tap, and " +
        "another switch lets a tap anywhere on the bar jump there. Starts off. Turn it on in Hushfacebook settings > " +
        "Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        val scrubber = vddScrubber(
            classDefByStrings(SCRUBBER_PASSIVE, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        // The older bottom bar plugin is kept hooked where a build still has it, but needn't exist.
        val pluginHolders = classDefByStrings(REEL_SEEK_BAR_PLUGIN, StringComparisonType.EQUALS)
            .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val plugin = if (pluginHolders.isEmpty()) null else reelSeekBarPlugin(pluginHolders)
        val sizes = plugin?.let { barSizes(it) }
        val controls = classDefByOrNull(FULLSCREEN_CONTROLS)?.superclass
            ?.let { classDefByOrNull(it) }
            ?: refuse("FeedFullscreenVideoControlsPlugin or the controls class it extends isn't in this APK")
        val timer = fadeTimer(controls)
        val (extension, hide) = extensionFadeTimer(
            classDefByStrings(CONTROLS_EXTENSION, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val updater = controlsUpdater(
            classDefByStrings(PLAYER_CONTROL_UPDATE, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        ) { classDefByOrNull(it) }
        val graph = graphHide(scrubber) { classDefByOrNull(it) }
        val scrubberClass = classDefByOrNull(scrubber.type) ?: refuse("${scrubber.type} went missing")
        val chapters = chapterReset(scrubberClass, scrubber)
        val lengthCheck = minimumLengthCheck(scrubberClass)
        val shortCheck = shortReelCheck(scrubberClass)
        val progress = progressUpdate(
            classDefByStrings(SCRUBBER_UPDATE, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        if (progress.base.type != scrubberClass.superclass || !scrubber.viewsField.startsWith("${progress.base.type}->")) {
            refuse("${progress.base.type}, which updates the progress, isn't the base holding ${scrubber.type}'s views")
        }
        progress.update.freeLocalsAt(PATCH, dragCheck(progress.update, progress), 2)
        val info = barInfo(progress.base, lengthCheck)
        val another = anotherVideo(progress.base, info.builder.returnType)
        val delay = frameDelay(progress)
        val selector = lookSelector(progress.base, scrubber)
        val skip = sameLookSkip(selector) { classDefByOrNull(it) }
        val status = seekBarStatus(
            classDefByStrings(STATUS_DRAGGING, StringComparisonType.EQUALS)
                .filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val controllers = mutableListOf<ClassDef>()
        val statusOwners = mutableListOf<ClassDef>()
        val checkHolders = mutableListOf<ClassDef>()
        val mountHolders = mutableListOf<ClassDef>()
        classDefForEach {
            if (it.type.startsWith(EXTENSION_CLASSES)) return@classDefForEach
            if (takesUpdater(it, updater)) controllers += it
            if (it.fields.any { field -> field.type == status.type }) statusOwners += it
            if (it.type != scrubber.type && it.methods.any { method -> method.sameAs(lengthCheck) || method.sameAs(shortCheck) }) {
                checkHolders += it
            }
            if (it.type != progress.base.type && it.methods.any { method -> method.sameAs(another.mount) }) mountHolders += it
        }
        val (hider, hiderRun) = controlsHideRunnable(controllers) { classDefByOrNull(it) }
        val footer = statusHolder(statusOwners, status)
        val overrides = lengthCheckOverrides(checkHolders, scrubber.type, listOf(lengthCheck, shortCheck)) {
            classDefByOrNull(it)?.superclass
        }
        val skippedMounts = mountOverridesSkippingBase(mountHolders, progress.base.type, another.mount) {
            classDefByOrNull(it)?.superclass
        }
        if (skippedMounts.isNotEmpty()) {
            refuse("${skippedMounts.joinToString()} override the light mount ${another.mount.name} without calling the base's, so the next reel's reset wouldn't run there")
        }
        val barType = scrubberSeekBar(progress.base, scrubber.barField)
        // The extension takes the bar as a SeekBar, and the verifier holds the class to that.
        if (!extendsType(barType, SEEK_BAR) { classDefByOrNull(it)?.superclass }) refuse("$barType, the scrubber's bar, isn't a SeekBar")
        val touch = barTouch(classDefByOrNull(barType) ?: refuse("$barType, the scrubber's SeekBar, isn't in this APK"))

        mutableClassDefBy(scrubber.type).methods.single { it.sameAs(scrubber.passive) }
            .activeInstead(scrubber, graph)
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(another.mount) }
            .clearsLastReel(scrubber, graph, chapters, another.at)
        mutableClassDefBy(scrubber.type).methods.single { it.sameAs(lengthCheck) }.longEnoughWhileKept()
        mutableClassDefBy(scrubber.type).methods.single { it.sameAs(shortCheck) }.notShortWhileKept()
        for ((type, check) in overrides) {
            val method = mutableClassDefBy(type).methods.single { it.sameAs(check) }
            if (check.sameAs(lengthCheck)) method.longEnoughWhileKept() else method.notShortWhileKept()
        }
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(info.builder) }.hasBarWhileKept(info.call)
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(progress.update) }
            .refreshesTimeWithBar(progress, scrubber)
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(delay) }.smoothWhileKept()
        // The skip's index is the unpatched selector's, so its hook goes in before the one at the top.
        mutableClassDefBy(progress.base.type).methods.single { it.sameAs(selector) }.apply {
            replacesParkedLook(skip)
            countsLook()
        }
        mutableClassDefBy(footer.type).methods.single { it.sameAs(footer.constructor) }.startsLifted(footer, status)
        mutableClassDefBy(footer.type).methods.single { it.sameAs(footer.update) }.staysLifted(footer, status)
        if (plugin != null && sizes != null) {
            mutableClassDefBy(plugin.type).methods.single { it.sameAs(sizes.shrink) }
                .fullSizeInstead(plugin.type, sizes.fullSize)
        }
        mutableClassDefBy(controls.type).methods.single { it.sameAs(timer) }.noTimerWhileKept()
        mutableClassDefBy(extension.type).methods.single { it.sameAs(hide) }.noTimerWhileKept()
        mutableClassDefBy(hider.type).methods.single { it.sameAs(hiderRun) }.noTimerWhileKept()
        mutableClassDefBy(barType).methods.single { it.sameAs(touch) }.takesTapsToSeek()
        enableStatus("keepProgressBar")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.calls(reference: String) = implementation?.instructions?.any {
    it.opcode.name.startsWith("invoke") && (it as ReferenceInstruction).reference.toString() == reference
} == true

/**
 * The Reels viewer's bottom progress bar plugin: the one class of [holders] with a `()String`
 * method loading [REEL_SEEK_BAR_PLUGIN], the name the plugin gives itself.
 */
internal fun reelSeekBarPlugin(holders: List<ClassDef>): ClassDef {
    val plugins = holders.filter { holder ->
        methodsHolding(holder, REEL_SEEK_BAR_PLUGIN).any {
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
    }.distinctBy { it.type }
    return plugins.singleOrNull()
        ?: refuse("expected one plugin naming itself \"$REEL_SEEK_BAR_PLUGIN\", found ${plugins.size}")
}

/** The unified scrubber's class and its two looks. */
internal class ScrubberLooks(
    val type: String,
    val active: Method,
    val passive: Method,
    /** The scrubber's field holding its views (582 `LX/9c4;->A0J`), and the time label field in that holder (`A09`). */
    val viewsField: String,
    val labelField: String,
    /** The SeekBar field in that holder (582 `LX/9DH;->A0A`). */
    val barField: String,
)

/**
 * The scrubber class: the one of [holders] with an instance `()V` method tracing
 * [SCRUBBER_ACTIVE] and another tracing [SCRUBBER_PASSIVE]. Refuses unless there's exactly one
 * such class with exactly one method of each.
 */
internal fun vddScrubber(holders: List<ClassDef>): ScrubberLooks {
    fun look(holder: ClassDef, string: String) = methodsHolding(holder, string).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.isEmpty()
    }
    val scrubbers = holders.distinctBy { it.type }.filter { look(it, SCRUBBER_ACTIVE).size == 1 && look(it, SCRUBBER_PASSIVE).size == 1 }
    val scrubber = scrubbers.singleOrNull()
        ?: refuse("expected one class tracing both \"$SCRUBBER_ACTIVE\" and \"$SCRUBBER_PASSIVE\", found ${scrubbers.size}")
    val passive = look(scrubber, SCRUBBER_PASSIVE).single()
    val (viewsField, labelField) = timeLabelFields(scrubber, passive)
    val barField = seekBarField(passive, labelField.substringBefore("->"))
    return ScrubberLooks(scrubber.type, look(scrubber, SCRUBBER_ACTIVE).single(), passive, viewsField, labelField, barField)
}

/**
 * The SeekBar field of the scrubber's views class [views], read from the passive look, which sizes
 * the bar: every `iget-object` there of a [SEEK_BAR] field that [views] declares. Refuses unless
 * that's exactly one field.
 */
internal fun seekBarField(passive: Method, views: String): String {
    val found = passive.implementation!!.instructions.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference)
            ?.takeIf { it.type == SEEK_BAR && it.definingClass == views }?.toString()
    }.distinct()
    return found.singleOrNull()
        ?: refuse("${passive.definingClass}->${passive.name} reads ${found.size} SeekBar fields off $views, expected the one bar")
}

/**
 * The time label's two fields, read from the passive look, which hides the label: the one
 * `iget-object` of a [VIEW_GROUP] field in it, straight after the `iget-object` of the scrubber's
 * own field that holds the views class declaring it. That field is declared on the scrubber, or on
 * the abstract base it extends: 582 moved the scrubber's fields and its looks' signatures to a base
 * (`LX/9c4;`, `LX/9d9;` overriding the looks), and its looks read the fields through the base.
 * Refuses unless that's exactly one field.
 */
internal fun timeLabelFields(scrubber: ClassDef, passive: Method): Pair<String, String> {
    val owners = setOf(scrubber.type) + listOfNotNull(scrubber.superclass)
    val code = passive.implementation!!.instructions.toList()
    val found = code.indices.mapNotNull { index ->
        val label = (code[index] as? ReferenceInstruction)?.takeIf { it.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference
        val views = (code.getOrNull(index - 1) as? ReferenceInstruction)?.takeIf { it.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference
        if (label != null && views != null && label.type == VIEW_GROUP && views.definingClass in owners &&
            views.type == label.definingClass
        ) {
            views.toString() to label.toString()
        } else {
            null
        }
    }.distinct()
    return found.singleOrNull()
        ?: refuse("${scrubber.type}->${passive.name} reads ${found.size} view group fields off the scrubber's views, expected the one time label")
}

/** The "most replayed" graph over the scrubber's bar: the views holder's field holding it, and the flag hiding it. */
internal class GraphHide(val graphField: String, val hiddenField: String)

/**
 * The "most replayed" graph (582 `LX/9DH;->A01`, a `LX/9DE;` built on `LX/ZHy;`) and its hidden
 * flag (`LX/ZHy;->A07`), read from the passive look, which hides it: of the boolean flags the look
 * sets on a view right before redrawing it, the one whose class's own `onDraw` returns at once while
 * it's set, and the one field of the views holder the look reads whose type is that class or extends
 * it, looked up through [classOf]. The look also redraws the chapter marks over the bar (`LX/9DG;`)
 * after setting a flag, but their `onDraw` goes on drawing. Refuses unless there's one of each.
 */
internal fun graphHide(looks: ScrubberLooks, classOf: (String) -> ClassDef?): GraphHide {
    val where = "${looks.type}->${looks.passive.name}"
    val views = looks.labelField.substringBefore("->")
    val code = looks.passive.implementation!!.instructions.toList()
    val flags = code.indices.mapNotNull { index ->
        val store = code[index] as? TwoRegisterInstruction
        val redraw = code.getOrNull(index + 1) as? FiveRegisterInstruction
        if (store == null || code[index].opcode != Opcode.IPUT_BOOLEAN || redraw == null ||
            code[index + 1].opcode != Opcode.INVOKE_VIRTUAL || code[index + 1].reference() != INVALIDATE ||
            redraw.registerCount != 1 || redraw.registerC != store.registerB
        ) {
            null
        } else {
            (code[index] as ReferenceInstruction).reference as FieldReference
        }
    }.distinctBy { it.toString() }
    val hiding = flags.filter { skipsDrawing(classOf(it.definingClass), it.toString()) }
    val flag = hiding.singleOrNull()
        ?: refuse("$where sets ${hiding.size} flag(s) a view stops drawing on, expected the one hiding the most replayed graph")
    val graphs = code.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.IGET_OBJECT }?.reference as? FieldReference)
            ?.takeIf { it.definingClass == views && extendsType(it.type, flag.definingClass) { type -> classOf(type)?.superclass } }
            ?.toString()
    }.distinct()
    val graph = graphs.singleOrNull()
        ?: refuse("$where reads ${graphs.size} fields of $views holding a ${flag.definingClass}, expected the one graph")
    return GraphHide(graph, flag.toString())
}

/**
 * Whether [view]'s own [ON_DRAW] returns straight away while [flag] is set: an `iget-boolean` of it,
 * then an `if-nez` of that value jumping to a `return-void`.
 */
internal fun skipsDrawing(view: ClassDef?, flag: String): Boolean {
    val draw = view?.methods?.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == ON_DRAW && it.implementation != null
    } ?: return false
    val code = draw.implementation!!.instructions.toList()
    val flow = ControlFlow.of(draw)
    return code.indices.any { index ->
        val read = code[index] as? TwoRegisterInstruction
        val branch = code.getOrNull(index + 1) as? OneRegisterInstruction
        read != null && code[index].opcode == Opcode.IGET_BOOLEAN && code[index].reference() == flag &&
            branch != null && code[index + 1].opcode == Opcode.IF_NEZ && branch.registerA == read.registerA &&
            flow.normal[index + 1].any { it != index + 2 && code[it].opcode == Opcode.RETURN_VOID }
    }
}

/**
 * Facebook's reset of the chapter marks over the bar (582 `LX/9d9;->A0C`), which its chapter
 * callback runs for a reel with none: the one static `(scrubber)V` method of [scrubber] that clears
 * a list, sets a view's visibility (the marks go) and sets the alpha of a view to 1.0 after reading
 * the bar (the bar the marks drew over comes back, for the looks that show it). Applying marks
 * clears and hides too, but puts the bar's alpha at 0, so the 1.0 tells the two apart. Refuses
 * unless there's one, and when it's private, since the scrubber base runs it.
 */
internal fun chapterReset(scrubber: ClassDef, looks: ScrubberLooks): Method {
    val resets = scrubber.methods.filter { method ->
        val code = method.implementation?.instructions ?: return@filter false
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(scrubber.type) &&
            method.calls(LIST_CLEAR) && method.calls(SET_VISIBILITY) && method.calls(SET_VIEW_ALPHA) &&
            code.any { it.reference() == looks.barField } &&
            code.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == FULL_ALPHA }
    }
    val reset = resets.singleOrNull()
        ?: refuse("${scrubber.type} has ${resets.size} static method(s) clearing its chapter marks and showing the bar again, expected one")
    if (AccessFlags.PRIVATE.isSet(reset.accessFlags)) {
        refuse("${scrubber.type}->${reset.name}, the chapter reset, is private, so the scrubber base can't run it")
    }
    return reset
}

/** The scrubber base's light mount and the index where its branch for another video starts. */
internal class AnotherVideo(val mount: Method, val at: Int)

/**
 * The scrubber base's light mount (582 `LX/9c4;->A0m`), which Facebook runs each time it hands its
 * one Reels scrubber another reel (its `onLoad` runs once): the one instance method of [base] with a
 * single parameter tracing [SCRUBBER_LIGHT_MOUNT]. It compares the new video's id with the one in the
 * bar info it built last, a String field of [barInfo], through `areEqual`, and an `if-nez` of that
 * skips the reset of the bar's state for the same video. Returns the mount and the index right after
 * that `if-nez`, where only the branch for another video goes on. Refuses unless all that holds, with
 * no jump onto that index.
 */
internal fun anotherVideo(base: ClassDef, barInfo: String): AnotherVideo {
    val mounts = methodsHolding(base, SCRUBBER_LIGHT_MOUNT).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 1
    }
    val mount = mounts.singleOrNull()
        ?: refuse("${base.type} has ${mounts.size} method(s) tracing \"$SCRUBBER_LIGHT_MOUNT\", expected its one light mount")
    val where = "${base.type}->${mount.name}"
    val code = mount.implementation!!.instructions.toList()
    val compares = code.indices.filter { index ->
        val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        val result = code.getOrNull(index + 1) as? OneRegisterInstruction
        val branch = code.getOrNull(index + 2) as? OneRegisterInstruction
        code[index].opcode == Opcode.INVOKE_STATIC && call?.name == "areEqual" && call.returnType == "Z" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
            result != null && code[index + 1].opcode == Opcode.MOVE_RESULT && branch != null &&
            code[index + 2].opcode == Opcode.IF_NEZ && branch.registerA == result.registerA
    }
    val compare = compares.singleOrNull() ?: refuse("$where compares ${compares.size} pairs with areEqual and skips on a match, expected the video's id")
    val readsId = code.subList(0, compare).any {
        it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
            field.definingClass == barInfo && field.type == "Ljava/lang/String;"
        }
    }
    if (!readsId) refuse("$where no longer reads the video id off its bar info $barInfo before comparing")
    val at = compare + 3
    val flow = ControlFlow.of(mount)
    if (at >= code.size || code.indices.any { it != at - 1 && at in flow.normal[it] }) {
        refuse("$where has a jump onto its branch for another video")
    }
    return AnotherVideo(mount, at)
}

/**
 * The classes extending the scrubber [base] that override its light [mount] without an
 * `invoke-super` of it. Facebook runs the mount virtually, so the reset hooked into the base's
 * mount would be skipped for any of them. 582 has none, as no class reaching `LX/9c4;` declares
 * `A0m`. Uses [lengthCheckOverrides] to find the overrides among [candidates].
 */
internal fun mountOverridesSkippingBase(
    candidates: List<ClassDef>,
    base: String,
    mount: Method,
    superclassOf: (String) -> String?,
): List<String> = lengthCheckOverrides(candidates, base, listOf(mount), superclassOf).filterNot { (_, override) ->
    override.implementation!!.instructions.any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        (it.opcode == Opcode.INVOKE_SUPER || it.opcode == Opcode.INVOKE_SUPER_RANGE) && call?.name == mount.name &&
            call.returnType == mount.returnType &&
            call.parameterTypes.map(CharSequence::toString) == mount.parameterTypes.map(CharSequence::toString)
    }
}.map { it.first }

/**
 * The scrubber's instance `(IZ)Z` length checks loading [LENGTH_CHECK_SURFACE] that compare the
 * reel's length, their int parameter, first in an [opcode] against Facebook's minimum.
 */
private fun lengthChecks(scrubber: ClassDef, opcode: Opcode): List<Method> =
    methodsHolding(scrubber, LENGTH_CHECK_SURFACE).filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("I", "Z")
    }.filter { check ->
        val implementation = check.implementation!!
        // The parameters take the last three registers: this, the length, then the boolean.
        val length = implementation.registerCount - 2
        implementation.instructions.any { it.opcode == opcode && (it as TwoRegisterInstruction).registerA == length }
    }

/**
 * The scrubber's check that a reel is long enough for a bar (582 `LX/9d9;->A1O`). The scrubber has
 * two instance `(IZ)Z` length checks, each loading [LENGTH_CHECK_SURFACE] first. This one answers no
 * through an `if-lt` of the reel's length against Facebook's minimum, and its answer decides between
 * the passive look and no bar at all. Refuses unless there's exactly one such check.
 */
internal fun minimumLengthCheck(scrubber: ClassDef): Method {
    val minimum = lengthChecks(scrubber, Opcode.IF_LT)
    return minimum.singleOrNull()
        ?: refuse("${scrubber.type} has ${minimum.size} length check(s) failing a reel shorter than a minimum, expected one")
}

/**
 * The scrubber's other length check (582 `LX/9d9;->A1N`), which answers no through an `if-gt` of
 * the reel's length against the same minimum, so yes for a short reel, when a server flag is on.
 * The scrubber asks it first, and its yes gives the reel the look that sets the bar's alpha to 0,
 * so a short reel's bar showed for a moment and went. Refuses unless there's exactly one.
 */
internal fun shortReelCheck(scrubber: ClassDef): Method {
    val short = lengthChecks(scrubber, Opcode.IF_GT)
    return short.singleOrNull()
        ?: refuse("${scrubber.type} has ${short.size} length check(s) passing only a reel at most a minimum long, expected one")
}

/**
 * The overrides of the scrubber's length [checks] in classes extending it: each instance method of
 * [candidates] with a check's name and signature, on a class whose superclasses, looked up through
 * [superclassOf], reach [scrubber]. 582's Reels tab scrubber (`LX/9D6;`) extends `LX/9d9;` and
 * overrides both checks with its own minimum of about a minute, and the bar info builder asks the
 * checks virtually, so hooks on the scrubber's own checks never ran there. A reel under a minute
 * then had no passive look, and as the look selector skips a look equal to the current one, the
 * bar showed on every other such reel only (#73). Returns each override's class and method.
 * [mountOverridesSkippingBase] finds the light mount's overrides with it too.
 */
internal fun lengthCheckOverrides(
    candidates: List<ClassDef>,
    scrubber: String,
    checks: List<Method>,
    superclassOf: (String) -> String?,
): List<Pair<String, Method>> {
    fun extendsScrubber(type: String): Boolean {
        var current = superclassOf(type)
        var depth = 0
        while (current != null && depth++ < 64) {
            if (current == scrubber) return true
            current = superclassOf(current)
        }
        return false
    }
    return candidates.filter { it.type != scrubber && extendsScrubber(it.type) }.flatMap { holder ->
        holder.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
                checks.any { method.sameAs(it) }
        }.map { holder.type to it }
    }
}

/**
 * The scrubber base's look selector (582 `LX/9c4;->A1F`): the one instance `(Integer)V` method of
 * [base] calling both of [looks]' looks. Its Integer is the look, 0 passive, 1 or 3 active, 2 faded
 * out and 4 invisible. Refuses unless there's exactly one.
 */
internal fun lookSelector(base: ClassDef, looks: ScrubberLooks): Method {
    val selectors = base.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Integer;") &&
            listOf(looks.active, looks.passive).all { look ->
                method.implementation?.instructions?.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    it.opcode.name.startsWith("invoke") && call?.name == look.name && call.returnType == "V" &&
                        call.parameterTypes.isEmpty()
                } == true
            }
    }
    return selectors.singleOrNull()
        ?: refuse("${base.type} has ${selectors.size} (Integer)V method(s) calling both scrubber looks, expected one")
}

/**
 * The look selector's skip of a look the bar already has: the `if-eq` at [branch], the register
 * [flow] holding the selector's state flow there and its [read] of the state, the state class
 * [state] and its field [parked] holding the look Facebook parks while a reel's player changes
 * (582 `LX/9DA;->A02`).
 */
internal class SameLookSkip(val branch: Int, val flow: Int, val read: String, val state: String, val parked: String)

/**
 * The look selector's skip of a look the bar already has (582 `LX/9c4;->A1F`, its second branch):
 * the first `if-eq` of the look it's asked for, its parameter, and the look read straight off the
 * selector's state, which Facebook takes from a flow (an `invoke-interface` of a `()Object` method,
 * `move-result-object`, `check-cast` to the state class, `iget-object` of an Integer field). The
 * state class has two instance Integer fields, the look the bar has and the one parked to apply once
 * a reel's player change ends, which is the one the skip doesn't read. [stateClass] looks the state
 * class up. Refuses unless all that holds, the skip jumps to a `return-void`, only the instruction
 * before it leads onto it and the flow's register still holds the flow there.
 */
internal fun sameLookSkip(selector: Method, stateClass: (String) -> ClassDef?): SameLookSkip {
    val where = "${selector.definingClass}->${selector.name}"
    val code = selector.implementation!!.instructions.toList()
    val look = selector.implementation!!.registerCount - 1
    val branch = code.indices.firstOrNull { index ->
        code[index].opcode == Opcode.IF_EQ && (code[index] as TwoRegisterInstruction).let { it.registerA == look || it.registerB == look }
    } ?: refuse("$where has no if-eq of the look it's asked for")
    if (branch < 4) refuse("$where compares the look before reading its state")
    val read = code[branch - 4] as? FiveRegisterInstruction
    val readCall = (read as? ReferenceInstruction)?.reference as? MethodReference
    val result = code[branch - 3] as? OneRegisterInstruction
    val cast = code[branch - 2]
    val current = code[branch - 1]
    val currentField = (current as? ReferenceInstruction)?.reference as? FieldReference
    val state = cast.reference()
    val held = (current as? TwoRegisterInstruction)?.registerA
    val compared = (code[branch] as TwoRegisterInstruction).let { if (it.registerA == look) it.registerB else it.registerA }
    if (read == null || read.opcode != Opcode.INVOKE_INTERFACE || read.registerCount != 1 || readCall == null ||
        readCall.parameterTypes.isNotEmpty() || readCall.returnType != "Ljava/lang/Object;" ||
        result == null || code[branch - 3].opcode != Opcode.MOVE_RESULT_OBJECT ||
        cast.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != result.registerA ||
        current.opcode != Opcode.IGET_OBJECT || (current as TwoRegisterInstruction).registerB != result.registerA ||
        currentField == null || currentField.definingClass != state || currentField.type != "Ljava/lang/Integer;" ||
        held != compared || read.registerC == result.registerA || read.registerC == held
    ) {
        refuse("$where no longer reads the bar's look off its state right before comparing it with the one asked for")
    }
    val flow = ControlFlow.of(selector)
    val target = flow.normal[branch].firstOrNull { it != branch + 1 }
    if (target == null || code[target].opcode != Opcode.RETURN_VOID) refuse("$where no longer returns on a look the bar has")
    if (code.indices.any { it != branch - 1 && branch in flow.normal[it] }) refuse("$where has a jump onto its skip of a look the bar has")
    val integers = stateClass(state!!)?.fields
        ?.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/lang/Integer;" }
        ?: refuse("$where reads its look off $state, which isn't in this APK")
    val parked = integers.filter { it.name != currentField!!.name }
    if (integers.size != 2 || parked.size != 1) refuse("$state has ${integers.size} Integer fields, expected the look and the parked one")
    return SameLookSkip(branch, read.registerC, readCall.toString(), state, "$state->${parked.single().name}:Ljava/lang/Integer;")
}

/** The scrubber base's builder of a reel's bar info and the index of the info's constructor call in it. */
internal class BarInfo(val builder: Method, val call: Int)

/**
 * The scrubber base's builder of a reel's bar info (582 `LX/9c4;->A03`, building `LX/AGr;`): the one
 * static method of [base] that asks [lengthCheck] and returns an object it makes with a
 * [BAR_INFO_INIT] constructor. That constructor's first boolean says whether the reel has a bar at
 * all (582 `LX/AGr;->A04`), and on no the scrubber picks the invisible look before anything else.
 * Facebook works it out from the reel, and an ad, the viewer's config and a few kinds of reel it
 * flags on its servers get none, so a kept bar was missing from some reels (#73). The builder hands
 * the same flag to both length checks, which the hooks already answer while kept, on the scrubber
 * and on any class overriding them ([lengthCheckOverrides]). Returns the
 * builder and its constructor call. Refuses unless there's one builder making one such object, by
 * the range form, right after its `new-instance`, with no jump or try block onto the call, and
 * unless the flag it passes is the one the length check gets.
 */
internal fun barInfo(base: ClassDef, lengthCheck: Method): BarInfo {
    fun Method.asksLengthCheck() = implementation?.instructions?.any {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode.name.startsWith("invoke-virtual") && call != null && call.name == lengthCheck.name &&
            call.returnType == "Z" && call.parameterTypes.map(CharSequence::toString) == listOf("I", "Z")
    } == true
    val builders = base.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType.startsWith("L") && method.asksLengthCheck() &&
            method.calls("${method.returnType}->$BAR_INFO_INIT")
    }
    val builder = builders.singleOrNull()
        ?: refuse("${base.type} has ${builders.size} static method(s) asking its length check and building a bar info, expected one")
    val where = "${base.type}->${builder.name}"
    val code = builder.implementation!!.instructions.toList()
    val init = "${builder.returnType}->$BAR_INFO_INIT"
    val calls = code.indices.filter { code[it].opcode.name.startsWith("invoke") && code[it].reference() == init }
    val call = calls.singleOrNull() ?: refuse("$where builds its bar info ${calls.size} times, expected once")
    val range = code[call] as? RegisterRangeInstruction
        ?: refuse("$where no longer builds its bar info by the range form")
    val made = code.getOrNull(call - 1)
    if (made == null || made.opcode != Opcode.NEW_INSTANCE || made.reference() != builder.returnType ||
        (made as OneRegisterInstruction).registerA != range.startRegister
    ) {
        refuse("$where no longer makes its bar info right before building it")
    }
    val flow = ControlFlow.of(builder)
    if (code.indices.any { it != call - 1 && call in flow.normal[it] } || flow.exceptional[call].isNotEmpty()) {
        refuse("$where has a jump or a try block onto its bar info's constructor")
    }
    val has = range.startRegister + 1
    val asks = code.filter { it.opcode == Opcode.INVOKE_VIRTUAL && (it.reference()?.contains("->${lengthCheck.name}(IZ)Z") == true) }
    if (asks.isEmpty() || asks.any { (it as FiveRegisterInstruction).registerE != has }) {
        refuse("$where no longer hands its length check the bar info's first flag")
    }
    if (range.startRegister + 4 > 15) refuse("$where builds its bar info past v15")
    return BarInfo(builder, call)
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

/** The scrubber base's progress update and the method writing its time. */
internal class ProgressUpdate(val base: ClassDef, val update: Method, val writeTime: Method)

/**
 * The scrubber base's progress update (582 `LX/9c4;->A04`), the one static `(base)V` method of
 * [holders] tracing [SCRUBBER_UPDATE], and its time writer (`A06`), the one static `(base)V` method
 * of the same class that sets text. The writer puts the elapsed and total time into the time label
 * and moves the bar to where the reel is. Facebook's update only calls it while you drag. Refuses
 * unless there's exactly one of each.
 */
internal fun progressUpdate(holders: List<ClassDef>): ProgressUpdate {
    fun ClassDef.takesItself(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(type)
    val updates = holders.distinctBy { it.type }.flatMap { holder ->
        methodsHolding(holder, SCRUBBER_UPDATE).filter { holder.takesItself(it) }.map { holder to it }
    }
    val (base, update) = updates.singleOrNull()
        ?: refuse("expected one static method tracing \"$SCRUBBER_UPDATE\" on the class it takes, found ${updates.size}")
    val writers = base.methods.filter { base.takesItself(it) && it.calls(TEXT_VIEW_SET_TEXT) }
    val writer = writers.singleOrNull()
        ?: refuse("${base.type} has ${writers.size} static methods setting text, expected one, its time writer")
    return ProgressUpdate(base, update, writer)
}

/**
 * Where [update], the scrubber base's progress update, asks whether you're dragging: the
 * `sget-object` of the drag state right before the `if-ne` that skips the update's one call to its
 * time writer (582 `LX/9c4;->A04`, `sget-object v0, LX/0UA;->A01`). The update gets there only after
 * reading the reel's position and length, finding a position other than -1 and a length above 0,
 * and moving the bar. Returns that instruction's index. Refuses unless the writer is called once,
 * right after such a check.
 */
internal fun dragCheck(update: Method, progress: ProgressUpdate): Int {
    val base = progress.base.type
    val writer = "$base->${progress.writeTime.name}($base)V"
    val code = update.implementation!!.instructions.toList()
    val calls = code.indices.filter {
        code[it].opcode == Opcode.INVOKE_STATIC && (code[it] as ReferenceInstruction).reference.toString() == writer
    }
    val call = calls.singleOrNull()
        ?: refuse("$base->${update.name} calls its time writer ${calls.size} times, expected once, during a drag")
    val branch = code.getOrNull(call - 1)?.takeIf { it.opcode == Opcode.IF_NE } as? TwoRegisterInstruction
    val state = code.getOrNull(call - 2)?.takeIf { it.opcode == Opcode.SGET_OBJECT } as? OneRegisterInstruction
    if (branch == null || state == null || state.registerA !in setOf(branch.registerA, branch.registerB)) {
        refuse("$base->${update.name} no longer checks for a drag right before calling its time writer")
    }
    return call - 2
}

/**
 * The wait before the scrubber's next progress update (582 `LX/9c4;->A02`): the one static
 * `(base)J` method of the base that its update calls. The update hands that wait to a frame
 * callback that runs it again, and it's Facebook's `defaultFrameDelayMs` (100 ms on the S25), or
 * longer for a video over 30 seconds, so the bar moves in steps several times a second. Refuses
 * unless the update calls exactly one such method.
 */
internal fun frameDelay(progress: ProgressUpdate): Method {
    val base = progress.base
    val calls = progress.update.implementation!!.instructions.mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.takeIf { instruction.opcode == Opcode.INVOKE_STATIC }?.reference as? MethodReference)
            ?.takeIf {
                it.definingClass == base.type && it.returnType == "J" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(base.type)
            }
    }.distinctBy { it.toString() }
    val call = calls.singleOrNull()
        ?: refuse("${base.type}->${progress.update.name} asks ${calls.size} static methods for a wait, expected one")
    return base.methods.singleOrNull { it.name == call.name && it.returnType == "J" && it.parameterTypes.size == 1 }
        ?: refuse("$call, the wait before the next progress update, isn't declared on ${base.type}")
}

/** The Reels footer's seek bar status enum and its HIDDEN and VISIBLE values, as `class->name:type`. */
internal class SeekBarStatus(val type: String, val hidden: String, val visible: String)

/**
 * The seek bar status (582 `LX/AGw;`): the one enum of [holders] whose `<clinit>` builds exactly
 * three values, named [STATUS_HIDDEN], [STATUS_VISIBLE] and [STATUS_DRAGGING], each name the last
 * string loaded before the value's `sput-object`. Refuses unless there's exactly one.
 */
internal fun seekBarStatus(holders: List<ClassDef>): SeekBarStatus {
    fun values(enum: ClassDef): Map<String, String>? {
        if (enum.superclass != "Ljava/lang/Enum;") return null
        val code = enum.methods.singleOrNull { it.name == "<clinit>" }?.implementation?.instructions ?: return null
        var name: String? = null
        val values = mutableMapOf<String, String>()
        for (instruction in code) {
            val reference = (instruction as? ReferenceInstruction)?.reference
            if (reference is StringReference) name = reference.string
            if (instruction.opcode == Opcode.SPUT_OBJECT && reference is FieldReference && reference.type == enum.type) {
                values[name ?: return null] = reference.toString()
                name = null
            }
        }
        return values.takeIf { it.keys == setOf(STATUS_HIDDEN, STATUS_VISIBLE, STATUS_DRAGGING) }
    }
    val found = holders.distinctBy { it.type }.mapNotNull { enum -> values(enum)?.let { enum.type to it } }
    val (type, values) = found.singleOrNull()
        ?: refuse("expected one enum of $STATUS_HIDDEN, $STATUS_VISIBLE and $STATUS_DRAGGING, found ${found.size}")
    return SeekBarStatus(type, values.getValue(STATUS_HIDDEN), values.getValue(STATUS_VISIBLE))
}

/**
 * The class keeping the footer's status (582 `LX/AGv;`): [update], its one store of a new status
 * into [field] at [store], from register [new] into the object in [self], with the status before it
 * in [old]; and [constructor], whose one store of the starting status is at [initialStore].
 */
internal class StatusHolder(
    val type: String,
    val field: String,
    val update: Method,
    val store: Int,
    val new: Int,
    val old: Int,
    val self: Int,
    val constructor: Method,
    val initialStore: Int,
)

private fun Method.code() = implementation?.instructions?.toList().orEmpty()

private fun stores(method: Method, field: String) = method.code().withIndex().filter { (_, it) ->
    it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as? FieldReference)?.toString() == field
}.map { it.index }

/**
 * The status holder among [owners], the classes with a field of [status]' type: the one whose
 * instance `V` method stores a status into its own field once, hands that same register to
 * [FUNCTION1_INVOKE] after the store, and reads the field once before it, into a register no
 * instruction between the read and the store writes. Its one constructor stores the starting status
 * into that field once, with no branch anywhere in it. Refuses unless there's exactly one.
 */
internal fun statusHolder(owners: List<ClassDef>, status: SeekBarStatus): StatusHolder {
    val found = owners.distinctBy { it.type }.flatMap { owner ->
        owner.fields.filter { it.type == status.type && !AccessFlags.STATIC.isSet(it.accessFlags) }.flatMap { declared ->
            val field = "${owner.type}->${declared.name}:${status.type}"
            owner.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.name != "<init>" }
                .mapNotNull { update -> holderOf(owner, field, update) }
        }
    }
    return found.singleOrNull()
        ?: refuse("expected one class storing a ${status.type} and handing it to a Function1, found ${found.size}")
}

private fun holderOf(owner: ClassDef, field: String, update: Method): StatusHolder? {
    val code = update.code()
    val store = stores(update, field).singleOrNull() ?: return null
    val put = code[store] as TwoRegisterInstruction
    // `this` sits right after the locals.
    val self = update.localRegisterCount()
    if (put.registerB != self) return null
    val handed = code.drop(store + 1).any {
        it.opcode == Opcode.INVOKE_INTERFACE && (it as ReferenceInstruction).reference.toString() == FUNCTION1_INVOKE &&
            (it as FiveRegisterInstruction).registerCount == 2 && it.registerD == put.registerA
    }
    if (!handed) return null
    val read = code.withIndex().filter { (index, it) ->
        index < store && it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).toString() == field
    }.singleOrNull() ?: return null
    val old = (read.value as TwoRegisterInstruction).registerA
    val overwritten = code.subList(read.index + 1, store).any {
        val target = (it as? OneRegisterInstruction)?.registerA
        target != null && (it.opcode.setsRegister() && target == old || it.opcode.setsWideRegister() && target + 1 == old)
    }
    if (overwritten || old == put.registerA) return null
    val constructors = owner.methods.filter { it.name == "<init>" && stores(it, field).size == 1 }
    val constructor = constructors.singleOrNull() ?: return null
    if (constructor.code().any { it is OffsetInstruction }) return null
    return StatusHolder(owner.type, field, update, store, put.registerA, old, self, constructor, stores(constructor, field).single())
}

/** The plugin's two size methods. */
internal class BarSizes(val shrink: Method, val fullSize: Method)

/**
 * The plugin's static `(SeekBar, plugin)V` size methods, both setting the thumb's alpha and whether
 * the bar takes drags: the full-size one loads 255 for the alpha, the shrinking one doesn't.
 * Refuses unless there's exactly one of each.
 */
internal fun barSizes(plugin: ClassDef): BarSizes {
    val sizes = plugin.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(SEEK_BAR, plugin.type) &&
            method.calls(SET_ALPHA) && method.calls(SET_ENABLED)
    }
    val (fullSize, shrink) = sizes.partition { method ->
        method.implementation!!.instructions.any { (it as? WideLiteralInstruction)?.wideLiteral == 255L }
    }
    if (fullSize.size != 1 || shrink.size != 1) {
        refuse(
            "${plugin.type} has ${fullSize.size} method(s) making its SeekBar full size and ${shrink.size} " +
                "shrinking it, expected one of each",
        )
    }
    return BarSizes(shrink.single(), fullSize.single())
}

/**
 * The full-screen controls' fade timer: the one instance `()V` method of [controls] that calls
 * [SEND_DELAYED], after [REMOVE_MESSAGES] drops the timer already set.
 */
internal fun fadeTimer(controls: ClassDef): Method {
    val timers = controls.methods.filter { it.calls(SEND_DELAYED) }
    val timer = timers.singleOrNull()
        ?: refuse("${controls.type} sets a delayed message in ${timers.size} methods, expected one, its fade timer")
    if (AccessFlags.STATIC.isSet(timer.accessFlags) || timer.returnType != "V" || timer.parameterTypes.isNotEmpty() ||
        !timer.calls(REMOVE_MESSAGES)
    ) {
        refuse("${controls.type}->${timer.name}, its fade timer, is no longer an instance ()V that drops the old timer first")
    }
    return timer
}

/**
 * The newer player's controls hide: the one instance `V` method among [holders] (the classes that
 * load [CONTROLS_EXTENSION]) that drops its pending callbacks and posts a runnable.
 */
internal fun extensionFadeTimer(holders: List<ClassDef>): Pair<ClassDef, Method> {
    val timers = holders.flatMap { holder ->
        holder.methods.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.calls(POST_DELAYED) &&
                it.calls(REMOVE_CALLBACKS)
        }.map { holder to it }
    }
    return timers.singleOrNull()
        ?: refuse("expected one method of the classes loading \"$CONTROLS_EXTENSION\" to post the controls' hide, found ${timers.size}")
}

/**
 * The interface the landscape player's controls updater implements (581 `LX/9Wi;`): the one
 * interface, among those of [holders] found with [classOf], declaring an instance `V` method of
 * theirs that traces [PLAYER_CONTROL_UPDATE].
 */
internal fun controlsUpdater(holders: List<ClassDef>, classOf: (String) -> ClassDef?): String {
    val found = holders.distinctBy { it.type }.flatMap { holder ->
        methodsHolding(holder, PLAYER_CONTROL_UPDATE)
            .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
            .flatMap { update ->
                holder.interfaces.mapNotNull(classOf).filter { face -> face.methods.any { it.sameAs(update) } }
            }
    }.map { it.type }.distinct()
    return found.singleOrNull()
        ?: refuse("expected one interface for the controls updater tracing \"$PLAYER_CONTROL_UPDATE\", found ${found.size}")
}

/** Whether a constructor of [classDef] takes the controls updater, as their controller's does. */
internal fun takesUpdater(classDef: ClassDef, updater: String) = classDef.methods.any { method ->
    method.name == "<init>" && method.parameterTypes.any { it.toString() == updater }
}

/** The fields of [owner]'s own [RUNNABLE]s that [method] hands to an interface method with a delay. */
private fun delayedRunnables(owner: String, method: Method): Set<String> {
    val code = method.implementation?.instructions?.toList().orEmpty()
    return code.indices.mapNotNull { index ->
        val post = (code[index] as? ReferenceInstruction)?.takeIf { code[index].opcode == Opcode.INVOKE_INTERFACE }
            ?.reference as? MethodReference
        val field = (code.getOrNull(index - 1) as? ReferenceInstruction)?.takeIf { it.opcode == Opcode.IGET_OBJECT }
            ?.reference as? FieldReference
        field?.toString()?.takeIf {
            post != null && post.returnType == "V" && post.parameterTypes.map(CharSequence::toString) == listOf(RUNNABLE, "J") &&
                field.definingClass == owner && field.type == RUNNABLE
        }
    }.toSet()
}

private fun delayedRunnables(controller: ClassDef) = controller.methods.flatMap { delayedRunnables(controller.type, it) }.toSet()

/**
 * The landscape player's controls hide: of [controllers] (the classes taking the updater), the one
 * that posts one of its own runnables with a delay, that runnable's class, found with [classOf] as
 * the `new-instance` its constructor stores in the field, and its `run()V`, which asks that
 * controller to hide the controls through a method other than the one posting it.
 */
internal fun controlsHideRunnable(controllers: List<ClassDef>, classOf: (String) -> ClassDef?): Pair<ClassDef, Method> {
    val posting = controllers.distinctBy { it.type }.map { it to delayedRunnables(it) }.filter { it.second.isNotEmpty() }
    val (controller, fields) = posting.singleOrNull()
        ?: refuse("expected one class built with the controls updater to post a runnable with a delay, found ${posting.size}")
    val field = fields.singleOrNull()
        ?: refuse("${controller.type} posts ${fields.size} of its runnables with a delay, expected the one hide")
    val made = controller.methods.filter { it.name == "<init>" }.mapNotNull { init ->
        val code = init.implementation?.instructions?.toList().orEmpty()
        val put = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference.toString() == field }
        if (put < 0) return@mapNotNull null
        val register = (code[put] as TwoRegisterInstruction).registerA
        code.subList(0, put).lastOrNull { it.opcode == Opcode.NEW_INSTANCE && (it as OneRegisterInstruction).registerA == register }
            ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.distinct()
    val runnable = made.singleOrNull()?.let(classOf)?.takeIf { RUNNABLE in it.interfaces }
        ?: refuse("$field isn't filled with one Runnable of Facebook's by ${controller.type}'s constructor")
    val run = runnable.methods.singleOrNull { it.name == "run" && it.returnType == "V" && it.parameterTypes.isEmpty() }
        ?: refuse("${runnable.type}, the controls' hide runnable, has no run()V")
    val posters = controller.methods.filter { field in delayedRunnables(controller.type, it) }
    val asked = run.implementation!!.instructions.mapNotNull { instruction ->
        (instruction as? ReferenceInstruction)?.takeIf { instruction.opcode.name.startsWith("invoke") }?.reference as? MethodReference
    }.filter { it.definingClass == controller.type }
    if (asked.isEmpty() || asked.any { call -> posters.any { it.name == call.name && it.returnType == call.returnType } }) {
        refuse("${runnable.type}->run doesn't ask ${controller.type} to hide the controls")
    }
    return runnable to run
}

/**
 * First thing in the scrubber's passive look: while the extension keeps the bar, run the active
 * look on the same instance, hide the time label and the most replayed [graph] the way the passive
 * look does, and return. The graph is hidden by its own flag and redrawn; a graph the views holder
 * doesn't have yet is skipped.
 * Otherwise Facebook's own code runs from its first instruction. The range form names p0, which
 * can sit past v15 in these long methods, and so does the move that reads it into v0 (format 22x).
 *
 * The active look starts with Facebook's time writer, which moves the bar to the player's position,
 * or to the start when the reel's length isn't known yet. The passive look never moves the bar, so
 * the hook puts the bar back where it was. Letting go of a drag switches to the passive look just
 * before Facebook reads the bar to seek, and where the player hadn't followed the drag (a reel
 * showing preview frames instead) the seek went back to where the drag began (issue #120).
 */
internal fun MutableMethod.activeInstead(looks: ScrubberLooks, graph: GraphHide) {
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_REEL_BAR
            move-result v0
            if-eqz v0, :facebook
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            iget-object v0, v0, ${looks.barField}
            invoke-virtual { v0 }, $GET_PROGRESS
            move-result v1
            invoke-virtual/range { p0 .. p0 }, ${looks.type}->${looks.active.name}()V
            invoke-virtual { v0, v1 }, $SET_PROGRESS
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            iget-object v0, v0, ${looks.labelField}
            invoke-static { v0 }, $HIDE_TIME_LABEL
            move-object/from16 v0, p0
            iget-object v0, v0, ${looks.viewsField}
            iget-object v0, v0, ${graph.graphField}
            if-eqz v0, :kept
            const/4 v1, 0x1
            iput-boolean v1, v0, ${graph.hiddenField}
            invoke-virtual { v0 }, $INVALIDATE
            :kept
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * In the scrubber base's light mount, at the start of its branch for another video ([AnotherVideo.at]):
 * while the extension keeps the bar, run Facebook's [chapters] reset on a scrubber of [looks]' class
 * and hide the most replayed [graph] by its flag, then go on resetting the bar as always. Facebook
 * hands its one Reels scrubber reel after reel without asking for a look again, so the last reel's
 * graph and chapter marks, which only the passive look took away, stayed over the next reel's bar,
 * and where the marks had drawn the bar the next reel had none. The reset reads only what the
 * scrubber's constructor sets. A scrubber of another class, or a graph the views holder doesn't have,
 * is skipped. The hook borrows two low locals that nothing reads from there on.
 */
internal fun MutableMethod.clearsLastReel(looks: ScrubberLooks, graph: GraphHide, chapters: Method, at: Int) {
    val (v, flag) = freeLocalsAt(PATCH, at, 2)
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $CLEARS_LAST_REEL
            move-result v$v
            if-eqz v$v, :facebook
            move-object/from16 v$v, p0
            instance-of v$flag, v$v, ${looks.type}
            if-eqz v$flag, :facebook
            check-cast v$v, ${looks.type}
            invoke-static { v$v }, ${looks.type}->${chapters.name}(${looks.type})V
            move-object/from16 v$v, p0
            iget-object v$v, v$v, ${looks.viewsField}
            iget-object v$v, v$v, ${graph.graphField}
            if-eqz v$v, :facebook
            const/4 v$flag, 0x1
            iput-boolean v$flag, v$v, ${graph.hiddenField}
            invoke-virtual { v$v }, $INVALIDATE
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * First thing in the scrubber's minimum length check: while the extension keeps the bar, answer
 * yes, so a short reel gets the passive look (which the hook above turns into the active one)
 * instead of no bar. Otherwise Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.longEnoughWhileKept() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $BARS_EVERY_REEL
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x1
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * In the scrubber base's bar info builder, between the info's `new-instance` and its constructor
 * [call]: hand the extension the flag saying whether the reel has a bar, and the reel's length, and
 * pass on its answer in the flag's register. The flag is the constructor's first boolean, its length
 * the int, so the range's second and fifth registers. Nothing jumps between the two instructions.
 */
internal fun MutableMethod.hasBarWhileKept(call: Int) {
    val start = getInstruction<RegisterRangeInstruction>(call).startRegister
    addInstructions(
        call,
        """
            invoke-static { v${start + 1}, v${start + 4} }, $HAS_BAR
            move-result v${start + 1}
        """,
    )
}

/**
 * In the scrubber's progress update, in front of its [dragCheck], so only once Facebook has moved
 * the bar: read the time label and the bar off the scrubber the update takes, and when the extension
 * asks for the time, have Facebook's own writer refresh it, then go on to the drag check as always.
 * The extension also puts back a label Facebook left hidden (#73), which is why it gets the bar. A
 * views holder that isn't there yet goes straight on. The hook borrows two low locals that nothing
 * reads from there on.
 */
internal fun MutableMethod.refreshesTimeWithBar(progress: ProgressUpdate, looks: ScrubberLooks) {
    val at = dragCheck(this, progress)
    val (v, bar) = freeLocalsAt(PATCH, at, 2)
    val base = progress.base.type
    addInstructionsWithLabels(
        at,
        """
            move-object/from16 v$v, p0
            iget-object v$v, v$v, ${looks.viewsField}
            if-eqz v$v, :facebook
            iget-object v$bar, v$v, ${looks.barField}
            iget-object v$v, v$v, ${looks.labelField}
            invoke-static { v$v, v$bar }, $REFRESHES_TIME
            move-result v$v
            if-eqz v$v, :facebook
            invoke-static/range { p0 .. p0 }, $base->${progress.writeTime.name}($base)V
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * First thing in the wait before the scrubber's next progress update: while the extension keeps
 * the bar, answer [SMOOTH_FRAME_DELAY_MS], so the bar moves about once a frame instead of in steps.
 * Otherwise Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.smoothWhileKept() {
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $SMOOTHS_BAR
            move-result v0
            if-eqz v0, :facebook
            const-wide/16 v0, $SMOOTH_FRAME_DELAY_MS
            return-wide v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in the scrubber's short reel check: while the extension keeps the bar, answer no, so
 * a short reel isn't given the look that fades its bar out. Otherwise Facebook's own code runs from
 * its first instruction.
 */
internal fun MutableMethod.notShortWhileKept() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $NO_FADED_BAR
            move-result v0
            if-eqz v0, :facebook
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * In the look selector, in front of its skip of a look the bar already has: read the look Facebook
 * parked, and when the extension says the look asked for takes its place, go on past the skip, so
 * Facebook parks this look in its stead (or applies it again). Otherwise the skip runs as before.
 * One low local that nothing reads there carries the parked look and then the answer.
 */
internal fun MutableMethod.replacesParkedLook(skip: SameLookSkip) {
    val free = freeLocalsAt(PATCH, skip.branch, 1).single()
    val look = implementation!!.registerCount - 1
    if (look > 15 || skip.flow > 15) refuse("$definingClass->$name keeps its look or its state flow past v15")
    addInstructionsWithLabels(
        skip.branch,
        """
            invoke-interface { v${skip.flow} }, ${skip.read}
            move-result-object v$free
            check-cast v$free, ${skip.state}
            iget-object v$free, v$free, ${skip.parked}
            invoke-static { v$look, v$free }, $REPLACES_PARKED
            move-result v$free
            if-nez v$free, :replaces
        """,
        ExternalLabel("replaces", getInstruction(skip.branch + 1)),
    )
}

/** First thing in the scrubber's look selector: hand the look to the extension, which only counts it. */
internal fun MutableMethod.countsLook() {
    // The range form names any register, so p1 past v15 is fine.
    addInstructions(0, "invoke-static/range { p1 .. p1 }, $COUNTS_LOOK")
}

/**
 * Right after the status holder's constructor stores the starting status: while the extension
 * keeps the bar, store VISIBLE over it, so a reel's footer starts in its lifted layout. The
 * constructor has no branch, so nothing jumps past the code.
 */
internal fun MutableMethod.startsLifted(holder: StatusHolder, status: SeekBarStatus) {
    val at = holder.initialStore + 1
    val self = (getInstruction(holder.initialStore) as TwoRegisterInstruction).registerB
    val free = freeLocalsAt(PATCH, at, 1).single()
    if (self > 15) refuse("$definingClass->$name keeps itself past v15")
    addInstructionsWithLabels(
        at,
        """
            invoke-static { }, $LIFTS_FOOTER
            move-result v$free
            if-eqz v$free, :facebook
            sget-object v$free, ${status.visible}
            iput-object v$free, v$self, ${holder.field}
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * Right after the status holder's update stores a new status: when that's HIDDEN and the extension
 * keeps the bar, store VISIBLE instead, which the update then hands on as it would HIDDEN, and
 * return if VISIBLE was already the status. The instruction after the store isn't a branch target
 * on 582, and the code borrows one local nothing reads from there on that isn't the old status.
 */
internal fun MutableMethod.staysLifted(holder: StatusHolder, status: SeekBarStatus) {
    val at = holder.store + 1
    val registers = listOf(holder.new, holder.old, holder.self)
    // The old status is dead past the store on 582, so liveness alone could hand it out.
    val live = liveAcrossInjection(at)
    val free = (0 until minOf(localRegisterCount(), 16)).firstOrNull { it !in live && it !in registers }
        ?: refuse("$definingClass->$name has no local free after its status store besides $registers")
    if (registers.any { it > 15 }) refuse("$definingClass->$name keeps the status past v15: $registers")
    addInstructionsWithLabels(
        at,
        """
            sget-object v$free, ${status.hidden}
            if-ne v${holder.new}, v$free, :facebook
            invoke-static { }, $LIFTS_FOOTER
            move-result v$free
            if-eqz v$free, :facebook
            sget-object v${holder.new}, ${status.visible}
            iput-object v${holder.new}, v${holder.self}, ${holder.field}
            if-ne v${holder.old}, v${holder.new}, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(at)),
    )
}

/**
 * First thing in the method that shrinks the bar: while the extension keeps it, call [fullSize]
 * with the same two arguments and return, so the bar is made full size instead. Otherwise
 * Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.fullSizeInstead(plugin: String, fullSize: Method) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_REEL_BAR
            move-result v0
            if-eqz v0, :facebook
            invoke-static { p0, p1 }, $plugin->${fullSize.name}($SEEK_BAR$plugin)V
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * First thing in the fade timer: while the extension keeps the controls, return without setting
 * a timer. Otherwise Facebook's own code runs from its first instruction.
 */
internal fun MutableMethod.noTimerWhileKept() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEPS_CONTROLS
            move-result v0
            if-eqz v0, :facebook
            return-void
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}

/**
 * The scrubber's own SeekBar class (582 `LX/9A0;`): the type the scrubber [base] checks the bar
 * against with an `instance-of` straight after reading it from [barField]. The bar is inflated from
 * a layout, so this is where the code names it. Refuses unless that's exactly one type.
 */
internal fun scrubberSeekBar(base: ClassDef, barField: String): String {
    val types = base.methods.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { at ->
            val read = code[at].takeIf { it.opcode == Opcode.IGET_OBJECT && it.reference() == barField } ?: return@mapNotNull null
            val check = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.INSTANCE_OF } ?: return@mapNotNull null
            val bar = (read as TwoRegisterInstruction).registerA
            ((check as ReferenceInstruction).reference as TypeReference).type.takeIf { (check as TwoRegisterInstruction).registerB == bar }
        }
    }.distinct()
    return types.singleOrNull() ?: refuse("${base.type} checks the bar it reads from $barField against ${types.size} types, expected one")
}

/** Whether [type] is [ancestor] or extends it, through [superOf], which answers null past the APK's own classes. */
internal fun extendsType(type: String, ancestor: String, superOf: (String) -> String?): Boolean =
    generateSequence(type) { superOf(it) }.take(64).any { it == ancestor }

/** The bar's own [ON_TOUCH] in [bar] (582 `LX/9A0;->onTouchEvent`). Refuses when it has none. */
internal fun barTouch(bar: ClassDef): Method =
    bar.methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == ON_TOUCH && it.implementation != null
    } ?: refuse("${bar.type} has no $ON_TOUCH of its own")

/**
 * First in the bar's touch handler: the extension sees every touch, and a yes answers true without
 * running Facebook's code, so the bar keeps the gesture. A no runs the handler as it was, which is
 * also how the drag the extension plays back for a tap reaches it. Nothing has run yet at the
 * start, so the bar and the touch are still in p0 and p1. Refuses when a jump comes back to the
 * start, as the hook would run twice and the register it writes may be live there.
 */
internal fun MutableMethod.takesTapsToSeek() {
    val first = implementation!!.instructions[0] as BuilderInstruction
    if (first.location.labels.isNotEmpty()) refuse("$definingClass->$name has a jump back to its start")
    val register = freeLocalsAt(PATCH, 0, 1).single()
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p1 }, $TAKES_TOUCH
            move-result v$register
            if-eqz v$register, :facebook
            const/4 v$register, 0x1
            return v$register
        """,
        ExternalLabel("facebook", getInstruction(0)),
    )
}
