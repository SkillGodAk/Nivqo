/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * See-through bars (#124) has no hook: the extension finds Facebook's main screen, its top bar and
 * its tab bar by name as the screen draws. So each name the extension holds has to be a class every
 * declared build keeps under that name, and the two bars have to be views, so a background is
 * theirs to have. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without them.
 */
class TranslucentBarsFixtureTest {
    private val extension = "Lapp/morphe/extension/facebook/navigation/TranslucentBars;"

    /** The framework views a bar may come down from. */
    private val frameworkViews = setOf(
        "Landroid/view/ViewGroup;", "Landroid/widget/FrameLayout;", "Landroid/widget/LinearLayout;",
        "Landroid/widget/RelativeLayout;",
    )

    private fun descriptor(name: String) = "L${name.replace('.', '/')};"

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    /** The first framework class above [type] in [bundle], following superclasses through Facebook's own. */
    private fun frameworkAncestor(bundle: File, type: String): String {
        var current = type
        repeat(16) {
            val classDef = FixtureDex.classes(bundle, setOf(current))[current] ?: return current
            current = classDef.superclass ?: return current
        }
        throw AssertionError("${bundle.name}: $type sits under more than 16 classes")
    }

    @Test
    fun `each declared build keeps the main screen and both bars under the names the extension finds them by`() {
        val names = listOf("MAIN_SCREEN", "TOP_BAR", "TAB_BAR").associateWith { ExtensionDex.stringConstant(extension, it) }
        assertEquals("com.facebook.katana.activity.FbMainTabActivity", names.getValue("MAIN_SCREEN"))
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val found = FixtureDex.classes(bundle, names.values.map(::descriptor).toSet())
                for ((field, name) in names) {
                    assertNotNull("${bundle.name}: no class $name for TranslucentBars.$field", found[descriptor(name)])
                }
                for (bar in listOf("TOP_BAR", "TAB_BAR")) {
                    val type = descriptor(names.getValue(bar))
                    val framework = frameworkAncestor(bundle, type)
                    assertTrue("${bundle.name}: $type comes down from $framework, not a framework view group",
                        framework in frameworkViews)
                }
                assertEquals("${bundle.name}: the main screen is a Facebook activity", "Lcom/facebook/base/activity/FbFragmentActivity;",
                    generateSequence(descriptor(names.getValue("MAIN_SCREEN"))) { type ->
                        FixtureDex.classes(bundle, setOf(type))[type]?.superclass
                    }.firstOrNull { it == "Lcom/facebook/base/activity/FbFragmentActivity;" })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
