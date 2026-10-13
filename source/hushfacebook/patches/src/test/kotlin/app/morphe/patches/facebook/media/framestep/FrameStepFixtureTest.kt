/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.framestep

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.media.resume.TRACK_START
import app.morphe.patches.facebook.media.resume.TRACK_STOP
import app.morphe.patches.facebook.media.resume.releases
import app.morphe.patches.facebook.media.resume.safeSeeks
import app.morphe.patches.facebook.media.resume.trackers
import app.morphe.patches.facebook.media.taptoplay.GROOT_PLAY
import app.morphe.patches.facebook.media.taptoplay.grootPlays
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.reels.doubletap.GESTURE_REACTION
import app.morphe.patches.facebook.reels.doubletap.isGestureReactionComponent
import app.morphe.patches.facebook.reels.doubletap.mountedView
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame step's anchors on every Facebook build the bundle declares: FbGrootPlayer's start, stop,
 * release and static seek as Resume long videos finds them, the one small-seek check the static
 * seek asks, the one ViewGroup field its attach writes, and GestureReactionComponent's view with
 * the one touch listener its constructor sets. Then the patch itself, run on those classes: each
 * player hook first in its method on its own arguments, the small-seek check answering no while
 * stepping and otherwise running as Facebook wrote it, the touch hook first in onTouch on the view
 * and the event, and each stub reaching what it stands for. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FrameStepFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    @Test
    fun `each declared build has every anchor once, and the patch goes in on its own classes`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name
                val holders = FixtureDex.classesHolding(fixture, GROOT_PLAY).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
                val play = holders.flatMap(::grootPlays).single()
                val trigger = play.parameterTypes.single().toString()
                val owner = holders.single { it.type == play.definingClass }

                val safeSeek = safeSeeks(owner, trigger).single()
                val smallSeek = smallSeekChecks(owner, safeSeek)
                assertEquals("$name: small-seek checks the static seek asks", 1, smallSeek.size)
                assertTrue("$name: the small-seek check has no register of its own", smallSeek.single().localRegisterCount() >= 1)
                val attach = grootAttaches(owner)
                assertEquals("$name: attaches holding \"$GROOT_ATTACH\"", 1, attach.size)
                val viewField = playerViewFields(owner, attach.single())
                assertEquals("$name: ViewGroup fields the attach writes", 1, viewField.size)

                val components = FixtureDex.classesHolding(fixture, GESTURE_REACTION).filter(::isGestureReactionComponent)
                assertEquals("$name: $GESTURE_REACTION components", 1, components.size)
                val viewType = mountedView(components.single())
                assertTrue("$name: $GESTURE_REACTION's mount content isn't one view", viewType != null)
                val gestureView = FixtureDex.classes(fixture, setOf(viewType!!)).getValue(viewType)
                val candidates = FixtureDex.classes(fixture, touchListenerCandidates(gestureView).toSet())
                val listeners = candidates.values.filter { ON_TOUCH_LISTENER in it.interfaces }
                assertEquals("$name: touch listeners the gesture view sets", 1, listeners.size)
                val listener = listeners.single()
                assertEquals("$name: the listener's onTouch", 1, onTouches(listener).size)

                // The patch, on this build's own classes.
                val classes = FixtureDex.classes(fixture, setOf(trigger) + owner.interfaces)
                val context = PatchContexts.of(
                    listOf(owner, classes.getValue(trigger), components.single(), gestureView, listener,
                        ExtensionDex.classDef(FRAME_STEP), ExtensionDex.classDef(SETTINGS_STATUS)) +
                        owner.interfaces.mapNotNull { classes[it] },
                )
                frameStepPatch.execute(context)
                val patchedOwner = context.mutableClassDefBy(owner.type)
                fun patched(method: Method) = patchedOwner.methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                listOf(
                    Triple(trackers(owner, TRACK_START, trigger).single(), STARTED, 2),
                    Triple(trackers(owner, TRACK_STOP, trigger).single(), STOPPED, 2),
                    Triple(releases(owner, trigger).single(), RELEASED, 2),
                    Triple(safeSeek, SEEKING, 3),
                ).forEach { (original, hook, arguments) ->
                    val told = patched(original)
                    val code = told.implementation!!.instructions.toList()
                    assertEquals("$name: ${original.name}'s hook", hook, code[0].call.toString())
                    val first = told.localRegisterCount()
                    assertEquals("$name: ${original.name}'s hook reads its own arguments",
                        (first until first + arguments).toList(), code[0].registers())
                    assertEquals("$name: ${original.name} grew by the hook alone",
                        original.implementation!!.instructions.count() + 1, code.size)
                }

                val check = patched(smallSeek.single())
                val checkCode = check.implementation!!.instructions.toList()
                assertEquals("$name: the small-seek check's hook",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                    checkCode.take(5).map { it.opcode })
                assertEquals("$name: the small-seek check asks", STEPPING, checkCode[0].call.toString())
                assertEquals("$name: the answer register", 0, (checkCode[1] as OneRegisterInstruction).registerA)
                assertEquals("$name: the small-seek check grew by the hook alone",
                    smallSeek.single().implementation!!.instructions.count() + 5, checkCode.size)
                assertEquals("$name: Facebook's first instruction follows",
                    smallSeek.single().implementation!!.instructions.first().opcode, checkCode[5].opcode)
                assertEquals("$name: a no lands on Facebook's first instruction", setOf(3, 5),
                    ControlFlow.of(check).normal[2].toSet())

                val onTouch = context.mutableClassDefBy(listener.type).methods.single {
                    it.name == "onTouch" && it.returnType == "Z"
                }
                val touchCode = onTouch.implementation!!.instructions.toList()
                assertEquals("$name: onTouch's hook", TOUCH, touchCode[0].call.toString())
                val self = onTouch.localRegisterCount()
                assertEquals("$name: onTouch hands on the view and the event", listOf(self + 1, self + 2), touchCode[0].registers())
                assertEquals("$name: onTouch grew by the hook alone",
                    onTouches(listener).single().implementation!!.instructions.count() + 1, touchCode.size)

                val extension = context.mutableClassDefBy(FRAME_STEP)
                fun stub(stubName: String) = extension.methods.single { it.name == stubName }.implementation!!.instructions.toList()
                fun calledBy(stubName: String) = stub(stubName).mapNotNull { it.call }.first().toString()
                assertTrue("$name: position stub", calledBy(POSITION_STUB).startsWith("${owner.type}->"))
                assertTrue("$name: position stub reads an int", calledBy(POSITION_STUB).endsWith("()I"))
                assertEquals("$name: playing stub", "${owner.type}->isPlaying()Z", calledBy(PLAYING_STUB))
                assertTrue("$name: seek stub", calledBy(SEEK_STUB).endsWith("(${trigger}I)V"))
                val read = stub(VIEW_STUB).firstNotNullOf { ((it as? ReferenceInstruction)?.reference as? FieldReference) }
                assertEquals("$name: view stub", "${owner.type}->${viewField.single().name}:$VIEW_GROUP", read.toString())
                checked += version
            }
        }
        assertEquals("builds checked", versions, checked)
    }
}
