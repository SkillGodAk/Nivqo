/*
 * Adapted from Hushfacebook's RestoreTrustPatch.kt, itself forked from Andrew Liang's
 * morphe-patches (GPL-3.0). Modified for HushMessenger (Messenger), 2026.
 */
package app.hushmessenger.patches.coexist

import app.hushmessenger.patches.MessengerTarget
import app.hushmessenger.patches.controls.jumpTargets
import app.hushmessenger.patches.controls.settingsExtension
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"

private const val ORIGINAL_SIGNERS = "Lapp/hushmessenger/extension/MessengerSignature;->" +
    "originalSigners(Landroid/content/pm/PackageInfo;)Ljava/util/List;"

internal const val FBNS_SIGNERS = "Lapp/hushmessenger/extension/MessengerSignature;->" +
    "fbnsSigners(Landroid/content/pm/PackageInfo;[Landroid/content/pm/Signature;)[Landroid/content/pm/Signature;"

/**
 * Messenger's trust checks read the signers of a package through a method that prefers the
 * SigningInfo API and falls back to the old signatures array. It is the only no-parameter method
 * that calls both signer-list getters and reads the legacy array. Class and method names are Redex
 * obfuscated, so the fingerprint uses only Android framework references.
 */
internal object PackageSignersFingerprint : Fingerprint(
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/pm/SigningInfo;",
            name = "getApkContentsSigners",
        ),
        methodCall(
            definingClass = "Landroid/content/pm/SigningInfo;",
            name = "getSigningCertificateHistory",
        ),
        fieldAccess(
            definingClass = "Landroid/content/pm/PackageInfo;",
            name = "signatures",
        ),
    ),
)

/**
 * FBNS's check of a package it may hand pushes to: the SHA-256 of the package's one signer, looked
 * up in Meta's certificates. It reads `PackageInfo.signatures` itself and logs as PackageInfoUtil.
 * Its class and its name are Redex names.
 */
internal object FbnsPackageCheckFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        fieldAccess(
            definingClass = "Landroid/content/pm/PackageInfo;",
            name = "signatures",
        ),
        string("PackageInfoUtil"),
        string("Failed to create SHA-256 hash"),
    ),
)

@Suppress("unused")
val restoreTrustPatch = bytecodePatch(
    name = "Restore screens on re-signed builds",
    description = "Patching and signing Messenger yourself can break some of its screens. This makes Messenger's signature check pass again, including for a Facebook you patched with the same key. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Fixes")
    compatibleWith(MessengerTarget.COMPATIBILITY)
    dependsOn(settingsExtension)

    execute {
        val method = PackageSignersFingerprint.method
        val owner = mutableClassDefBy(method.definingClass)

        // The hook reads the field off `this` with iget-object, so it has to be an instance field.
        val packageInfoFields = owner.fields.filter { it.type == PACKAGE_INFO && !AccessFlags.STATIC.isSet(it.accessFlags) }
        check(packageInfoFields.size == 1) {
            "Expected 1 PackageInfo instance field on ${method.definingClass}, found ${packageInfoFields.size}"
        }
        val packageInfo = packageInfoFields.single().name

        val signers = method.returnType
        val constructor = mutableClassDefBy(signers).methods.singleOrNull {
            it.name == "<init>" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;", "Z", "Z")
        }
        check(constructor != null) { "$signers has no (List, boolean, boolean) constructor" }

        method.answerOriginalSigners(packageInfo, signers)
        FbnsPackageCheckFingerprint.method.routeFbnsSigners()
    }
}

/**
 * For Messenger itself, or a Facebook carrying this build's key while it calls Messenger, answer
 * with the original certificate and skip the body. For any other package or read, the extension
 * answers null and the body runs as before. The two flags are false, as the
 * body sets them for a single signer.
 *
 * The injection is at index 0, where no local is live yet, so v0 to v2 are free once the method is
 * known to have three locals. `iget-object` takes 4-bit registers, so `this` is copied down into v0
 * first: in a method with more than sixteen registers `p0` sits above v15, and the patcher's smali
 * compiler leaves out an instruction whose register doesn't fit, without a word.
 */
internal fun MutableMethod.answerOriginalSigners(packageInfo: String, signers: String) {
    // The smali below copies p0 as `this`, which a static method doesn't have.
    if (AccessFlags.STATIC.isSet(accessFlags)) throw PatchException(
        "Restore screens on re-signed builds: $definingClass->$name is static, expected an instance method"
    )
    val paramWidth = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
    val locals = implementation!!.registerCount - 1 - paramWidth
    if (locals < 3) throw PatchException(
        "Restore screens on re-signed builds: $definingClass->$name has $locals local register(s), needs 3"
    )
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            iget-object v0, v0, $definingClass->$packageInfo:$PACKAGE_INFO
            invoke-static { v0 }, $ORIGINAL_SIGNERS
            move-result-object v1
            if-eqz v1, :original
            new-instance v0, $signers
            const/4 v2, 0x0
            invoke-direct { v0, v1, v2, v2 }, $signers-><init>(Ljava/util/List;ZZ)V
            return-object v0
        """,
        ExternalLabel("original", getInstruction(0)),
    )
}

/**
 * FBNS, the push service Meta's apps share, checks each package it may hand pushes to against
 * Meta's certificates, reading `PackageInfo.signatures` itself. On a re-signed build Messenger
 * fails its own check. The array that read gives goes through the extension, which answers the
 * original certificate for this build and the system's answer for any other package.
 *
 * The call goes straight after the read and names its two registers. `invoke-static` takes 4-bit
 * registers, so both must be v15 or lower, and the read must not overwrite the package it read
 * from, which the call still needs. A jump landing straight after the read would skip the call.
 */
internal fun MutableMethod.routeFbnsSigners() {
    val code = implementation!!.instructions.toList()
    val reads = code.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as FieldReference).let {
                it.definingClass == PACKAGE_INFO && it.name == "signatures"
            }
    }
    val (index, read) = reads.singleOrNull()
        ?: throw PatchException("Restore screens on re-signed builds: expected one read of PackageInfo.signatures in FBNS's package check, found ${reads.size}")
    val signatures = (read as TwoRegisterInstruction).registerA
    val info = read.registerB
    if (signatures == info || signatures > 15 || info > 15) throw PatchException(
        "Restore screens on re-signed builds: FBNS's package check reads signatures into v$signatures from v$info, which the call can't name"
    )
    if (index + 1 in jumpTargets()) throw PatchException(
        "Restore screens on re-signed builds: FBNS's package check jumps past its signatures read"
    )
    addInstructions(
        index + 1,
        """
            invoke-static { v$info, v$signatures }, $FBNS_SIGNERS
            move-result-object v$signatures
        """,
    )
}
