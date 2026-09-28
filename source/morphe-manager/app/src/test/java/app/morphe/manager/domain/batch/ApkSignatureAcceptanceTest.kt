/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.batch

import android.os.Build
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether the queue may patch an attached APK unasked. It is the only thing
 * standing between a file handed over by another app and the patcher, so a wrong answer either
 * strands a legitimate download behind a warning or lets a modified APK through unremarked.
 */
class ApkSignatureAcceptanceTest {
    private val declared = setOf("aa", "bb")
    private val current = Build.VERSION_CODES.TIRAMISU

    @Test
    fun `an APK signed with one of the declared certificates is taken as is`() {
        assertTrue(apkSignatureAccepted(current, declared, setOf("bb")))
    }

    @Test
    fun `an APK signed by anyone else is held back`() {
        assertFalse(apkSignatureAccepted(current, declared, setOf("cc")))
    }

    @Test
    fun `an archive that carries no certificate at all is held back`() {
        assertFalse(apkSignatureAccepted(current, declared, emptySet()))
    }

    @Test
    fun `an archive that could not be opened is not held against it`() {
        assertTrue(apkSignatureAccepted(current, declared, null))
    }

    @Test
    fun `an app no source declares certificates for has nothing to be checked against`() {
        assertTrue(apkSignatureAccepted(current, null, setOf("cc")))
        assertTrue(apkSignatureAccepted(current, emptySet(), setOf("cc")))
    }

    @Test
    fun `Android 10 and below cannot read certificates from an archive, so nothing is claimed`() {
        assertTrue(apkSignatureAccepted(Build.VERSION_CODES.Q, declared, setOf("cc")))
        assertTrue(apkSignatureAccepted(Build.VERSION_CODES.P, declared, emptySet()))
    }
}
