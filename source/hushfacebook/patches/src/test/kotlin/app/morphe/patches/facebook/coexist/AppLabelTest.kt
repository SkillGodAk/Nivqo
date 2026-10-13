/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.coexist

import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hushfacebook settings' App name option (#105). 582's manifest lists ten launcher aliases of
 * FbMainTabActivity (one enabled, nine icon choices) with no label of their own, and a few screens
 * labeled with the application's label. A picked name points all of them, and the application, at
 * one quoted string resource. Nothing but a label changes, and no name picked changes nothing.
 */
class AppLabelTest {
    private companion object {
        const val STOCK = "@string/app_name"
        const val PICKED = "@string/$APP_NAME_STRING"
        val ICONS = listOf("worldcup_ic", "fab_ic", "vaporwave_ic", "fierce_ic", "loved_ic", "dreamy_ic", "quiet_cool", "katana", "blue_fb")
    }

    private fun alias(name: String, enabled: Boolean) = """
        <activity-alias android:name="com.facebook.katana.LoginActivity$name" android:enabled="$enabled"
                android:exported="true" android:icon="@mipmap/ic_launcher"
                android:targetActivity="com.facebook.katana.activity.FbMainTabActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity-alias>
    """

    private fun facebook(): Document = parse(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana">
            <permission android:name="com.facebook.katana.permission.CROSS_PROCESS_BROADCAST_MANAGER" android:protectionLevel="signature"/>
            <application android:label="$STOCK" android:icon="@mipmap/ic_launcher" android:name="com.facebook.katana.app.FacebookApplication">
                <activity android:name="com.facebook.katana.activity.FbMainTabActivity" android:label="$STOCK" android:exported="true"/>
                <activity android:name="com.facebook.katana.CellularDataUsage" android:label="Cellular Data Usage"/>
                <activity android:name="com.facebook.deeplinks.Links" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.VIEW"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.DEFAULT"/>
                    </intent-filter>
                </activity>
                <provider android:name="com.facebook.katana.provider.AttributionIdProvider"
                        android:authorities="com.facebook.katana.provider.AttributionIdProvider" android:exported="true"/>
                ${ICONS.joinToString("") { alias(".$it", enabled = false) }}
                ${alias("", enabled = true)}
            </application>
        </manifest>
        """,
    )

    // Not namespace aware, the way the patcher's document reads a decoded file: the attribute is
    // called android:label, prefix and all.
    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trimIndent().byteInputStream())

    private fun Document.all(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Document.named(name: String) = (all("activity") + all("activity-alias")).single { it.getAttribute("android:name") == name }

    /** Every attribute of every element but android:label, in document order. */
    private fun Document.everythingButLabels(): List<String> = all("*").flatMap { element ->
        val attributes = element.attributes
        (0 until attributes.length).map { attributes.item(it) }
            .filter { it.nodeName != "android:label" }
            .map { "${element.tagName} ${it.nodeName}=${it.nodeValue}" }
    }

    @Test
    fun `a picked name labels the application, every launcher alias and the screens that showed Facebook's name`() {
        val manifest = facebook()
        val before = manifest.everythingButLabels()
        assertEquals("ten aliases and the main tab screen", 11, manifest.labelWith(PICKED))

        assertEquals(PICKED, manifest.all("application").single().getAttribute("android:label"))
        for (icon in ICONS.map { ".$it" } + "") {
            assertEquals("the LoginActivity$icon alias", PICKED, manifest.named("com.facebook.katana.LoginActivity$icon").getAttribute("android:label"))
        }
        assertEquals(PICKED, manifest.named("com.facebook.katana.activity.FbMainTabActivity").getAttribute("android:label"))
        assertEquals("a screen with its own name keeps it", "Cellular Data Usage",
            manifest.named("com.facebook.katana.CellularDataUsage").getAttribute("android:label"))
        // MAIN and LAUNCHER in two different filters is not a launcher entry.
        assertEquals("", manifest.named("com.facebook.deeplinks.Links").getAttribute("android:label"))
        assertEquals("names, exported, enabled, permissions, authorities and the package stay", before, manifest.everythingButLabels())
    }

    @Test
    fun `a manifest with no launcher entry or no application label is refused before a label changes`() {
        val noLauncher = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana">
                <application android:label="$STOCK"><activity android:name="com.example.Only" android:label="$STOCK"/></application>
            </manifest>
            """,
        )
        assertThrows(PatchException::class.java) { noLauncher.labelWith(PICKED) }
        assertEquals(STOCK, noLauncher.all("application").single().getAttribute("android:label"))
        assertEquals(STOCK, noLauncher.named("com.example.Only").getAttribute("android:label"))

        val noLabel = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.facebook.katana">
                <application>${alias("", enabled = true)}</application>
            </manifest>
            """,
        )
        assertThrows(PatchException::class.java) { noLabel.labelWith(PICKED) }
        assertEquals("", noLabel.named("com.facebook.katana.LoginActivity").getAttribute("android:label"))
    }

    @Test
    fun `no name picked keeps Facebook's labels, and a picked one is trimmed and held to what a label can be`() {
        for (blank in listOf(null, "", "   ")) assertNull("picked: \"$blank\"", pickedAppName(blank))
        assertEquals("HushBook", pickedAppName("  HushBook  "))
        assertEquals("x".repeat(MAX_APP_NAME), pickedAppName("x".repeat(MAX_APP_NAME)))
        // An emoji is one character to the reader, two Java chars.
        assertEquals("\uD83D\uDE00".repeat(MAX_APP_NAME), pickedAppName("\uD83D\uDE00".repeat(MAX_APP_NAME)))
        for (refused in listOf("x".repeat(MAX_APP_NAME + 1), "Hush\nBook", "Hush\tBook")) {
            assertThrows("accepted: $refused", PatchException::class.java) { pickedAppName(refused) }
        }
    }

    @Test
    fun `the resource reads back as typed, punctuation, references and all`() {
        assertEquals("\"HushBook\"", resourceText("HushBook"))
        assertEquals("\"Matt's FB\"", resourceText("Matt's FB"))
        assertEquals("\"Say \\\"hi\\\" \\\\ bye\"", resourceText("Say \"hi\" \\ bye"))
        assertEquals("\"50% off & <more>\"", resourceText("50% off & <more>"))
        assertEquals("\"\\@home\"", resourceText("@home"))
        assertEquals("\"\\?attr\"", resourceText("?attr"))
        assertEquals("\"Facebook \u00e9t\u00e9 \u0444\u0435\u0439\u0441\"", resourceText("Facebook \u00e9t\u00e9 \u0444\u0435\u0439\u0441"))
    }

    @Test
    fun `the string resource is added once, untranslated and unformatted, and rewritten on a second pick`() {
        val strings = parse("""<resources><string name="app_name">Facebook</string></resources>""")
        strings.putAppName("Hush & Book")
        strings.putAppName("HushBook")
        val picked = strings.all("string").filter { it.getAttribute("name") == APP_NAME_STRING }
        assertEquals(1, picked.size)
        assertEquals("\"HushBook\"", picked.single().textContent)
        assertEquals("false", picked.single().getAttribute("translatable"))
        assertEquals("false", picked.single().getAttribute("formatted"))
        assertEquals("Facebook", strings.all("string").single { it.getAttribute("name") == "app_name" }.textContent)
    }
}
