/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File

/** FDS's dark check in [bundle], found the way the patch finds it: on the class of the view resolver FdsColorScheme asks. */
internal fun fdsDarkCheckOf(bundle: File): String {
    val scheme = FixtureDex.classes(bundle, setOf(FDS_COLOR_SCHEME)).values
    FdsSchemeResolveFingerprint.clearMatch()
    try {
        val resolver = with(PatchContexts.of(scheme)) {
            ((FdsSchemeResolveFingerprint.instructionMatches[1].instruction as ReferenceInstruction).reference as MethodReference)
                .definingClass
        }
        return with(PatchContexts.of(scheme + FixtureDex.classes(bundle, setOf(resolver)).values)) { fdsDarkCheck() }
    } finally {
        FdsSchemeResolveFingerprint.clearMatch()
    }
}
