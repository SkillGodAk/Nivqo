/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The task label swap writes the app's name into the constructor's label register, so a call whose
 * label register is another of its arguments too, or is read again after the call, would hand that
 * name to something that never asked for it: a Bitmap slot fails verification. [taskLabels] takes
 * the plain shape 582 has and refuses those two.
 */
class TaskLabelShapesTest {
    private val holder = "Lfixture/TaskLabeler;"
    private val description = "Landroid/app/ActivityManager\$TaskDescription;"
    private val constructor = "$description-><init>(Ljava/lang/String;Landroid/graphics/Bitmap;I)V"

    private fun labeler(smali: String): ClassDef {
        val method = MutableMethod(
            ImmutableMethod(
                holder, "run", listOf(ImmutableMethodParameter("Landroid/app/Activity;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(5, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }.let(ImmutableMethod::of)
        return ImmutableClassDef(holder, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            emptyList(), listOf(method))
    }

    private fun made(label: String, call: String, after: String = "") = labeler("""
        $label
        const/4 v2, 0x0
        const/4 v3, 0x0
        new-instance v0, $description
        invoke-direct { $call }, $constructor
        $after
        invoke-virtual { p0, v0 }, Landroid/app/Activity;->setTaskDescription(${description})V
        return-void
    """)

    @Test
    fun `a label in a register of its own is taken`() {
        val labels = taskLabels(made("const-string v1, \"Facebook\"", "v0, v1, v2, v3"))
        assertEquals(1, labels.size)
        assertEquals(1, labels.single().label)
        assertEquals("the constructor's place", 4, labels.single().at)
    }

    @Test
    fun `a label register that is also the bitmap is refused`() {
        val failure = assertThrows(PatchException::class.java) {
            taskLabels(made("const/4 v1, 0x0", "v0, v1, v1, v3"))
        }
        assertTrue(failure.message!!, failure.message!!.contains("another argument"))
    }

    @Test
    fun `a label register read again after the call is refused`() {
        val failure = assertThrows(PatchException::class.java) {
            taskLabels(made("const-string v1, \"Facebook\"", "v0, v1, v2, v3",
                after = "invoke-static { v1 }, Lfixture/Log;->d(Ljava/lang/String;)V"))
        }
        assertTrue(failure.message!!, failure.message!!.contains("again after"))
    }
}
