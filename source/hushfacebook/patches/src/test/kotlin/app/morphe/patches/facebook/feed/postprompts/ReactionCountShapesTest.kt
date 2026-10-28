/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The count beside a post's faces (#158), without a Facebook build: the summary's constructor is
 * taken only when the object it builds was made right before it and nothing reads the text's
 * register after it, so the extension's answer reaches that component and nothing else.
 */
class ReactionCountShapesTest {
    private val string = "Ljava/lang/String;"
    private val summary = "Lfixture/Summary;"

    private fun made(type: String = summary) = ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(type))

    private val built = ImmutableInstruction35c(
        Opcode.INVOKE_DIRECT, 3, 0, 1, 2, 0, 0,
        ImmutableMethodReference(summary, "<init>", listOf(string, string), "V"),
    )

    private val returned = ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)

    private fun render(vararg body: Instruction): Method = ImmutableMethod(
        "Lfixture/Header;",
        "render",
        emptyList(),
        "Ljava/lang/Object;",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null,
        null,
        ImmutableMethodImplementation(5, body.toList(), null, null),
    )

    @Test
    fun `a component made right before its constructor, with the text read nowhere after, is taken`() {
        assertEquals(1, reactionCountCall(render(made(), built, returned)))
    }

    @Test
    fun `a constructor with something else between it and the new-instance is left alone`() {
        assertNull(reactionCountCall(render(made(), ImmutableInstruction11n(Opcode.CONST_4, 3, 0), built, returned)))
    }

    @Test
    fun `a constructor right after a new-instance of another type is left alone`() {
        assertNull(reactionCountCall(render(made("Lfixture/Other;"), built, returned)))
    }

    @Test
    fun `a constructor whose text is read again after it is left alone`() {
        val logged = ImmutableInstruction35c(
            Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0,
            ImmutableMethodReference("Lfixture/Log;", "d", listOf(string), "V"),
        )
        assertNull(reactionCountCall(render(made(), built, logged, returned)))
    }
}
