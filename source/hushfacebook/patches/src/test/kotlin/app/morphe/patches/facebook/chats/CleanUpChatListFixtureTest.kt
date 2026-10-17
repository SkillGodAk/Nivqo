/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.chats

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.namesString
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchors of Clean up Facebook's chat list on every declared Facebook build, then the patch on
 * those classes. The tile state: one class whose constructor checks `activeNowTiles` and stores an
 * ImmutableList, found in 577 (`E4x`), 580 (`EBq`) and 581 (`E7k`); the hook goes in front of the
 * store as range calls, because the list's register can sit past v15 elsewhere, and the test reads
 * the instructions back. The promotions: one plugin per tag, each with one show question and a
 * local to borrow; the hook goes first in the question. The Meta AI button: one search row builds
 * it with a tap action that opens Meta AI from the inbox, and the hook takes the built button
 * before the row's own null check. The search screen's Meta AI button: its tap names the row's
 * class, the row sets one flag to 1 once, and the hook takes the flag right after.
 */
class CleanUpChatListFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** Each if and goto as (its index, the index it jumps to), for stock and patched code alike. */
    private fun branches(code: List<Instruction>): List<Pair<Int, Int>> {
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        return code.indices.mapNotNull { at ->
            val instruction = code[at]
            if (instruction.opcode in PAYLOAD_JUMPS) return@mapNotNull null
            when (instruction) {
                is BuilderOffsetInstruction -> at to instruction.target.location.index
                is OffsetInstruction -> at to addresses.indexOf(addresses[at] + instruction.codeOffset)
                else -> null
            }
        }
    }

    private val PAYLOAD_JUMPS = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA)

    private fun Instruction.target() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun locals(method: Method): Int {
        val self = if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
        return method.implementation!!.registerCount - self - method.parameterTypes.sumOf {
            if (it.toString() == "J" || it.toString() == "D") 2 else 1
        }
    }

    private fun patched(context: BytecodePatchContext, method: Method): Method =
        context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.returnType == method.returnType &&
                it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
        }

    @Test
    fun `each declared build has one tile state and one plugin per promotion, and the patch hooks them`() {
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name

                // The tile state: two classes hold the literal, the state and its builder, and one is the state.
                val holders = FixtureDex.classesHolding(bundle, NOTES_TILES)
                assertEquals("$name: classes holding the tile list's name", 2, holders.size)
                val states = holders.mapNotNull { owner -> notesTrayOf(owner)?.let { owner to it } }
                assertEquals("$name: tile states", 1, states.size)
                val (state, store) = states.single()
                assertTrue("$name: the store's register is one an instruction can name", store.list in 0..255)
                assertTrue("$name: the constructor has a local for the answer", locals(store.method) >= 1)
                val builder = holders.single { it.type != state.type }
                assertTrue("$name: the builder is the constructor's parameter",
                    store.method.parameterTypes.single().toString() == builder.type)

                // The hook hands the list back through ImmutableList.copyOf(Collection), so every build has to carry it.
                val guava = FixtureDex.classes(bundle, setOf(IMMUTABLE_LIST_TYPE))[IMMUTABLE_LIST_TYPE]
                assertTrue("$name: Guava's ImmutableList is in the dex", guava != null)
                assertTrue("$name: ImmutableList.copyOf(Collection) is public static and returns an ImmutableList",
                    guava!!.methods.any {
                        it.name == "copyOf" && it.returnType == IMMUTABLE_LIST_TYPE &&
                            AccessFlags.STATIC.isSet(it.accessFlags) &&
                            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Collection;")
                    })

                // The promotions: one plugin per tag, distinct, each with one show question.
                val plugins = mutableListOf<ClassDef>()
                val questions = PROMOTION_TAGS.map { tag ->
                    val owners = FixtureDex.classesHolding(bundle, tag)
                    assertEquals("$name: classes loading \"$tag\"", 1, owners.size)
                    plugins += owners.single()
                    val question = bannerQuestion(owners.single(), tag)
                    assertTrue("$name: no show question for \"$tag\"", question != null)
                    question!!
                }
                assertEquals("$name: two plugins", 2, plugins.map { it.type }.toSet().size)

                // The Meta AI button: one class opens Meta AI from the search row, and one method of
                // the classes tagging the search box builds the button with that tap action.
                val openers = FixtureDex.classesHolding(bundle, META_AI_ICON_CLICKED).filter(::opensMetaAiFromSearchRow)
                assertEquals("$name: classes opening Meta AI from the search row", 1, openers.size)
                val rows = FixtureDex.classesHolding(bundle, SEARCH_BAR_TAG)
                val sites = rows.flatMap { owner -> owner.methods.mapNotNull { metaAiButtonSite(it, openers.map { it.type }.toSet()) } }
                assertEquals("$name: search rows building a Meta AI button", 1, sites.size)
                val site = sites.single()

                // The search screen's Meta AI button: one tap opens Meta AI from the people picker right
                // after reading its row's CallerContext, and one method of that row sets the flag to 1 once.
                // 583's 64-bit tap asks a string table for the entry, so the table's callers count too.
                val entryHolders = FixtureDex.classesHolding(bundle, PEOPLE_PICKER_ENTRY)
                val tables = entryHolders.associateBy { it.type }
                val resolve: (MethodReference) -> Method? = { call -> tables[call.definingClass]?.let { resolveStatic(it, call) } }
                val asking = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.definingClass in tables } }) {
                    callsMetaAiLauncher(it) && !holdsString(it, PEOPLE_PICKER_ENTRY) && namesString(it, PEOPLE_PICKER_ENTRY, resolve)
                }
                val pickerOpeners = (entryHolders + FixtureDex.classes(bundle, asking.map { it.definingClass }.toSet()).values)
                    .distinctBy { it.type }
                val pickers = pickerOpeners.mapNotNull { opener -> pickerRowOf(opener, resolve)?.let { opener to it } }
                assertEquals("$name: Meta AI taps opening from the people picker", 1, pickers.size)
                val (pickerOpener, pickerRowType) = pickers.single()
                val pickerRow = FixtureDex.classes(bundle, setOf(pickerRowType))[pickerRowType]
                assertTrue("$name: the picker row $pickerRowType is in the dex", pickerRow != null)
                val gates = pickerRow!!.methods.mapNotNull { pickerButtonGate(it, pickerOpener.type) }
                assertEquals("$name: picker row methods setting the Meta AI flag", 1, gates.size)
                val gate = gates.single()
                val pickerStock = gate.method.body()
                val tap = pickerStock.indexOfFirst {
                    it.opcode == Opcode.NEW_INSTANCE && it.target() == pickerOpener.type
                }
                val flag = gate.register
                val tests = (gate.set + 1 until tap).filter {
                    pickerStock[it].opcode == Opcode.IF_EQZ && (pickerStock[it] as OneRegisterInstruction).registerA == flag
                }
                assertEquals("$name: the flag gives the search box its end margin, then builds the button", 2, tests.size)
                assertEquals("$name: the gate is the button's test", gate.test, tests.last())
                // Up to the button's test, the flag is written twice: 0 at the start and Facebook's 1. So the
                // hook before that test only ever sees a 0 or a 1. Inside the button block Facebook reuses the
                // register for other values, which the hook never touches.
                val writes = (0 until tests.last()).filter {
                    pickerStock[it].opcode.setsRegister() && (pickerStock[it] as? OneRegisterInstruction)?.registerA == flag
                }
                assertEquals("$name: writes to the flag before the button's test", 2, writes.size)
                assertEquals("$name: the first puts 0", 0, (pickerStock[writes[0]] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$name: the second is Facebook's 1", gate.set, writes[1])

                // The patch on those classes.
                val context = PatchContexts.of(
                    (listOf(state) + plugins + openers + rows + pickerOpeners + pickerRow).distinctBy { it.type } +
                        ExtensionDex.classDef(SETTINGS_STATUS),
                )
                val before = store.method.body()
                cleanUpChatListPatch.execute(context)

                val after = patched(context, store.method).body()
                val at = store.store
                val where = "$name ${state.type} constructor"
                assertEquals("$where: four instructions in", before.size + 4, after.size)
                assertEquals("$where: stock code before the hook", before.subList(0, at).map { it.opcode }, after.subList(0, at).map { it.opcode })
                assertEquals("$where: the hook call", Opcode.INVOKE_STATIC_RANGE, after[at].opcode)
                assertEquals("$where: the hook", NOTES_TILES_HOOK, after[at].target())
                val hook = after[at] as RegisterRangeInstruction
                assertEquals("$where: one register handed over", 1, hook.registerCount)
                assertEquals("$where: the list's register", store.list, hook.startRegister)
                assertEquals("$where: the answer", Opcode.MOVE_RESULT_OBJECT, after[at + 1].opcode)
                assertEquals("$where: lands where the list was", store.list, (after[at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$where: back into an ImmutableList", Opcode.INVOKE_STATIC_RANGE, after[at + 2].opcode)
                assertEquals("$where: copyOf", IMMUTABLE_COPY_OF, after[at + 2].target())
                val copy = after[at + 2] as RegisterRangeInstruction
                assertEquals("$where: copyOf takes the answer", store.list, copy.startRegister)
                assertEquals("$where: copyOf's register count", 1, copy.registerCount)
                assertEquals("$where: the copy", Opcode.MOVE_RESULT_OBJECT, after[at + 3].opcode)
                assertEquals("$where: lands where the list was", store.list, (after[at + 3] as OneRegisterInstruction).registerA)
                assertEquals("$where: the store follows", Opcode.IPUT_OBJECT, after[at + 4].opcode)
                assertEquals("$where: stores the same register", store.list, (after[at + 4] as OneRegisterInstruction).registerA)
                assertEquals("$where: stock code after the hook", before.drop(at).map { it.target() }, after.drop(at + 4).map { it.target() })

                for (question in questions) {
                    val stock = question.body()
                    val hooked = patched(context, question).body()
                    val here = "$name ${question.definingClass}->${question.name}"
                    // A question with no local is copied with its parameters moved down; the hook still goes
                    // first, before those moves, where v0 is free.
                    val moves = hooked.size - stock.size - 5
                    assertTrue("$here: $moves preserved parameters", moves >= 0)
                    assertEquals("$here: moves only when it has no local", locals(question) < 1, moves > 0)
                    assertTrue("$here: a local register for the answer", locals(patched(context, question)) >= 1)
                    assertEquals("$here: the hook call", Opcode.INVOKE_STATIC, hooked[0].opcode)
                    assertEquals("$here: the hook", HIDES_PROMOTION, hooked[0].target())
                    assertEquals("$here: no arguments", 0, (hooked[0] as FiveRegisterInstruction).registerCount)
                    assertEquals("$here: the answer", Opcode.MOVE_RESULT, hooked[1].opcode)
                    assertEquals("$here: lands in v0", 0, (hooked[1] as OneRegisterInstruction).registerA)
                    assertEquals("$here: tested", Opcode.IF_EQZ, hooked[2].opcode)
                    // A "no" returns; a "yes" must land on the first of the stock code, which is the first
                    // preserved parameter move on a cloned method, never past it or back into the hook.
                    assertEquals("$here: a yes continues at the first stock instruction", 5,
                        (hooked[2] as BuilderOffsetInstruction).target.location.index)
                    assertEquals("$here: answers no", Opcode.CONST_4, hooked[3].opcode)
                    assertEquals("$here: returns it", Opcode.RETURN, hooked[4].opcode)
                    assertEquals("$here: stock code follows the moves", stock[0].opcode, hooked[5 + moves].opcode)
                }

                val rowStock = site.method.body()
                val rowHooked = patched(context, site.method).body()
                val r = site.result
                val there = "$name ${site.method.definingClass}->${site.method.name}"
                assertEquals("$there: three instructions in", rowStock.size + 3, rowHooked.size)
                assertEquals("$there: stock code up to the button", rowStock.take(r + 1).map { it.target() }, rowHooked.take(r + 1).map { it.target() })
                assertEquals("$there: the hook call", Opcode.INVOKE_STATIC_RANGE, rowHooked[r + 1].opcode)
                assertEquals("$there: the hook", META_AI_BUTTON_HOOK, rowHooked[r + 1].target())
                val handed = rowHooked[r + 1] as RegisterRangeInstruction
                assertEquals("$there: hands over the button", listOf(site.register, 1), listOf(handed.startRegister, handed.registerCount))
                assertEquals("$there: the answer", Opcode.MOVE_RESULT_OBJECT, rowHooked[r + 2].opcode)
                assertEquals("$there: lands where the button was", site.register, (rowHooked[r + 2] as OneRegisterInstruction).registerA)
                assertEquals("$there: cast back", Opcode.CHECK_CAST, rowHooked[r + 3].opcode)
                assertEquals("$there: to the built type", site.type, rowHooked[r + 3].target())
                assertEquals("$there: the row's null check follows", Opcode.IF_EQZ, rowHooked[r + 4].opcode)
                assertEquals("$there: on the same register", site.register, (rowHooked[r + 4] as OneRegisterInstruction).registerA)
                assertEquals("$there: stock code after the hook", rowStock.drop(r + 1).map { it.target() }, rowHooked.drop(r + 4).map { it.target() })

                val pickerHooked = patched(context, gate.method).body()
                val t = gate.test
                val screen = "$name ${gate.method.definingClass}->${gate.method.name}"
                assertEquals("$screen: two instructions in", pickerStock.size + 2, pickerHooked.size)
                // The margin's test comes before the hook, so the box keeps the margin it has beside the button.
                assertEquals("$screen: stock code up to the button's test, the margin's test in it", pickerStock.take(t).map { it.opcode to it.target() }, pickerHooked.take(t).map { it.opcode to it.target() })
                assertEquals("$screen: the hook call", Opcode.INVOKE_STATIC_RANGE, pickerHooked[t].opcode)
                assertEquals("$screen: the hook", PICKER_META_AI_HOOK, pickerHooked[t].target())
                val asked = pickerHooked[t] as RegisterRangeInstruction
                assertEquals("$screen: hands over the flag", listOf(flag, 1), listOf(asked.startRegister, asked.registerCount))
                assertEquals("$screen: the answer", Opcode.MOVE_RESULT, pickerHooked[t + 1].opcode)
                assertEquals("$screen: lands in the flag", flag, (pickerHooked[t + 1] as OneRegisterInstruction).registerA)
                assertEquals("$screen: the button's test reads it", flag, (pickerHooked[t + 2] as OneRegisterInstruction).registerA)
                assertEquals("$screen: stock code from the test on", pickerStock.drop(t).map { it.opcode to it.target() }, pickerHooked.drop(t + 2).map { it.opcode to it.target() })
                // Nothing jumps to the button's test, so every way there runs the hook first, and every
                // other branch still lands on the instruction it did.
                assertTrue("$screen: no branch lands on the button's test", branches(pickerStock).none { it.second == t })
                val moved = { at: Int -> if (at >= t) at + 2 else at }
                assertEquals(
                    "$screen: branches land where they did",
                    branches(pickerStock).map { (from, to) -> moved(from) to moved(to) },
                    branches(pickerHooked),
                )
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
