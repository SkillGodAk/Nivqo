/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrubber's length checks overridden below it (#73): 582's Reels tab scrubber extends the one
 * the patch finds and answers both checks itself, so only hooks on those overrides reach its reels.
 * An override of the base's light mount has to call the base's, or the next reel's reset is skipped.
 */
class LengthCheckOverridesTest {
    private companion object {
        const val SCRUBBER = "Lfixture/Scrubber;"
        const val BASE = "Lfixture/ScrubberBase;"
    }

    private fun check(owner: String, name: String, static: Boolean = false, abstract: Boolean = false): Method =
        ImmutableMethod(
            owner,
            name,
            listOf(ImmutableMethodParameter("I", null, null), ImmutableMethodParameter("Z", null, null)),
            "Z",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or
                (if (abstract) AccessFlags.ABSTRACT.value else 0),
            null,
            null,
            if (abstract) {
                null
            } else {
                ImmutableMethodImplementation(
                    4,
                    listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)),
                    null,
                    null,
                )
            },
        )

    private fun type(name: String, superclass: String, vararg methods: Method): ClassDef =
        ImmutableClassDef(name, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.toList())

    private val minimum = check(SCRUBBER, "A1O")
    private val short = check(SCRUBBER, "A1N")
    private val scrubber = type(SCRUBBER, BASE, minimum, short)

    private fun overrides(vararg candidates: ClassDef): List<String> {
        val all = (listOf(scrubber) + candidates).associate { it.type to it.superclass }
        return lengthCheckOverrides(candidates.toList(), SCRUBBER, listOf(minimum, short)) { all[it] }
            .map { (type, method) -> "$type->${method.name}" }
    }

    @Test
    fun `a subclass overriding both checks has both hooked`() {
        val reels = type("Lfixture/ReelsScrubber;", SCRUBBER, check("Lfixture/ReelsScrubber;", "A1N"), check("Lfixture/ReelsScrubber;", "A1O"))
        assertEquals(listOf("Lfixture/ReelsScrubber;->A1N", "Lfixture/ReelsScrubber;->A1O"), overrides(reels))
    }

    @Test
    fun `a class further down is found through its superclasses`() {
        val middle = type("Lfixture/Middle;", SCRUBBER)
        val bottom = type("Lfixture/Bottom;", "Lfixture/Middle;", check("Lfixture/Bottom;", "A1O"))
        assertEquals(listOf("Lfixture/Bottom;->A1O"), overrides(middle, bottom))
    }

    @Test
    fun `the scrubber itself and classes outside its hierarchy are left alone`() {
        val unrelated = type("Lfixture/Unrelated;", "Ljava/lang/Object;", check("Lfixture/Unrelated;", "A1O"))
        val base = type(BASE, "Ljava/lang/Object;", check(BASE, "A1O"))
        assertEquals(emptyList<String>(), overrides(scrubber, unrelated, base))
    }

    @Test
    fun `a static or abstract method of the same shape isn't an override to hook`() {
        val reels = type(
            "Lfixture/ReelsScrubber;",
            SCRUBBER,
            check("Lfixture/ReelsScrubber;", "A1O", static = true),
            check("Lfixture/ReelsScrubber;", "A1N", abstract = true),
            check("Lfixture/ReelsScrubber;", "A1P"),
        )
        assertEquals(emptyList<String>(), overrides(reels))
    }

    private fun mount(owner: String, callsSuper: Boolean): Method = ImmutableMethod(
        owner,
        "A0m",
        listOf(ImmutableMethodParameter("Lfixture/Video;", null, null)),
        "V",
        AccessFlags.PUBLIC.value,
        null,
        null,
        ImmutableMethodImplementation(
            2,
            listOfNotNull(
                if (callsSuper) {
                    ImmutableInstruction35c(
                        Opcode.INVOKE_SUPER, 2, 0, 1, 0, 0, 0,
                        ImmutableMethodReference(BASE, "A0m", listOf("Lfixture/Video;"), "V"),
                    )
                } else {
                    null
                },
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
            null,
            null,
        ),
    )

    private fun skippingBase(vararg candidates: ClassDef): List<String> {
        val all = (listOf(type(BASE, "Ljava/lang/Object;")) + scrubber + candidates).associate { it.type to it.superclass }
        return mountOverridesSkippingBase(candidates.toList(), BASE, mount(BASE, callsSuper = false)) { all[it] }
    }

    @Test
    fun `a scrubber overriding the light mount without calling the base's is caught`() {
        val own = type(SCRUBBER, BASE, mount(SCRUBBER, callsSuper = false))
        val reels = type("Lfixture/ReelsScrubber;", SCRUBBER, mount("Lfixture/ReelsScrubber;", callsSuper = false))
        assertEquals(listOf(SCRUBBER, "Lfixture/ReelsScrubber;"), skippingBase(own, reels))
    }

    @Test
    fun `an override calling the base's light mount still gets the reset`() {
        val own = type(SCRUBBER, BASE, mount(SCRUBBER, callsSuper = true))
        val unrelated = type("Lfixture/Unrelated;", "Ljava/lang/Object;", mount("Lfixture/Unrelated;", callsSuper = false))
        assertEquals(emptyList<String>(), skippingBase(own, unrelated))
    }
}
