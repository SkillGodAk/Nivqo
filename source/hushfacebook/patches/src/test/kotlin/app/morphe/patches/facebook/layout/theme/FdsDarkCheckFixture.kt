/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File

/**
 * The classes of [bundle] holding the short static wrappers some of [readers] ask [darkCheck] through
 * (583's armeabi-v7a link attachment asks `LX/RTh;->A1a(LX/1UQ;)Z`), so a patch context can resolve them.
 */
internal fun darkCheckWrappers(bundle: File, readers: List<Method>, darkCheck: String): List<ClassDef> {
    val callees = readers.flatMap { it.implementation?.instructions?.toList().orEmpty() }
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.returnType == "Z" }.map { it.definingClass }.toSet()
    return FixtureDex.classes(bundle, callees).values.filter { holder ->
        holder.methods.any { wrapper ->
            wrapper.implementation?.instructions?.toList()?.let { body ->
                body.size <= 6 && body.any { (it as? ReferenceInstruction)?.reference?.toString() == darkCheck }
            } == true
        }
    }
}

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
