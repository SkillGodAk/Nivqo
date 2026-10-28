/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code Download any reel puts in front of the sidebar assembly, and its field lookups. */
class DownloadReelShapesTest {
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"

    /**
     * A sidebar builder whose button list comes from a helper declared to return a plain List, as
     * a build could hand it, and whose markers are a new ArrayList. The block goes in front of the
     * nop standing in for the assembly's argument moves. The story sits in [story], and the helper
     * call names it in [storyScratch].
     */
    private fun sidebar(story: Int = 33, storyScratch: Int = 4): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sidebar;",
            "build",
            listOf(ImmutableMethodParameter("Lfixture/Scope;", null, null)),
            "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(40, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;
                move-result-object v20
                new-instance v21, Ljava/util/ArrayList;
                invoke-direct/range { v21 .. v21 }, Ljava/util/ArrayList;-><init>()V
                nop
                const/4 v0, 0x0
                return-object v0
            """,
        )
        addInstructionsAtControlFlowLabel(
            4,
            sidebarButtonBlock(
                session = 30,
                scoped = 31,
                player = 32,
                story = story,
                storyScratch = storyScratch,
                helper = HELPER,
                buttons = 20,
                icon = "Lfixture/Icon;->DOWNLOAD:Lfixture/Icon;",
                marker = "Lfixture/Marker;->of(Lfixture/Icon;)Lfixture/Marker;",
                markers = 21,
            ),
        )
    }

    private val HELPER =
        "Lfixture/Sidebar;->hushfacebookDownloadButton(${session}Lfixture/Scope;Lfixture/Player;Ljava/lang/Object;)Lfixture/Button;"

    private val Instruction.call get() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /**
     * The helper gets the session, the scoped context, the player and the reel's story, the last
     * in the 4-bit register the patch found dead, copied from the assembly argument that holds it.
     */
    @Test
    fun `the helper is handed the reel's story in the dead register`() {
        val body = sidebar().implementation!!.instructions.toList()
        val at = body.indexOfFirst { it.call == HELPER }
        assertTrue("no call to the helper", at > 0)
        val call = body[at] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, call.opcode)
        assertEquals(listOf(0, 1, 2, 4), listOf(call.registerC, call.registerD, call.registerE, call.registerF))
        val copy = body[at - 1] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, copy.opcode)
        assertEquals(4, copy.registerA)
        assertEquals(33, copy.registerB)
        // The switch is still asked first, and the story's copy sits after the three the helper always took.
        assertTrue(body[4].call!!.endsWith("->showsButton()Z"))
        assertEquals(listOf(30, 31, 32), (at - 4 until at - 1).map { (body[it] as TwoRegisterInstruction).registerB })
    }

    /**
     * 582 hands the assembly its arguments from v3 up, so the story already sits in a 4-bit
     * register. The helper call names it there, and nothing is copied over it.
     */
    @Test
    fun `a story already in a low register goes to the helper as it is`() {
        val body = sidebar(story = 4, storyScratch = 4).implementation!!.instructions.toList()
        val at = body.indexOfFirst { it.call == HELPER }
        assertTrue("no call to the helper", at > 0)
        val call = body[at] as FiveRegisterInstruction
        assertEquals(listOf(0, 1, 2, 4), listOf(call.registerC, call.registerD, call.registerE, call.registerF))
        assertEquals("only the three copies the helper always takes", listOf(30, 31, 32),
            (at - 3 until at).map { (body[it] as TwoRegisterInstruction).registerB })
        assertEquals("the switch's branch comes straight before them", Opcode.IF_EQZ, body[at - 4].opcode)
        assertTrue("nothing writes v4", body.none { (it as? TwoRegisterInstruction)?.registerA == 4 && it.opcode == Opcode.MOVE_OBJECT_FROM16 })
    }

    /**
     * `AbstractCollection.add` through invoke-virtual only verifies on a register the verifier
     * knows holds an AbstractCollection. A list declared as a plain List doesn't, and ART rejects
     * the whole class for it.
     */
    @Test
    fun `both adds go through the List interface`() {
        val body = sidebar().implementation!!.instructions.toList()
        val adds = body.withIndex().filter { it.value.call?.contains("->add(") == true }
        assertEquals("two adds: the button and its marker", 2, adds.size)
        for ((index, add) in adds) {
            assertEquals(Opcode.INVOKE_INTERFACE, add.opcode)
            assertEquals("Ljava/util/List;->add(Ljava/lang/Object;)Z", add.call)
            // The list is copied into the call's first register just before it.
            val copy = body[index - 1] as TwoRegisterInstruction
            assertEquals(Opcode.MOVE_OBJECT_FROM16, body[index - 1].opcode)
            assertEquals(copy.registerA, (add as FiveRegisterInstruction).registerC)
        }
        assertEquals("the button goes in the button list", 20, (body[adds[0].index - 1] as TwoRegisterInstruction).registerB)
        assertEquals("the marker goes in the marker list", 21, (body[adds[1].index - 1] as TwoRegisterInstruction).registerB)
        assertTrue(body.none { it.call?.startsWith("Ljava/util/AbstractCollection;") == true })
    }

    /**
     * A builder shaped like the sidebar's end: the two lists fetched into v20 and v21, three
     * instructions standing in for the argument moves before the assembly call at index 8, then
     * [after]. Each names a high register only where its operand reaches it: the patcher's smali
     * compiler leaves out, without a word, an instruction whose register doesn't fit.
     */
    private fun builderEnd(after: String = "", window: String = "nop\nnop\nnop"): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sidebar;", "build", listOf(ImmutableMethodParameter("Lfixture/Scope;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(40, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                const/4 v3, 0x0
                invoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;
                move-result-object v20
                invoke-static { }, Lfixture/Lists;->markers()Ljava/util/ArrayList;
                move-result-object v21
                $window
                invoke-static/range { v20 .. v21 }, Lfixture/Assembly;->build(Ljava/util/List;Ljava/util/ArrayList;)V
                $after
                return-void
            """,
        )
    }

    /** The injection point three instructions before the assembly call, which is at index 8. */
    private val injectAt = 5
    private val assemblyAt = 8

    @Test
    fun `the block's locals are proved free where it goes, not taken on trust`() {
        // The positive control: nothing reads v0 to v3 after the injection point.
        builderEnd().requireSidebarBlockFits(injectAt, SIDEBAR_BLOCK_SCRATCH + 3, reads = listOf(20, 21))

        // v0 is read after the call. The window before the call never names it, which is all the
        // old check looked at, so the block would have replaced it with the switch's answer.
        val readsV0 = builderEnd(after = "invoke-static { v0 }, Lfixture/Log;->note(I)V")
        val refused = assertThrows(PatchException::class.java) {
            readsV0.requireSidebarBlockFits(injectAt, SIDEBAR_BLOCK_SCRATCH + 3, reads = listOf(20, 21))
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("still reads v0 after instruction 5"))
    }

    @Test
    fun `the block may not read a register it borrows`() {
        val borrowed = assertThrows(PatchException::class.java) {
            builderEnd().requireSidebarBlockFits(injectAt, SIDEBAR_BLOCK_SCRATCH + 3, reads = listOf(20, 2))
        }
        assertTrue(borrowed.message, borrowed.message.orEmpty().contains("keeps v2 for the assembly call"))
    }

    /**
     * What the block reads is what the call gets: a register nothing writes before the call is the
     * call's own, and one an object move fills in between is followed back to the register it copies.
     */
    @Test
    fun `an argument is followed back through the moves after the block, and nothing else`() {
        assertEquals(20, builderEnd().argumentAt(injectAt, assemblyAt, 20))
        // The list the call gets is swapped in from v21 after the block, so the block adds to v21.
        val swapped = builderEnd(window = "nop\nmove-object/from16 v20, v21\nnop")
        assertEquals(21, swapped.argumentAt(injectAt, assemblyAt, 20))
        // Two moves in a row are followed to the first.
        val twice = builderEnd(window = "move-object/from16 v22, v21\nmove-object/from16 v20, v22\nnop")
        assertEquals(21, twice.argumentAt(injectAt, assemblyAt, 20))

        // Made fresh between the block and the call, it doesn't exist yet where the block reads it.
        val made = builderEnd(window = "nop\ninvoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;\nmove-result-object v20")
        val refused = assertThrows(PatchException::class.java) { made.argumentAt(injectAt, assemblyAt, 20) }
        assertTrue(refused.message, refused.message.orEmpty().contains("makes v20 at instruction 7"))
    }

    @Test
    fun `the story's local is the lowest above v2 that nothing reads and the block doesn't`() {
        assertEquals(3, builderEnd().storyScratchRegister(injectAt, reads = listOf(20, 21, 22), story = 22))
        // v3 is read after the call, and v4 holds something the block reads.
        val method = builderEnd(after = "invoke-static { v3 }, Lfixture/Log;->note(I)V")
        assertEquals(5, method.storyScratchRegister(injectAt, reads = listOf(4, 20, 21, 22), story = 22))
    }

    @Test
    fun `a story from v3 to v15 is named in its own register, and one in v0 to v2 is copied`() {
        // v3 is read after the call: it's the story the call gets, and the helper only reads it.
        val method = builderEnd(after = "invoke-static { v3 }, Lfixture/Log;->note(I)V")
        assertEquals(3, method.storyScratchRegister(injectAt, reads = listOf(3, 20, 21), story = 3))
        assertEquals(15, builderEnd().storyScratchRegister(injectAt, reads = listOf(15, 20, 21), story = 15))
        // The block writes v0 to v2 before the helper call, so a story there needs a copy.
        assertEquals(3, builderEnd().storyScratchRegister(injectAt, reads = listOf(2, 20, 21), story = 2))
    }

    /**
     * 577 to 582 leave v0 to v2 free three instructions before the call. 583 moves three lambdas
     * out of them into the call's range there, so the block goes in after the last such read.
     */
    @Test
    fun `the block goes in where v0 to v2 are free, three instructions before the call at most`() {
        assertEquals(injectAt, builderEnd().sidebarBlockIndex(assemblyAt))
        val lambdasLast = builderEnd(window = "move-object/from16 v22, v1\nmove-object/from16 v23, v0\nnop")
        assertEquals(assemblyAt - 1, lambdasLast.sidebarBlockIndex(assemblyAt))
        // The block fits there, and the old place is refused for the very reason it moved.
        lambdasLast.requireSidebarBlockFits(assemblyAt - 1, SIDEBAR_BLOCK_SCRATCH + 3, reads = listOf(20, 21))
        val refused = assertThrows(PatchException::class.java) {
            lambdasLast.requireSidebarBlockFits(injectAt, SIDEBAR_BLOCK_SCRATCH + 3, reads = listOf(20, 21))
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("still reads v0, v1 after instruction 5"))
        // Read right up to the call, nothing qualifies, and the old place is handed back to be refused.
        val readToTheEnd = builderEnd(window = "nop\nnop\nmove-object/from16 v23, v0", after = "invoke-static { v1 }, Lfixture/Log;->note(I)V")
        assertEquals(injectAt, readToTheEnd.sidebarBlockIndex(assemblyAt))
    }

    /**
     * A render shaped like 584's end (LX/AF0;->A0l): the player parked in v29 at instruction 2, the
     * lists fetched, a button loop whose exit jumps to instruction 11, then the call's straight run,
     * which parks something else in v29 first and makes three lambdas in v0 to v2 before the call at
     * 18. [start] goes in front of the player's read, and [loop] at the end of the loop's body.
     */
    private fun loopEnd(start: String = "", loop: String = ""): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sidebar;", "render", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(40, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                $start
                invoke-static { }, Lfixture/Lists;->player()Lfixture/Player;
                move-result-object v3
                move-object/from16 v29, v3
                :parked
                invoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;
                move-result-object v20
                invoke-static { }, Lfixture/Lists;->markers()Ljava/util/ArrayList;
                move-result-object v21
                :loop
                invoke-static { }, Lfixture/Lists;->more()Z
                move-result v3
                if-eqz v3, :done
                $loop
                goto :loop
                :done
                move-object/from16 v29, v22
                const/4 v0, 0x0
                const/4 v1, 0x0
                const/4 v2, 0x0
                move-object/from16 v23, v0
                move-object/from16 v24, v1
                move-object/from16 v25, v2
                invoke-static/range { v20 .. v29 }, Lfixture/Assembly;->build(Ljava/util/List;Ljava/util/ArrayList;$OBJECTS)V
                return-void
            """,
        )
    }

    private val OBJECTS = "Ljava/lang/Object;".repeat(8)
    private val loopCallAt = 18
    private val runStart = 11

    /**
     * 584 parks the scoped context over the player in the call's straight run, so the block goes in
     * at the run's start, where the loop's exit lands and v0 to v2 are still free, and not three
     * instructions before the call. Nothing parked, or the parked register left alone in the run,
     * the block stays where it was.
     */
    @Test
    fun `a block reading a parked player goes in before the run overwrites it`() {
        val method = loopEnd()
        assertEquals(runStart, method.straightRunStart(loopCallAt))
        assertEquals(loopCallAt, method.sidebarBlockIndex(loopCallAt))
        assertEquals(runStart, method.sidebarBlockIndex(loopCallAt, parked = 29))
        assertEquals(loopCallAt, method.sidebarBlockIndex(loopCallAt, parked = 26))
        method.requireSidebarBlockFits(runStart, SIDEBAR_BLOCK_SCRATCH + 3, reads = listOf(20, 21, 22, 29))
    }

    /**
     * The arguments are followed back from the call to the run's start, where the block reads them,
     * and an index the loop jumps past is refused.
     */
    @Test
    fun `the block's arguments are read at the run's start, and not across a jump`() {
        val method = loopEnd()
        assertEquals("the call's v29 is v22 before the run parks it", 22, method.argumentAt(runStart, loopCallAt, 29))
        assertEquals(20, method.argumentAt(runStart, loopCallAt, 20))
        val made = assertThrows(PatchException::class.java) { method.argumentAt(runStart, loopCallAt, 23) }
        assertTrue(made.message, made.message.orEmpty().contains("makes v0 at instruction 12"))
        val jumped = assertThrows(PatchException::class.java) { method.argumentAt(runStart - 4, loopCallAt, 20) }
        assertTrue(jumped.message, jumped.message.orEmpty().contains("jumps between instruction 7 and the call at 18"))
    }

    /**
     * Every path to the block has to come through the parking. One that skips it, or one coming
     * round the loop after the loop parks something else, would hand the helper a wrong player that
     * still verifies.
     */
    @Test
    fun `the parked player has to reach the block on every path`() {
        loopEnd().requireParkedAt("the player", 29, parkedAt = 2, index = runStart)

        // The loop's body parks something else in v29, and its back edge reaches the block.
        val loopWrites = loopEnd(loop = "move-object/from16 v29, v22")
        val looped = assertThrows(PatchException::class.java) {
            loopWrites.requireParkedAt("the player", 29, parkedAt = 2, index = runStart + 1)
        }
        assertTrue(looped.message, looped.message.orEmpty().contains("reaches instruction 12"))
        assertTrue(looped.message, looped.message.orEmpty().contains("writes v29 at 10, 12"))

        // A branch at the start jumps past the parking.
        val skips = loopEnd(start = "if-eqz v5, :parked")
        val skipped = assertThrows(PatchException::class.java) {
            skips.requireParkedAt("the player", 29, parkedAt = 3, index = runStart + 1)
        }
        assertTrue(skipped.message, skipped.message.orEmpty().contains("reaches instruction 12"))
    }

    /**
     * The block takes over the label of the instruction it goes in front of, so 584's loop exit runs
     * it, and its switch's no lands on its own last nop, which falls through to that instruction.
     */
    @Test
    fun `a jump to the block's place runs the block, and the switch's no skips to the original`() {
        val method = loopEnd()
        method.addInstructionsAtControlFlowLabel(
            runStart,
            sidebarButtonBlock(
                session = 30, scoped = 31, player = 29, story = 32, storyScratch = 4, helper = HELPER,
                buttons = 20, icon = "Lfixture/Icon;->DOWNLOAD:Lfixture/Icon;",
                marker = "Lfixture/Marker;->of(Lfixture/Icon;)Lfixture/Marker;", markers = 21,
            ),
        )
        val body = method.implementation!!.instructions.toList()
        val exit = body[9] as BuilderOffsetInstruction
        assertEquals(Opcode.IF_EQZ, exit.opcode)
        assertSame("the loop's exit lands on the block", body[runStart], exit.target.location.instruction)
        assertTrue(body[runStart].call!!.endsWith("->showsButton()Z"))

        val switch = body[runStart + 2] as BuilderOffsetInstruction
        val nop = body.indexOfLast { it.opcode == Opcode.NOP }
        assertSame("the switch's no goes to the block's last nop", body[nop], switch.target.location.instruction)
        val original = body[nop + 1] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[nop + 1].opcode)
        assertEquals(listOf(29, 22), listOf(original.registerA, original.registerB))
        assertEquals("the original stays once, after the block", 1,
            body.count { it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == 29 && it.registerB == 22 })
    }

    /** A one-method stand-in for a class's method, with [code] as its body. */
    private fun method(type: String, name: String, parameters: List<String>, returns: String, code: String, static: Boolean = false) =
        MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(8, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, code) }

    /**
     * 583's sidebar builder is the spec's method, handed the scoped context first and then the
     * component's fields, and the component's render only calls it. The render is told by its one
     * parameter, the builder's first, and the call.
     */
    @Test
    fun `a render handing the scoped context to the spec's builder is the sidebar's render`() {
        val builder = method(
            "Lfixture/Spec;", "build", listOf("Lfixture/Scope;", session, "Lfixture/Player;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nreturn-object v0",
        )
        val call = "invoke-virtual/range { v0 .. v3 }, Lfixture/Spec;->build(Lfixture/Scope;${session}Lfixture/Player;)Lfixture/Component;"
        val render = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v2, 0x0\nconst/4 v3, 0x0\n$call\nmove-result-object v0\nreturn-object v0")
        assertTrue(rendersThrough(render, builder))

        val otherScope = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Other;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v2, 0x0\nconst/4 v3, 0x0\n$call\nmove-result-object v0\nreturn-object v0")
        assertTrue("another scoped type", !rendersThrough(otherScope, builder))
        val twoParameters = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;", "I"), "Lfixture/Component;",
            "const/4 v0, 0x0\nconst/4 v2, 0x0\nconst/4 v3, 0x0\n$call\nmove-result-object v0\nreturn-object v0")
        assertTrue("a second parameter", !rendersThrough(twoParameters, builder))
        val noCall = method("Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
            "const/4 v0, 0x0\nreturn-object v0")
        assertTrue("no call to the builder", !rendersThrough(noCall, builder))
    }

    private val handler = "Lkotlin/jvm/functions/Function1;"
    private val factory = "Lfixture/Factory;->make($session$handler$handler$handler$handler)Lfixture/Button;"

    /** A static of the sidebar's class taking the button's kind, with [code] as its body. */
    private fun buttonMaker(code: String, type: String = "Lfixture/Sidebar;", static: Boolean = true) =
        method(type, "button", listOf("I"), "Lfixture/Button;", code, static)

    /** A render naming the sidebar, when [named], and calling [call]. */
    private fun sidebarRender(call: String, named: Boolean = true) = method(
        "Lfixture/Sidebar;", "render", listOf("Lfixture/Scope;"), "Lfixture/Component;",
        (if (named) "const-string v0, \"UDDSideBarComponent\"\n" else "") +
            "const/4 v1, 0x0\ninvoke-static { v1 }, $call\nmove-result-object v0\nreturn-object v0",
    )

    /**
     * Up to 583 the sidebar's builder calls the button factory, the call taking the session and
     * four handlers, itself. 584's render (LX/AF0;->A0l) calls a static of its own class instead
     * (LX/AF0;->A01), which calls the factory (LX/2Uj;->A00), so that static is read for it.
     */
    @Test
    fun `the sidebar's builder calls the button factory itself or through a static of its class`() {
        val makesButton = "invoke-static { v0, v1, v2, v3, v4 }, $factory\nmove-result-object v0\nreturn-object v0"
        val inline = method("Lfixture/Spec;", "build", listOf("Lfixture/Scope;"), "Lfixture/Button;",
            "const-string v0, \"UDDSideBarComponent\"\n$makesButton")
        assertTrue(isSidebarBuilder(inline, listOf(inline)))
        assertEquals(listOf(inline), buttonFactoryCallers(inline, listOf(inline)))

        val maker = buttonMaker(makesButton)
        val render = sidebarRender("Lfixture/Sidebar;->button(I)Lfixture/Button;")
        assertTrue(isSidebarBuilder(render, listOf(render, maker)))
        assertEquals(listOf(maker), buttonFactoryCallers(render, listOf(render, maker)))

        // Without the sidebar's name it's some other component's render.
        val unnamed = sidebarRender("Lfixture/Sidebar;->button(I)Lfixture/Button;", named = false)
        assertTrue("no sidebar name", !isSidebarBuilder(unnamed, listOf(unnamed, maker)))
        // A static of another class with the same name and signature isn't one of the siblings.
        val elsewhere = sidebarRender("Lfixture/Other;->button(I)Lfixture/Button;")
        assertTrue("another class's static", !isSidebarBuilder(elsewhere, listOf(elsewhere, maker)))
        // The static has to call the factory: four handlers without the session first is 582's
        // FDSBottomSheet, not a button.
        val sheet = buttonMaker("invoke-static { v0, v1, v2, v3 }, Lfixture/Sheet;->make($handler$handler$handler$handler)Lfixture/Button;\n" +
            "move-result-object v0\nreturn-object v0")
        assertTrue("a sibling making a sheet", !isSidebarBuilder(render, listOf(render, sheet)))
        // And it has to be the static the render calls, not an instance method of the same shape.
        val instance = buttonMaker(makesButton, static = false)
        assertTrue("an instance method", !isSidebarBuilder(render, listOf(render, instance)))
    }

    /**
     * 583's arm64 builder asks for each button's marker itself. Its armeabi-v7a one hands the icon,
     * the button and the three lists to a static Redex outlined from the loop body, which asks for
     * the marker there, so that helper is read when the builder names no marker.
     */
    @Test
    fun `the marker is read off the builder, or off the outlined loop body it hands the icon to`() {
        val icon = "Lfixture/Icon;"
        val lists = "Ljava/util/AbstractCollection;"
        val marker = "Lfixture/Markers;->of(${icon})Lfixture/Marker;"
        val loopBody = method(
            "Lfixture/Loop;", "add", listOf(icon, "Ljava/lang/Object;", lists, lists, lists), "V",
            "invoke-static { p0 }, $marker\nmove-result-object v0\ninvoke-virtual { p3, v0 }, $lists->add(Ljava/lang/Object;)Z\nreturn-void",
            static = true,
        )
        val resolve = { call: MethodReference -> loopBody.takeIf { call.definingClass == "Lfixture/Loop;" && call.name == "add" } }
        val outlined = method("Lfixture/Spec;", "build", listOf("Lfixture/Scope;"), "V",
            "const/4 v0, 0x0\ninvoke-static { v0, v0, v0, v0, v0 }, Lfixture/Loop;->add(${icon}Ljava/lang/Object;$lists$lists$lists)V\nreturn-void")
        assertEquals(listOf(marker), iconMarkers(outlined, icon, resolve).map { it.toString() })

        // Asked in the builder, that's the one, and a helper taking the icon isn't read.
        val inline = method("Lfixture/Spec;", "build", listOf("Lfixture/Scope;"), "V",
            "const/4 v0, 0x0\ninvoke-static { v0 }, Lfixture/Own;->of(${icon})Lfixture/Marker;\n" +
                "invoke-static { v0, v0, v0, v0, v0 }, Lfixture/Loop;->add(${icon}Ljava/lang/Object;$lists$lists$lists)V\nreturn-void")
        assertEquals(listOf("Lfixture/Own;->of(${icon})Lfixture/Marker;"), iconMarkers(inline, icon, resolve).map { it.toString() })

        // A helper that can't be read names nothing.
        assertEquals(emptyList<String>(), iconMarkers(outlined, icon) { null }.map { it.toString() })
    }

    private fun field(name: String, type: String) = ImmutableField("Lfixture/Sidebar;", name, type, AccessFlags.PUBLIC.value, null, null, null)

    @Test
    fun `a field is picked by its type, and a missing or doubled one is named`() {
        val sessionField = field("session", session)
        val others = listOf(field("context", "Landroid/content/Context;"), field("count", "I"))
        assertSame(sessionField, fieldOfType(others + sessionField, session, "the session field"))

        val none = assertThrows(PatchException::class.java) { fieldOfType(others, session, "the session field") }
        assertTrue(none.message, none.message.orEmpty().contains("the session field"))
        assertTrue(none.message, none.message.orEmpty().contains("found 0"))

        val two = assertThrows(PatchException::class.java) {
            fieldOfType(others + sessionField + field("session2", session), session, "the session field")
        }
        assertTrue(two.message, two.message.orEmpty().contains("found 2"))
    }
}
