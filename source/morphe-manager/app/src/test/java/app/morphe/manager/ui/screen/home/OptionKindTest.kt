/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import app.morphe.manager.patcher.patch.Option
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app runs without full Kotlin reflection, so the editor is picked from the declared type alone. */
class OptionKindTest {
    @Test
    fun `an untyped option gets the editor of its declared type`() {
        assertEquals(OptionKind.BooleanToggle, kindOf(typeOf<Boolean>()))
        assertEquals(OptionKind.IntLong, kindOf(typeOf<Int>()))
        assertEquals(OptionKind.IntLong, kindOf(typeOf<Long>()))
        assertEquals(OptionKind.FloatDouble, kindOf(typeOf<Float>()))
        assertEquals(OptionKind.FloatDouble, kindOf(typeOf<Double>()))
        assertEquals(OptionKind.StringText, kindOf(typeOf<String>()))
        assertEquals(OptionKind.StringText, kindOf(typeOf<String?>()))
        assertEquals(OptionKind.StringList, kindOf(typeOf<List<String>>()))
    }

    @Test
    fun `an array option is a dropdown whatever its elements`() {
        assertEquals(OptionKind.ArrayDropdown, kindOf(typeOf<Array<String>>()))
        assertEquals(OptionKind.ArrayDropdown, kindOf(typeOf<IntArray>()))
        assertEquals(OptionKind.ArrayDropdown, kindOf(typeOf<Array<Int>>()))
    }

    @Test
    fun `a list of numbers without slider bounds falls back to the numeric field`() {
        assertEquals(OptionKind.IntLong, kindOf(typeOf<List<Int>>()))
        assertEquals(OptionKind.FloatDouble, kindOf(typeOf<List<Float>>()))
    }

    @Test
    fun `a string option with presets is a dropdown`() {
        assertEquals(OptionKind.StringDropdown, kindOf(typeOf<String>(), presets = mapOf("A" to "a")))
    }

    private fun kindOf(type: KType, presets: Map<String, Any?>? = null) = resolveOptionKind(
        Option(
            title = "Title",
            key = "key",
            description = "",
            required = false,
            type = type,
            default = null,
            presets = presets,
            validator = { true }
        ),
        value = null
    )
}
