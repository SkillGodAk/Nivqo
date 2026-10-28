/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.followinghome

import app.morphe.PatchContexts
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The feed query hook sits on the return itself, so a branch onto that return goes through it
 * too, and a return before the feed's own query (583's ad query) is left alone.
 */
class FeedQueryReturnsTest {
    private val query = "LX/2Dt;"
    private val result = "LX/5hd;"

    @Test
    fun aBranchOntoTheReturnPassesTheHookAndAnEarlierReturnIsLeftAlone() {
        // Code units: 0 return-object (1), 1 const-string (2), 3 new-instance (2), 5 if-eqz (2),
        // 7 const/4 (1), 8 return-object (1). The if-eqz at 5 lands on the last return.
        val code: List<Instruction> = listOf(
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(FEED_QUERY_INNER)),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(result)),
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 3),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
        )
        val method = ImmutableMethod(query, "BWh", emptyList(), result, AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(3, code, null, null))
        val context = PatchContexts.of(listOf(
            ImmutableClassDef(query, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method)),
        ))
        val mutable = context.mutableClassDefBy(query).methods.single()
        assertEquals(listOf(5), feedQueryReturns(mutable))

        mutable.sendsFeedParams(FeedQuery(method, "$result->A01:$PARAM_SET_CLASS"))

        val patched = mutable.implementation!!.instructions.toList()
        assertEquals("the earlier return gets nothing", Opcode.RETURN_OBJECT, patched[0].opcode)
        assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.NOP,
            Opcode.RETURN_OBJECT), patched.drop(5).map { it.opcode })
        assertEquals("the branch onto the return runs the hook", 5, target(patched, 3))
        assertEquals("a null result skips to the return", 9, target(patched, 6))
        assertEquals(FEED_REQUEST_SENT, (patched[8] as ReferenceInstruction).reference.toString())
    }

    /** The instruction index the branch at [index] lands on. */
    private fun target(code: List<Instruction>, index: Int): Int {
        var address = 0
        val addresses = code.map { val at = address; address += it.codeUnits; at }
        return addresses.indexOf(addresses[index] + (code[index] as OffsetInstruction).codeOffset)
    }
}
