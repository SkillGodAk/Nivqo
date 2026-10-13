package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val ATTRIBUTION_UPLOADS = "attribution_uploads"

/**
 * Messenger's ad attribution job. It keeps its name in every 582 build, and its one static worker does the whole job:
 * it reads the saved attribution state, gets the Advertising ID through Google's library or, failing that, a direct
 * Binder call to Play services' IAdvertisingIdService, reads the Facebook app's attribution, then sends all of it as an
 * AttributionIdUpdate request and saves the answer. Startup's background job list and one Runnable call it and wait for
 * nothing, so an early return leaves no retry or completion state behind.
 */
internal const val LAT_STATUS_JOB = "Lcom/facebook/attribution/LatStatusJob;"
internal const val ATTRIBUTION_WORKER = "$LAT_STATUS_JOB->A00($LAT_STATUS_JOB$FB_USER_SESSION)V"

/** The failure text the worker reports when the send fails. It names the request this control stops. */
internal const val ATTRIBUTION_SEND_MARK = "Failure while creating and sending attribution state through AttributionIdUpdate."

/** The Binder interface the worker falls back to. A hook that only covered Google's library would miss this route. */
internal const val AD_ID_SERVICE = "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"

private fun Method.stringLiterals() =
    implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet()

/** The static (LatStatusJob, FbUserSession)V worker that still holds both the send and the Binder fallback. */
internal fun Method.isAttributionUpload(): Boolean {
    if (definingClass != LAT_STATUS_JOB || !AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" ||
        parameterTypes.map { it.toString() } != listOf(LAT_STATUS_JOB, FB_USER_SESSION)) return false
    val literals = stringLiterals().orEmpty()
    return ATTRIBUTION_SEND_MARK in literals && AD_ID_SERVICE in literals
}

internal fun findAttributionUploads(classes: Iterable<ClassDef>): List<Method> =
    classes.filter { it.type == LAT_STATUS_JOB }.flatMap { it.methods }.filter { it.isAttributionUpload() }

internal fun MutableMethod.validateAttributionUpload() {
    if (!isAttributionUpload()) {
        throw PatchException("Messenger controls: ${hookId()} isn't the ad attribution upload")
    }
    validateSwitch()
}

/**
 * While the switch is on the worker returns before its first instruction, so it never reads the Advertising ID by
 * either route and never builds the request. The saved attribution state stays as it was, so a later run with the
 * switch off works as before. Off, Pause and safe mode run Messenger's own code.
 */
internal fun MutableMethod.injectAttributionUpload() {
    validateAttributionUpload()
    injectSwitch("stopAttributionUploads", "0x0")
}
