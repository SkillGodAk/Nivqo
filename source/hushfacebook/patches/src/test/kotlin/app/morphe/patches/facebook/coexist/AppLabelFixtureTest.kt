/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.Fixtures
import app.morphe.patches.shared.compat.AppCompatibilities
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The App name option (#105) on every Facebook build the bundle declares, on the manifest as
 * Morphe decodes it for a resource patch: every launcher entry, the enabled one and each icon
 * choice, ends up with the picked name, and nothing but labels changes. AppLabelTest covers the
 * same on a hand-written manifest. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class AppLabelFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The base APK's manifest decoded by ARSCLib, the decoder Morphe's resource coder extends. */
    private fun decodedManifest(bundle: File): Document {
        val work = Files.createTempDirectory("hushfacebook-label").toFile()
        try {
            val apk = File(work, "base.apk")
            ZipFile(bundle).use { zip ->
                val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
                zip.getInputStream(base).use { input -> apk.outputStream().use { input.copyTo(it) } }
            }
            val decoded = File(work, "decoded")
            val module = ApkModule.loadApkFile(apk)
            try {
                ApkModuleXmlDecoder(module).decodeAndroidManifest(decoded)
            } finally {
                module.close()
            }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(decoded.walk().single { it.name == "AndroidManifest.xml" })
        } finally {
            work.deleteRecursively()
        }
    }

    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    private fun Document.launcherEntries() = (all("activity") + all("activity-alias")).filter { it.isLauncherEntry() }

    /** Every element's tag and attributes, labels left out, in document order. */
    private fun Document.everythingButLabels(): List<String> = all("*").map { element ->
        val attributes = element.attributes
        element.tagName + (0 until attributes.length).map { attributes.item(it) }
            .filter { it.nodeName != "android:label" }.joinToString(prefix = "[", postfix = "]") { "${it.nodeName}=${it.nodeValue}" }
    }

    @Test
    fun `each declared build names every launcher entry and changes nothing but labels`() = bundles { bundle ->
        val name = bundle.name
        val manifest = decodedManifest(bundle)
        val launchers = manifest.launcherEntries()
        // The enabled entry and the nine icon choices Facebook can turn on in its place.
        assertEquals("$name: launcher entries", 10, launchers.size)
        assertTrue("$name: a launcher entry has a label of its own", launchers.all { it.getAttribute("android:label").isEmpty() })
        val before = manifest.everythingButLabels()

        val reference = "@string/$APP_NAME_STRING"
        val relabeled = manifest.labelWith(reference)
        assertTrue("$name: only $relabeled entries and screens relabeled", relabeled >= launchers.size)
        assertEquals("$name: the application's label", reference, manifest.all("application").single().getAttribute("android:label"))
        manifest.launcherEntries().forEach {
            assertEquals("$name: ${it.getAttribute("android:name")}'s label", reference, it.getAttribute("android:label"))
        }
        assertEquals("$name: something besides a label changed", before, manifest.everythingButLabels())
    }
}
