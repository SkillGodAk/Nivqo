/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.ExtensionDex
import app.morphe.RepoFiles
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.shared.FEED_STORY_CATEGORY
import app.morphe.patches.facebook.shared.FEED_UNIT_EDGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parts of the swap guard that need no Facebook build: which runnable it takes and where in
 * it, what it refuses, the code it puts there, and the contract rule that holds a patched build to it.
 */
class EdgeSwapShapesTest {
    private val owner = "Lfixture/SwapRunnable;"
    private val collection = "Lfixture/Collection;"
    private val swapCall = "invoke-interface {v4, v0, v9}, $collection->replace(${FEED_UNIT_EDGE}Ljava/lang/String;)V"
    private val categoryGetter = "category"
    private val unitGetter: Method = ImmutableMethod(
        FEED_UNIT_EDGE, "unit", emptyList(), "Lfixture/FeedUnit;", AccessFlags.PUBLIC.value, null, null, null,
    )

    /**
     * The runnable's run(), cut down to what the guard reads: the edge taken from its holder and
     * cast, the collection's size before, the swap handing it the edge and the old key, the size
     * after, and the log line when the collection came out smaller. The runnable itself is v11 (p0).
     */
    private fun standardBody(
        cast: String = "check-cast v0, $FEED_UNIT_EDGE",
        beforeSwap: String = "",
        swap: String = swapCall,
        literals: List<String> = listOf(EDGE_SWAP_DROPPED, SIZE_BEFORE, SIZE_AFTER),
    ): String = """
        iget-object v5, v11, $owner->holder:Lfixture/Holder;
        invoke-interface {v5}, Lfixture/Holder;->edge()Ljava/lang/Object;
        move-result-object v0
        $cast
        iget-object v3, v11, $owner->manager:Lfixture/Manager;
        iget-object v4, v3, Lfixture/Manager;->collection:$collection
        invoke-interface {v4}, $collection->size()I
        move-result v6
        iget-object v9, v11, $owner->key:Ljava/lang/String;
        $beforeSwap
        $swap
        invoke-interface {v4}, $collection->size()I
        move-result v7
        if-ge v7, v6, :kept
        ${literals.mapIndexed { i, s -> "const-string v${listOf(1, 2, 8)[i]}, \"$s\"" }.joinToString("\n")}
        invoke-static {v1, v2, v8}, Lfixture/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
        :kept
        invoke-static {v0}, Lfixture/Tracker;->sent(Ljava/lang/Object;)V
        return-void
    """

    private fun run(
        body: String = standardBody(),
        registers: Int = 12,
        static: Boolean = false,
        name: String = "run",
        returns: String = "V",
    ): MutableMethod {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
        return MutableMethod(
            ImmutableMethod(owner, name, emptyList(), returns, flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null)),
        ).apply { addInstructionsWithLabels(0, body) }
    }

    private fun runnable(run: Method = run(), runnable: Boolean = true, interfaces: List<String>? = null): ClassDef = ImmutableClassDef(
        owner, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
        interfaces ?: if (runnable) listOf("Ljava/lang/Runnable;") else null, null, null, null, listOf(run),
    )

    /** 584's way out of the swap: Kotlin's Unit, read from its own static field and returned. */
    private val unitReturn = """
        sget-object v1, Lfixture/Unit;->INSTANCE:Lfixture/Unit;
        return-object v1
    """

    /** 584's invoke(), which does run()'s work and returns Unit. */
    private fun invoke(body: String = standardBody().replace("return-void", unitReturn)) =
        run(body, name = "invoke", returns = "Ljava/lang/Object;")

    /** 584's swap, a Kotlin Function0 around [invoke]. */
    private fun function0(invoke: Method = invoke()): ClassDef =
        runnable(invoke, interfaces = listOf("Lkotlin/jvm/functions/Function0;"))

    @Test
    fun `the guard goes right after the incoming edge's cast`() {
        val guard = swapGuard(runnable())!!
        assertEquals(4, guard.index)
        assertEquals(0, guard.edge)
    }

    @Test
    fun `all three literals of the log line are required`() {
        val literals = listOf(EDGE_SWAP_DROPPED, SIZE_BEFORE, SIZE_AFTER)
        for (left in literals) {
            val others = literals.map { if (it == left) "something else" else it }
            assertNull("without $left", swapGuard(runnable(run(standardBody(literals = others)))))
        }
    }

    @Test
    fun `only a Runnable's instance run taking nothing is the swap runnable`() {
        assertNull("not a Runnable", swapGuard(runnable(runnable = false)))
        assertNull("static", swapGuard(runnable(run(static = true))))
        assertNull("another name", swapGuard(runnable(run(name = "flush"))))
    }

    @Test
    fun `the swap has to hand the collection the cast edge and no other`() {
        assertNull("no cast", swapGuard(runnable(run(standardBody(cast = "nop")))))
        assertNull("no swap", swapGuard(runnable(run(standardBody(swap = "nop")))))
        assertNull("two swaps", swapGuard(runnable(run(standardBody(beforeSwap = swapCall)))))
        assertNull("another register", swapGuard(runnable(run(standardBody(
            swap = "invoke-interface {v4, v5, v9}, $collection->replace(${FEED_UNIT_EDGE}Ljava/lang/String;)V",
        )))))
        assertNull("the edge's register written again", swapGuard(runnable(run(standardBody(
            beforeSwap = "invoke-interface {v5}, Lfixture/Holder;->other()Ljava/lang/Object;\nmove-result-object v0",
        )))))
    }

    /**
     * 583's armeabi-v7a runnable takes the edge from a getter that already answers
     * GraphQLFeedUnitEdge, with no cast, and the guard goes right after that getter's result.
     */
    @Test
    fun `an edge from a getter answering the edge type needs no cast`() {
        val body = standardBody(cast = "").replace(
            "Lfixture/Holder;->edge()Ljava/lang/Object;", "Lfixture/Holder;->edge()$FEED_UNIT_EDGE",
        )
        val guard = swapGuard(runnable(run(body)))!!
        assertEquals(3, guard.index)
        assertEquals(0, guard.edge)
        assertNull("an untyped getter and no cast", swapGuard(runnable(run(standardBody(cast = "")))))
    }

    /**
     * 584 posts a Kotlin Function0 instead of a Runnable. Its invoke() takes the guard in the same
     * place, and the guard returns through invoke()'s one return, the Unit load right before it.
     */
    @Test
    fun `584's Function0 takes the guard in invoke and returns through its Unit`() {
        val guard = swapGuard(function0())!!
        assertEquals(4, guard.index)
        assertEquals(0, guard.edge)
        val code = guard.method.implementation!!.instructions.toList()
        assertEquals(listOf(Opcode.SGET_OBJECT, Opcode.RETURN_OBJECT), listOf(code[guard.unit!!].opcode, code[guard.unit!! + 1].opcode))
        assertNull("a runnable's run() has no Unit", swapGuard(runnable())!!.unit)

        assertNull("anything but Unit returned", swapGuard(function0(invoke(standardBody().replace("return-void", "const/4 v1, 0x0\nreturn-object v1")))))
        assertNull("a static field of another type", swapGuard(function0(invoke(standardBody().replace(
            "return-void", "sget-object v1, Lfixture/Unit;->OTHER:Lfixture/Other;\nreturn-object v1",
        )))))
        assertNull("two ways out", swapGuard(function0(invoke(standardBody().replace(
            "if-ge v7, v6, :kept", "if-ge v7, v6, :kept\nconst/4 v3, 0x0\nreturn-object v3",
        ).replace("return-void", unitReturn)))))
        assertNull("both a Runnable and a Function0", swapGuard(runnable(
            run(standardBody().replace("return-void", unitReturn), name = "invoke", returns = "Ljava/lang/Object;"),
            interfaces = listOf("Ljava/lang/Runnable;", "Lkotlin/jvm/functions/Function0;"),
        )))
        assertNull("a Function0's run()", swapGuard(runnable(run(), interfaces = listOf("Lkotlin/jvm/functions/Function0;"))))
    }

    /** In invoke() the guard asks the extension the same way and jumps to the Unit return when told to. */
    @Test
    fun `the guard in invoke jumps to its Unit return`() {
        val method = invoke()
        val guard = swapGuard(function0(method))!!
        val before = method.implementation!!.instructions.toList()
        method.guardEdgeSwap(guard, categoryGetter, unitGetter)
        val after = method.implementation!!.instructions.toList()

        assertEquals(before.size + 7, after.size)
        val added = after.subList(guard.index, guard.index + 7)
        assertEquals(
            listOf(
                Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
            ),
            added.map { it.opcode },
        )
        assertEquals(HIDE_SWAPPED_EDGE, added[4].call())
        assertEquals(listOf(1, 2), added[4].registers())
        assertSame("the jump lands on the Unit load", after[guard.unit!! + 7],
            (added[6] as BuilderOffsetInstruction).target.location.instruction)
        assertEquals("the rest of invoke()", before.map { it.opcode },
            after.filterIndexed { index, _ -> index !in guard.index until guard.index + 7 }.map { it.opcode })
    }

    /** A branch that lands just after the cast would go round a guard put there. */
    @Test
    fun `a branch into the instruction after the cast is refused`() {
        val looped = """
            iget-object v5, v11, $owner->holder:Lfixture/Holder;
            invoke-interface {v5}, Lfixture/Holder;->edge()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $FEED_UNIT_EDGE
            :again
            iget-object v4, v11, $owner->collection:$collection
            iget-object v9, v11, $owner->key:Ljava/lang/String;
            $swapCall
            invoke-static {}, Lfixture/Log;->retry()Z
            move-result v6
            if-nez v6, :again
            const-string v1, "$EDGE_SWAP_DROPPED"
            const-string v2, "$SIZE_BEFORE"
            const-string v8, "$SIZE_AFTER"
            return-void
        """
        assertNull(swapGuard(runnable(run(looped))))
    }

    private fun Instruction.call(): String = (this as ReferenceInstruction).reference.toString()

    private fun Instruction.registers(): List<Int> = (this as FiveRegisterInstruction).let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    }

    /**
     * The guard reads the edge's category and unit with the funnel guard's getters, asks the
     * extension, and returns before the swap when it says so; otherwise the runnable goes on with
     * its own next instruction, and nothing else in it moves.
     */
    @Test
    fun `the guard asks the extension about the edge and returns before the swap`() {
        val method = run()
        val guard = swapGuard(runnable(method))!!
        val before = method.implementation!!.instructions.toList()
        method.guardEdgeSwap(guard, categoryGetter, unitGetter)
        val after = method.implementation!!.instructions.toList()

        assertEquals(before.size + 8, after.size)
        val added = after.subList(guard.index, guard.index + 8)
        assertEquals(
            listOf(
                Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
            ),
            added.map { it.opcode },
        )
        assertEquals("$FEED_UNIT_EDGE->$categoryGetter()$FEED_STORY_CATEGORY", added[0].call())
        assertEquals("$FEED_UNIT_EDGE->unit()Lfixture/FeedUnit;", added[2].call())
        assertEquals(HIDE_SWAPPED_EDGE, added[4].call())
        // v0 holds the edge; v1 and v2 are the lowest locals nothing reads before writing.
        assertEquals(listOf(1, 2), added[4].registers())
        assertEquals(1, (added[1] as OneRegisterInstruction).registerA)
        assertEquals(2, (added[3] as OneRegisterInstruction).registerA)
        assertEquals("the rest of run()", before.map { it.opcode },
            after.filterIndexed { index, _ -> index !in guard.index until guard.index + 8 }.map { it.opcode })
        val swap = after.indexOfFirst { it.opcode == Opcode.INVOKE_INTERFACE && it.call().contains("->replace(") }
        assertTrue("the guard comes before the swap", swap > guard.index + 7)
    }

    /** With one free local, not two, the patch stops and says why. */
    @Test
    fun `too few free locals stops the patch`() {
        val tight = """
            iget-object v1, v2, $owner->holder:Lfixture/Holder;
            invoke-interface {v1}, Lfixture/Holder;->edge()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $FEED_UNIT_EDGE
            iget-object v1, v2, $owner->collection:$collection
            invoke-interface {v1, v0, v0}, $collection->replace(${FEED_UNIT_EDGE}Ljava/lang/String;)V
            const-string v1, "$EDGE_SWAP_DROPPED"
            const-string v1, "$SIZE_BEFORE"
            const-string v1, "$SIZE_AFTER"
            return-void
        """
        val method = run(tight, registers = 3)
        val guard = swapGuard(runnable(method))!!
        val refusal = assertThrows(PatchException::class.java) { method.guardEdgeSwap(guard, categoryGetter, unitGetter) }
        assertTrue(refusal.message, refusal.message!!.contains("needs 2"))
    }

    /** The extension the bundle carries answers the call the guard writes, as a public static method. */
    @Test
    fun `the extension declares the method the guard calls`() {
        val (type, rest) = HIDE_SWAPPED_EDGE.split("->")
        val name = rest.substringBefore('(')
        val shape = rest.substring(name.length)
        val found = ExtensionDex.classDef(type).methods.filter {
            it.name == name && "(${it.parameterTypes.joinToString("")})${it.returnType}" == shape
        }
        assertEquals(1, found.size)
        assertTrue(AccessFlags.STATIC.isSet(found.single().accessFlags) && AccessFlags.PUBLIC.isSet(found.single().accessFlags))
    }

    /**
     * The contract holds a patched APK to one call of the funnel guard's hideEdge(Object, Object),
     * the edge helper's own, and counts calls from the extension too. So the swap guard reaches the same
     * verdict without calling it, and no other method of the feed filter calls it either.
     */
    @Test
    fun `the extension's feed filter never calls the funnel guard itself`() {
        val filter = HIDE_SWAPPED_EDGE.substringBefore("->")
        val hideEdge = "$filter->hideEdge(Ljava/lang/Object;Ljava/lang/Object;)Z"
        val callers = ExtensionDex.classDef(filter).methods.filter { method ->
            method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == hideEdge } == true
        }.map { it.name }
        assertEquals(emptyList<String>(), callers)
    }

    /**
     * The receipt refuses a patched build whose swap runnable doesn't ask the extension, asks it
     * twice, or where the call went anywhere else, by a once-call rule in
     * scripts/injected-mutation-contracts.txt. The rule names two of the strings the patch finds the
     * runnable by (the third holds spaces, which a rule can't) and the call the guard writes, in an
     * instance method taking nothing: run() through 583, invoke() on 584.
     */
    @Test
    fun `the contract file holds the hook`() {
        val rules = File(RepoFiles.root, "scripts/injected-mutation-contracts.txt").readLines()
            .map { it.trim() }
            .filter { it.contains("->hideSwappedEdge(") }
        assertEquals(listOf("once-call $HIDE_SWAPPED_EDGE in instance ()* holding $SIZE_BEFORE $SIZE_AFTER"), rules)
    }
}
