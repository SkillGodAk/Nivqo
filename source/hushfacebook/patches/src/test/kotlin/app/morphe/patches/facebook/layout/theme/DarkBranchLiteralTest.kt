/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Route three's dark-branch colours (issue #37): a colour `const` that is the first thing a method
 * does once Facebook's own dark check said yes goes through the extension, right after the `const`
 * and on its register, and only there. The light branch still skips it, and a `const` the method
 * also reaches another way, a resource id, or a check that branches the other way stays as it was.
 */
class DarkBranchLiteralTest {
    private val check = "Lfixture/Fds;->isDark(Landroid/content/Context;)Z"
    private val tile = "Lfixture/StoryTile;"
    private val context = "Landroid/content/Context;"

    /** The Create story tile's grey, #333334, and light mode's, #F8F9FB, as signed ints. */
    private val grey = -0xcccccc
    private val light = -0x70605

    private fun method(name: String, smali: String): Method =
        MutableMethod(
            ImmutableMethod(
                tile, name, listOf(ImmutableMethodParameter(context, null, null)), "I",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
                null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }.let(ImmutableMethod::of)

    private fun app(): BytecodePatchContext = PatchContexts.of(listOf<ClassDef>(
        ImmutableClassDef(tile, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            emptyList(), listOf(
                method("card", """
                    invoke-static { p0 }, $check
                    move-result v0
                    if-eqz v0, :light
                    const v1, $grey
                    :join
                    return v1
                    :light
                    const v1, $light
                    goto :join
                """),
                method("resource", """
                    invoke-static { p0 }, $check
                    move-result v0
                    if-eqz v0, :light
                    const v1, 0x7f0601ac
                    return v1
                    :light
                    const v1, 0x7f06014e
                    return v1
                """),
                method("lightFirst", """
                    invoke-static { p0 }, $check
                    move-result v0
                    if-nez v0, :dark
                    const v1, $light
                    return v1
                    :dark
                    const v1, $grey
                    return v1
                """),
                method("shared", """
                    invoke-static { p0 }, $check
                    move-result v0
                    if-eqz v0, :light
                    :both
                    const v1, $grey
                    return v1
                    :light
                    goto :both
                """),
                method("otherCheck", """
                    invoke-static { p0 }, Lfixture/Other;->isWide(Landroid/content/Context;)Z
                    move-result v0
                    if-eqz v0, :light
                    const v1, $grey
                    return v1
                    :light
                    const v1, $light
                    return v1
                """),
            )),
    ))

    private fun BytecodePatchContext.body(name: String): List<Instruction> =
        mutableClassDefBy(tile).methods.single { it.name == name }.implementation!!.instructions.toList()

    private fun Instruction.call(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** The instruction a branch at [index] jumps to. */
    private fun List<Instruction>.target(index: Int): Instruction {
        val addresses = runningFold(0) { address, it -> address + it.codeUnits }
        val at = addresses[index] + (this[index] as OffsetInstruction).codeOffset
        return this[addresses.indexOf(at)]
    }

    @Test
    fun `the colour on the dark branch goes through the extension and the light branch skips it`() {
        val app = app()
        assertEquals("sites changed", 1, with(app) { readDarkBranchLiterals(check) })

        val card = app.body("card")
        assertEquals(Opcode.CONST, card[3].opcode)
        assertEquals("the call", DARK_BRANCH, card[4].call())
        assertEquals("on the const's register", 1, (card[4] as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.MOVE_RESULT, card[5].opcode)
        assertEquals("into it", 1, (card[5] as OneRegisterInstruction).registerA)
        assertEquals("the dark branch then returns it", Opcode.RETURN, card[6].opcode)
        val goto = card.indexOfLast { it.opcode == Opcode.GOTO }
        assertEquals("the light branch jumps to the return, past the call", Opcode.RETURN, card.target(goto).opcode)
    }

    @Test
    fun `a resource id, the other branch order, a shared const and another check stay`() {
        val app = app()
        val before = listOf("resource", "lightFirst", "shared", "otherCheck").associateWith { app.body(it).map { i -> i.opcode } }
        with(app) { readDarkBranchLiterals(check) }
        for ((name, opcodes) in before) {
            assertEquals(name, opcodes, app.body(name).map { it.opcode })
            assertEquals("$name: no call", 0, app.body(name).count { it.call() == DARK_BRANCH })
        }
    }
}
