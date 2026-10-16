package app.morphe.patches.facebook.chats

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The search screen row's Meta AI gate. The hook goes right before the button's test, so the gate
 * is refused when a jump lands on that test: the path through the jump would skip the hook.
 */
class PickerButtonGateTest {
    private val tap = "Lfixture/Tap;"

    private fun smali(body: String) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Row;", "render", listOf(ImmutableMethodParameter("Z", null, null)),
            "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(4, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    @Test
    fun `the flag's 1 and the button's test are found past the margin's test`() {
        val gate = pickerButtonGate(
            smali("""
                const/4 v0, 0x0
                if-eqz p0, :skip
                const/4 v0, 0x1
                :skip
                if-eqz v0, :nomargin
                const/4 v1, 0x2
                :nomargin
                const/4 v1, 0x3
                if-eqz v0, :done
                new-instance v2, $tap
                :done
                return-void
            """),
            tap,
        )
        // As on 582, the margin's test lands on the instruction before the button's test, not on it.
        assertEquals(listOf(2, 6, 0), listOf(gate?.set, gate?.test, gate?.register))
    }

    @Test
    fun `a jump landing on the button's test refuses the gate`() {
        val gate = pickerButtonGate(
            smali("""
                const/4 v0, 0x0
                if-eqz p0, :test
                const/4 v0, 0x1
                if-eqz v0, :nomargin
                const/4 v1, 0x2
                :nomargin
                const/4 v1, 0x3
                :test
                if-eqz v0, :done
                new-instance v2, $tap
                :done
                return-void
            """),
            tap,
        )
        assertNull(gate)
    }
}
