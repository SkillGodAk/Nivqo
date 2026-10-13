/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the progress bar's Reels hooks on every Facebook build the bundle declares: the scrubber's
 * bar info builder asking the extension whether a reel has a bar at all (issue #73), its
 * minimum length check answering yes while kept, so a short reel gets a bar, on the scrubber and on
 * the Reels tab's subclass overriding it (issue #73), its progress update
 * having Facebook's own time writer refresh the time label once the update has moved the bar (never
 * before its position checks, issue #120), the wait before its next update cut to a frame, the
 * short reel check answering no so the bar isn't faded out, the look selector reporting each look
 * and letting a look that shows the bar take the place of a parked hide (issue #73),
 * and the Reels footer's seek bar status starting and staying VISIBLE, so a playing reel's caption
 * sits above the bar (issue #73). Each hook asks the extension, a no lands on Facebook's code, and
 * nothing of Facebook's code moves. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class KeepProgressBarReelsFixtureTest {
    private companion object {
        const val GET_MAX = "Landroid/widget/ProgressBar;->getMax()I"
    }

    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private class Scrubber(val looks: ScrubberLooks, val scrubber: ClassDef, val progress: ProgressUpdate)

    private fun scrubber(bundle: File): Scrubber {
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val looks = vddScrubber(holders)
        val scrubber = holders.single { it.type == looks.type }
        val progress = progressUpdate(
            FixtureDex.classesHolding(bundle, SCRUBBER_UPDATE).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        assertEquals("${bundle.name}: the progress update isn't on the scrubber's base", scrubber.superclass, progress.base.type)
        return Scrubber(looks, scrubber, progress)
    }

    @Test
    fun `each declared build gives a short reel a bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val scrubber = scrubber(bundle).scrubber
        val check = minimumLengthCheck(scrubber)
        val where = "$name: ${scrubber.type}->${check.name}"
        assertTrue("$where doesn't load \"$LENGTH_CHECK_SURFACE\"", check.code().any { it.reference() == LENGTH_CHECK_SURFACE })
        // The hook borrows v0: this, the length and the boolean are the parameter registers.
        assertTrue("$where has no local register for the hook", check.implementation!!.registerCount - 3 >= 1)

        val context = PatchContexts.of(listOf(scrubber))
        val method = context.mutableClassDefBy(scrubber.type).methods.single { it.sameAs(check) }
        val original = method.code()
        method.longEnoughWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", BARS_EVERY_REEL, patched[0].reference())
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes answers true", 1, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: and returns it", Opcode.RETURN, patched[4].opcode)
        assertEquals("$where: from the same register", register, (patched[4] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build hooks the length checks a scrubber subclass overrides`() = bundles { bundle ->
        val name = bundle.name
        val scrubber = scrubber(bundle).scrubber
        val minimum = minimumLengthCheck(scrubber)
        val checks = listOf(minimum, shortReelCheck(scrubber))
        val superclasses = mutableMapOf<String, String?>()
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                superclasses[classDef.type] = classDef.superclass
                if (classDef.type != scrubber.type && classDef.methods.any { method -> checks.any { method.sameAs(it) } }) {
                    holders += ImmutableClassDef.of(classDef)
                }
            }
        }
        val overrides = lengthCheckOverrides(holders, scrubber.type, checks) { superclasses[it] }
        // #73: 582's Reels tab scrubber overrides both checks with a minute's minimum, so a reel under it had no
        // passive look, and the bar showed on every other such reel.
        assertEquals("$name: the checks a class below ${scrubber.type} overrides", checks.map { it.name }.toSet(),
            overrides.map { it.second.name }.toSet())
        val context = PatchContexts.of(holders.filter { holder -> overrides.any { it.first == holder.type } })
        for ((type, check) in overrides) {
            val where = "$name: $type->${check.name}"
            val method = context.mutableClassDefBy(type).methods.single { it.sameAs(check) }
            val original = method.code()
            val longEnough = check.sameAs(minimum)
            if (longEnough) method.longEnoughWhileKept() else method.notShortWhileKept()
            val patched = method.code()
            assertEquals("$where: the extension is asked first", if (longEnough) BARS_EVERY_REEL else NO_FADED_BAR,
                patched[0].reference())
            val register = (patched[1] as OneRegisterInstruction).registerA
            assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
            assertEquals("$where: a yes answers ${if (longEnough) 1 else 0}", if (longEnough) 1 else 0,
                (patched[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
            assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
        }
    }

    @Test
    fun `each declared build asks the extension whether a reel has a bar at all`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val base = found.progress.base
        val info = barInfo(base, minimumLengthCheck(found.scrubber))
        val where = "$name: ${base.type}->${info.builder.name}"
        val code = info.builder.code()
        val init = code[info.call] as RegisterRangeInstruction
        assertEquals("$where: the bar info's constructor", "${info.builder.returnType}->$BAR_INFO_INIT", code[info.call].reference())
        val start = init.startRegister
        // #73: the constructor's first flag is the one the scrubber reads before choosing a look, and a no is the
        // invisible look, so the bar never showed on such a reel.
        val selector = lookSelector(base, found.looks)
        val infoClass = FixtureDex.classes(bundle, setOf(info.builder.returnType))[info.builder.returnType]
            ?: error("$where: the bar info's class ${info.builder.returnType} isn't in the APK")
        val constructor = infoClass.methods.single {
            it.name == "<init>" && it.parameterTypes.joinToString("") == "ZZZILjava/lang/String;"
        }
        val hasField = constructor.code().first {
            it.opcode == Opcode.IPUT_BOOLEAN && (it as TwoRegisterInstruction).registerA == constructor.implementation!!.registerCount - 6 + 1
        }.reference()
        assertTrue("$where: nothing on the base reads the info's first flag and then picks a look",
            base.methods.any { method ->
                val calls = method.implementation?.instructions?.toList().orEmpty()
                calls.any { it.opcode == Opcode.IGET_BOOLEAN && it.reference() == hasField } &&
                    calls.any { it.reference()?.contains("->${selector.name}(Ljava/lang/Integer;)V") == true }
            })

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(info.builder) }
        val original = method.code()
        method.hasBarWhileKept(info.call)
        val patched = method.code()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        assertEquals("$where: Facebook's code up to the constructor stays", original.take(info.call).map { it.opcode },
            patched.take(info.call).map { it.opcode })
        assertEquals("$where: the info is still made right before", Opcode.NEW_INSTANCE, patched[info.call - 1].opcode)
        assertEquals("$where: the extension is asked", HAS_BAR, patched[info.call].reference())
        val ask = patched[info.call] as FiveRegisterInstruction
        assertEquals("$where: with the has-a-bar flag and the length", listOf(2, start + 1, start + 4),
            listOf(ask.registerCount, ask.registerC, ask.registerD))
        assertEquals("$where: its answer replaces the flag", Opcode.MOVE_RESULT, patched[info.call + 1].opcode)
        assertEquals("$where: in its own register", start + 1, (patched[info.call + 1] as OneRegisterInstruction).registerA)
        assertEquals("$where: the constructor and the rest stay", original.drop(info.call).map { it.opcode },
            patched.drop(info.call + 2).map { it.opcode })
        val flow = ControlFlow.of(method)
        val into = patched.indices.filter { info.call in flow.normal[it] }
        assertEquals("$where: something reaches the hook other than the new-instance", listOf(info.call - 1), into)
        assertTrue("$where: the hook is in a try block", flow.exceptional[info.call].isEmpty())
    }

    @Test
    fun `each declared build has Facebook's writer refresh the shown time once the bar has moved`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val looks = found.looks
        val progress = found.progress
        val base = progress.base
        val where = "$name: ${base.type}->${progress.update.name}"
        assertTrue("$where doesn't trace \"$SCRUBBER_UPDATE\"", progress.update.code().any { it.reference() == SCRUBBER_UPDATE })
        assertTrue("$name: ${base.type}->${progress.writeTime.name} sets no text",
            progress.writeTime.code().any { it.reference() == TEXT_VIEW_SET_TEXT })
        assertTrue("$name: the writer doesn't reach the views holder the time label is in",
            progress.writeTime.code().any { it.reference()?.startsWith(looks.labelField.substringBefore("->")) == true })
        // Issue #120: the writer moves the bar with no check of its own, so it may run only where Facebook's update
        // has found a usable position and length. The update reads the bar's maximum only past both checks.
        assertTrue("$name: the writer no longer moves the bar", progress.writeTime.code().any { it.reference() == SET_PROGRESS })
        val at = dragCheck(progress.update, progress)
        val inflow = ControlFlow.of(progress.update)
        val update = progress.update.code()
        val maxReads = update.indices.filter { update[it].reference() == GET_MAX }.toSet()
        assertTrue("$where reads no bar maximum", maxReads.isNotEmpty())
        val reached = mutableSetOf(0)
        val queue = ArrayDeque(listOf(0))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next in maxReads) continue
            (inflow.normal[next] + inflow.exceptional[next]).forEach { if (reached.add(it)) queue += it }
        }
        assertTrue("$where reaches its drag check without reading the bar's maximum, before its position checks", at !in reached)

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(progress.update) }
        val original = method.code()
        method.refreshesTimeWithBar(progress, looks)
        val patched = method.code()
        val local = method.localRegisterCount()
        assertEquals("$where gains nine instructions", original.size + 9, patched.size)
        assertEquals("$where: Facebook's code up to the drag check stays", original.take(at).map { it.opcode },
            patched.take(at).map { it.opcode })
        assertEquals("$where: the scrubber moves into a local through the wide form", Opcode.MOVE_OBJECT_FROM16, patched[at].opcode)
        val register = (patched[at] as TwoRegisterInstruction).registerA
        val bar = (patched[at + 3] as TwoRegisterInstruction).registerA
        val live = RegisterLiveness.of(progress.update).liveInto(at)
        for (borrowed in listOf(register, bar)) {
            assertTrue("$where: the hook writes v$borrowed, which isn't a low local", borrowed < minOf(local, 16))
            assertTrue("$where: the hook borrows v$borrowed, which Facebook reads on", borrowed !in live)
        }
        assertNotEquals("$where: the bar and the label share a register", register, bar)
        assertEquals("$where: from p0", local, (patched[at] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the views holder is read", looks.viewsField, patched[at + 1].reference())
        assertEquals("$where: a missing holder goes on to Facebook", Opcode.IF_EQZ, patched[at + 2].opcode)
        assertEquals("$where: then the bar", looks.barField, patched[at + 3].reference())
        assertEquals("$where: off the views holder", register, (patched[at + 3] as TwoRegisterInstruction).registerB)
        assertEquals("$where: then the time label", looks.labelField, patched[at + 4].reference())
        assertEquals("$where: the extension is asked about the label and its bar", REFRESHES_TIME, patched[at + 5].reference())
        val ask = patched[at + 5] as FiveRegisterInstruction
        assertEquals("$where: in that order", listOf(2, register, bar), listOf(ask.registerCount, ask.registerC, ask.registerD))
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 6].opcode)
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[at + 7].opcode)
        assertEquals("$where: a yes runs Facebook's writer", "${base.type}->${progress.writeTime.name}(${base.type})V",
            patched[at + 8].reference())
        val call = patched[at + 8] as RegisterRangeInstruction
        assertEquals("$where: on this scrubber", listOf(local, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: Facebook's drag check and the rest stay", original.drop(at).map { it.opcode },
            patched.drop(at + 9).map { it.opcode })
        assertEquals("$where: the drag check comes right after the hook", Opcode.SGET_OBJECT, patched[at + 9].opcode)
        val flow = ControlFlow.of(method)
        assertEquals("$where: a missing holder lands on the drag check", setOf(at + 3, at + 9), flow.normal[at + 2].toSet())
        assertEquals("$where: a no lands on the drag check", setOf(at + 8, at + 9), flow.normal[at + 7].toSet())
        val into = patched.indices.filter { at + 9 in flow.normal[it] }.toSet()
        assertEquals("$where: something jumps to the drag check past the hook", setOf(at + 2, at + 7, at + 8), into)
    }

    @Test
    fun `each declared build moves the kept bar about once a frame`() = bundles { bundle ->
        val name = bundle.name
        val progress = scrubber(bundle).progress
        val base = progress.base
        val delay = frameDelay(progress)
        val where = "$name: ${base.type}->${delay.name}"
        assertTrue("$where isn't static", AccessFlags.STATIC.isSet(delay.accessFlags))
        assertTrue("$where isn't asked by the progress update",
            progress.update.code().any { it.reference() == "${base.type}->${delay.name}(${base.type})J" })
        // The hook borrows v0 and v1 for the wide answer: the scrubber is the one parameter register.
        assertTrue("$where has no two local registers for the hook", delay.implementation!!.registerCount - 1 >= 2)

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(delay) }
        val original = method.code()
        method.smoothWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", SMOOTHS_BAR, patched[0].reference())
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register and the next, which aren't locals", register + 1 < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes waits a frame", SMOOTH_FRAME_DELAY_MS.toLong(), (patched[3] as WideLiteralInstruction).wideLiteral)
        assertEquals("$where: as a long", Opcode.CONST_WIDE_16, patched[3].opcode)
        assertEquals("$where: and returns it", Opcode.RETURN_WIDE, patched[4].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build keeps a short reel from the faded bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val scrubber = scrubber(bundle).scrubber
        val check = shortReelCheck(scrubber)
        val where = "$name: ${scrubber.type}->${check.name}"
        assertTrue("$where is the bar's own length check", check.name != minimumLengthCheck(scrubber).name)
        assertTrue("$where has no local register for the hook", check.implementation!!.registerCount - 3 >= 1)

        val context = PatchContexts.of(listOf(scrubber))
        val method = context.mutableClassDefBy(scrubber.type).methods.single { it.sameAs(check) }
        val original = method.code()
        method.notShortWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", NO_FADED_BAR, patched[0].reference())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes answers false", 0, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: and returns it", Opcode.RETURN, patched[4].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build reports the scrubber's look first in its selector`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val selector = lookSelector(found.progress.base, found.looks)
        val where = "$name: ${found.progress.base.type}->${selector.name}"
        // The looks it picks from: 0 passive, 1 and 3 active, 2 faded out, 4 invisible.
        val looks = selector.code().filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral }.toSet()
        assertTrue("$where no longer picks from looks 0 to 4: $looks", looks.containsAll(listOf(0, 1, 2, 3, 4)))

        val context = PatchContexts.of(listOf(found.progress.base))
        val method = context.mutableClassDefBy(found.progress.base.type).methods.single { it.sameAs(selector) }
        val original = method.code()
        method.countsLook()
        val patched = method.code()
        assertEquals("$where: the look goes to the extension first", COUNTS_LOOK, patched[0].reference())
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: from p1", listOf(method.localRegisterCount() + 1, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(1).map { it.opcode })
    }

    @Test
    fun `each declared build lets a look that shows the bar take a parked hide's place`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val base = found.progress.base
        val selector = lookSelector(base, found.looks)
        val where = "$name: ${base.type}->${selector.name}"
        val skip = sameLookSkip(selector) { FixtureDex.classes(bundle, setOf(it))[it] }
        // #73: the parked look is the state's second Integer, the one the selector stores the look it's asked for in
        // while a reel's player changes. A hide parked as one reel left landed on the next one after its own look
        // was skipped as the one the bar had.
        val state = FixtureDex.classes(bundle, setOf(skip.state)).getValue(skip.state)
        val init = state.methods.single { method ->
            method.name == "<init>" && method.parameterTypes.count { it.toString() == "Ljava/lang/Integer;" } == 2
        }
        val parameters = init.parameterTypes.map(CharSequence::toString)
        assertTrue("$where: the state's constructor takes a wide value", parameters.none { it == "J" || it == "D" })
        val second = init.implementation!!.registerCount - parameters.size + parameters.lastIndexOf("Ljava/lang/Integer;")
        val stored = init.code().single { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == second }
        assertEquals("$where: the parked look is the state's second Integer", skip.parked, stored.reference())

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(selector) }
        val original = method.code()
        method.replacesParkedLook(skip)
        val patched = method.code()
        val hook = patched.subList(skip.branch, skip.branch + 7)
        assertEquals("$where: the hook's code",
            listOf(Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT, Opcode.IF_NEZ),
            hook.map { it.opcode })
        assertEquals("$where: the hook reads the parked look", skip.parked, hook[3].reference())
        assertEquals("$where: and asks the extension", REPLACES_PARKED, hook[4].reference())
        val free = (hook[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$free, which isn't a local", free < method.localRegisterCount())
        assertTrue("$where: the hook writes v$free, which the selector reads later", free !in RegisterLiveness.of(selector).liveInto(skip.branch))
        val call = hook[4] as FiveRegisterInstruction
        assertEquals("$where: with the look asked for and the parked one", listOf(method.implementation!!.registerCount - 1, free),
            listOf(call.registerC, call.registerD))
        assertEquals("$where: Facebook's code stays", original.map { it.opcode },
            (patched.take(skip.branch) + patched.drop(skip.branch + 7)).map { it.opcode })
        assertEquals("$where: a no reaches the skip and a yes the code past it", setOf(skip.branch + 7, skip.branch + 8),
            ControlFlow.of(method).normal[skip.branch + 6].toSet())

        val extension = ExtensionDex.classDef(PROGRESS_BAR).methods.singleOrNull {
            "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == REPLACES_PARKED
        }
        assertTrue("$where: the extension has no $REPLACES_PARKED", extension != null && AccessFlags.STATIC.isSet(extension.accessFlags))
    }

    @Test
    fun `each declared build keeps a playing reel's caption above the bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val status = seekBarStatus(FixtureDex.classesHolding(bundle, STATUS_DRAGGING).filterNot { it.type.startsWith(EXTENSION_CLASSES) })
        val owners = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(owners) { owner -> owner.fields.any { it.type == status.type } }
        }
        val holder = statusHolder(owners, status)
        val owner = owners.single { it.type == holder.type }
        val where = "$name: ${holder.type}"
        val constructor = holder.constructor.code()
        assertEquals("$where doesn't start at $STATUS_HIDDEN", status.hidden, constructor[holder.initialStore - 1].reference())

        val context = PatchContexts.of(listOf(owner))
        val mutable = context.mutableClassDefBy(holder.type).methods
        val init = mutable.single { it.sameAs(holder.constructor) }
        init.startsLifted(holder, status)
        val started = init.code()
        val at = holder.initialStore + 1
        assertEquals("$where: Facebook's start stays", constructor.take(at).map { it.opcode }, started.take(at).map { it.opcode })
        assertEquals("$where: the extension is asked after the start is stored", LIFTS_FOOTER, started[at].reference())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, started[at + 2].opcode)
        assertEquals("$where: a yes loads $STATUS_VISIBLE", status.visible, started[at + 3].reference())
        assertEquals("$where: and stores it", holder.field, started[at + 4].reference())
        assertEquals("$where: Facebook's code after it stays", constructor.drop(at).map { it.opcode },
            started.drop(at + 5).map { it.opcode })

        val update = mutable.single { it.sameAs(holder.update) }
        val original = update.code()
        val store = holder.store
        update.staysLifted(holder, status)
        val patched = update.code()
        val free = (patched[store + 1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook borrows v$free, which it reads or Facebook does",
            free !in listOf(holder.new, holder.old, holder.self) && free !in RegisterLiveness.of(holder.update).liveInto(store + 1))
        assertEquals("$where: Facebook's code up to the store stays", original.take(store + 1).map { it.opcode },
            patched.take(store + 1).map { it.opcode })
        assertEquals("$where: $STATUS_HIDDEN is loaded to compare", status.hidden, patched[store + 1].reference())
        assertEquals("$where: with the new status", holder.new, (patched[store + 2] as TwoRegisterInstruction).registerA)
        assertEquals("$where: the extension is asked", LIFTS_FOOTER, patched[store + 3].reference())
        assertEquals("$where: $STATUS_VISIBLE replaces it", status.visible, patched[store + 6].reference())
        assertEquals("$where: in the new status' register", holder.new, (patched[store + 6] as OneRegisterInstruction).registerA)
        assertEquals("$where: and is stored", holder.field, patched[store + 7].reference())
        val unchanged = patched[store + 8] as TwoRegisterInstruction
        assertEquals("$where: compared with the old status", listOf(holder.old, holder.new), listOf(unchanged.registerA, unchanged.registerB))
        assertEquals("$where: which stops there", Opcode.RETURN_VOID, patched[store + 9].opcode)
        assertEquals("$where: Facebook's code after it stays", original.drop(store + 1).map { it.opcode },
            patched.drop(store + 10).map { it.opcode })
        assertTrue("$where: Facebook's Function1 call after the hook went missing",
            patched.drop(store + 10).any { it.reference() == FUNCTION1_INVOKE })
        val flow = ControlFlow.of(update)
        for (branch in listOf(store + 2, store + 5, store + 8)) {
            assertTrue("$where: a no at $branch doesn't land on Facebook's code", store + 10 in flow.normal[branch])
        }
        val into = patched.indices.filter { store + 1 in flow.normal[it] }.toSet()
        assertEquals("$where: something jumps past the hook", setOf(store), into)
    }
}
