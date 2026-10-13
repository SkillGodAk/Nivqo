package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val SYSTEM_CAMERA = "system_camera"
internal const val MONTAGE_PARAMS = "Lcom/facebook/messaging/montage/composer/model/MontageComposerFragmentParams;"
internal const val NAVIGATION_TRIGGER = "Lcom/facebook/messaging/send/trigger/NavigationTrigger;"
internal const val MONTAGE_ACTIVITY = "Lcom/facebook/messaging/montage/composer/MontageComposerActivity;"
/** The request code the chat composer starts Messenger's own camera with, and reads its sent message back under. */
internal const val CAMERA_REQUEST = 7377
/** The request code Messenger 582's chat screen starts Messenger's own camera with, through its activity launcher. */
internal const val CHAT_CAMERA_REQUEST = 7376
/** The request code a photo picked in another app comes back under, which opens Messenger's editor for the open chat. */
internal const val EXTERNAL_MEDIA_REQUEST = 1112
internal const val EXTERNAL_MEDIA_NULL_DATA = "ComposeFragment:externalMediaGalleryActivityResultNullData"
internal const val CAMERA_NULL_DATA = "ComposeFragment:montageMessageActivityResultNullData"
internal const val CAMERA_ACTIVITY = "app.hushmessenger.extension.CameraActivity"
internal const val CAMERA_PROVIDER = "app.hushmessenger.extension.CameraProvider"
/** The capture provider's authority is the package name plus this, the way the extension looks it up. */
internal const val CAMERA_AUTHORITY_SUFFIX = ".hush.camera"
private const val CAMERA_INTENT_CALL = "$SETTINGS->systemCamera(Landroid/content/Intent;)Landroid/content/Intent;"
private const val CAMERA_REQUEST_CALL = "$SETTINGS->cameraRequestCode(Landroid/content/Intent;I)I"
private const val CHAT_CAMERA_MARK_CALL = "$SETTINGS->markChatCamera(Landroid/os/Bundle;)V"
private const val CHAT_CAMERA_CALL = "$SETTINGS->chatCamera(Landroid/content/Intent;)Landroid/content/Intent;"
private const val TRUST_CAPTURE_CALL = "$SETTINGS->trustCapturedPhoto(Landroid/net/Uri;)Z"
private const val INTERNAL_FILE_CALL = "$SETTINGS->internalFile(ZLandroid/net/Uri;)Z"
private const val INTERNAL_FILE = "Attempted to retrieve internal file."
private const val BUNDLE = "Landroid/os/Bundle;"
private const val BUNDLE_COPY = "$BUNDLE-><init>($BUNDLE)V"

private fun cameraChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the chat camera launch moved ($detail)")

private fun Instruction.literal() = (this as? NarrowLiteralInstruction)?.narrowLiteral
private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.isCameraClass() = opcode == Opcode.CONST_CLASS &&
    ((this as ReferenceInstruction).reference as? TypeReference)?.type == MONTAGE_ACTIVITY
private fun Method.strings() = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet().orEmpty()

/** Messenger's static helper that builds the camera screen's intent from the composer's params. */
private fun MethodReference.isCameraIntent() = definingClass == MONTAGE_ACTIVITY && returnType == "Landroid/content/Intent;" &&
    parameterTypes.map { it.toString() } == listOf("Landroid/content/Context;", MONTAGE_PARAMS, NAVIGATION_TRIGGER)

/** Messenger's in-app launcher: start this intent for a result on the chat's fragment. */
private fun MethodReference.isFragmentLauncher() = returnType == "Z" &&
    parameterTypes.map { it.toString() } == listOf("Landroid/content/Intent;", ANDROIDX_FRAGMENT, "I")

/** The chat composer's camera listener: it builds Messenger's camera intent and starts it with [CAMERA_REQUEST]. */
internal fun Method.isCameraLaunch(): Boolean {
    if (returnType != "V" || parameterTypes.map { it.toString() } != listOf(MONTAGE_PARAMS, NAVIGATION_TRIGGER) ||
        AccessFlags.STATIC.isSet(accessFlags)) return false
    val code = implementation?.instructions ?: return false
    return code.any { it.literal() == CAMERA_REQUEST } && code.any { it.methodRef()?.isCameraIntent() == true }
}

/**
 * The chat's fragment reads both results: Messenger's camera hands back a finished message under [CAMERA_REQUEST], and
 * a photo picked in another app comes back under [EXTERNAL_MEDIA_REQUEST] as a content URI, which Messenger copies and
 * opens in its own editor for the open chat. The switch relies on that second path, so it needs exactly one such reader.
 */
internal fun Method.isComposerResult(): Boolean {
    if (name != "onActivityResult" || returnType != "V" || parameterTypes.map { it.toString() } != listOf("I", "I", "Landroid/content/Intent;")) return false
    val code = implementation?.instructions ?: return false
    val strings = code.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()
    return code.any { it.literal() == CAMERA_REQUEST } && code.any { it.literal() == EXTERNAL_MEDIA_REQUEST } &&
        EXTERNAL_MEDIA_NULL_DATA in strings && CAMERA_NULL_DATA in strings
}

/**
 * Messenger 582's chat screen asks this static factory for its camera button (#40). It copies the camera screen's extras
 * into an activity launcher that starts [MONTAGE_ACTIVITY] with [CHAT_CAMERA_REQUEST] on the chat's fragment. Story and
 * note replies build the same kind of launcher with the same request code elsewhere, so only this one gets marked.
 */
internal fun Method.isChatCameraFactory(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.firstOrNull()?.toString() != "Landroid/content/Context;") return false
    val code = implementation?.instructions ?: return false
    val strings = strings()
    return "fragment_params" in strings && "trigger2" in strings &&
        code.any { it.literal() == CHAT_CAMERA_REQUEST } && code.any { it.isCameraClass() }
}

/** The launcher type the factory builds: what its builder returns right after it's handed the camera screen's class. */
internal fun Method.chatCameraLauncherType(): String? {
    val code = implementation?.instructions?.toList() ?: return null
    val at = code.indexOfFirst { it.isCameraClass() }
    val store = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.IPUT_OBJECT } as? ReferenceInstruction ?: return null
    val builder = (store.reference as FieldReference).definingClass
    return code.drop(at + 2).firstNotNullOfOrNull { insn ->
        insn.methodRef()?.takeIf { insn.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == builder }?.returnType
    }
}

/** The launcher's start for a result: it builds the intent from its class and extras and starts it on its fragment. */
internal fun Method.isLauncherStart(): Boolean = returnType == "Z" && parameterTypes.map { it.toString() } == listOf(BUNDLE) &&
    !AccessFlags.STATIC.isSet(accessFlags) && "ActivityLauncher" in strings() &&
    implementation?.instructions?.any { it.methodRef()?.isFragmentLauncher() == true } == true

/**
 * Messenger 582's chat copies a photo picked in another app only once this check says the URI's provider belongs to an
 * app outside Meta's. The capture screen's provider lives inside Messenger, so without the hook its photo is refused
 * with a SecurityException that closes Messenger (#40).
 */
internal fun Method.isThirdPartyUriCheck(): Boolean = !AccessFlags.STATIC.isSet(accessFlags) &&
    returnType == "Ljava/lang/Boolean;" &&
    parameterTypes.map { it.toString() } == listOf("Landroid/content/Context;", "Landroid/net/Uri;") &&
    strings().let { "Unable to get providerInfo for authority " in it && "content" in it }

/**
 * The copy then opens the photo through this static helper, which refuses a file Messenger itself owns. Every file the
 * capture screen's provider serves is one, since the provider runs inside Messenger (#40).
 */
internal fun Method.isInternalFileOpen(): Boolean = AccessFlags.STATIC.isSet(accessFlags) &&
    returnType == "Landroid/content/res/AssetFileDescriptor;" &&
    parameterTypes.map { it.toString() } == listOf("Landroid/content/Context;", "Landroid/net/Uri;") && INTERNAL_FILE in strings()

/**
 * Every camera listener, plus the chat camera's factory, its launcher's start and the two checks the chat runs before
 * it copies the photo, or none when the chat fragment no longer reads a photo from another app. The last four come only
 * while that same reader also reads the chat camera's request code, so the photo goes back to the chat that asked for it.
 */
internal fun findSystemCamera(classes: Iterable<ClassDef>): List<Method> {
    val methods = classes.flatMap { it.methods }
    val readers = methods.filter { it.isComposerResult() }
    if (readers.size != 1) return emptyList()
    val listeners = methods.filter { it.isCameraLaunch() }
    if (readers.single().implementation!!.instructions.none { it.literal() == CHAT_CAMERA_REQUEST }) return listeners
    val factories = methods.filter { it.isChatCameraFactory() }
    val launcher = factories.singleOrNull()?.chatCameraLauncherType()
    return listeners + factories + methods.filter { it.definingClass == launcher && it.isLauncherStart() } +
        methods.filter { it.isThirdPartyUriCheck() } + methods.filter { it.isInternalFileOpen() }
}

/**
 * Index of the launcher call that starts Messenger's camera:
 *
 *     invoke-static {vContext, p1, p2}, MontageComposerActivity->A1E(Context, Params, Trigger)Intent
 *     move-result-object vIntent
 *     ...                                   (the launcher and the chat fragment)
 *     const/16 vCode, 7377
 *     invoke-virtual {vLauncher, vIntent, vFragment, vCode}, <launcher>(Intent, Fragment, I)Z   <- returned
 *     return-void
 *
 * The listener only starts the camera, so the launcher's answer is dropped and nothing else reads the intent.
 */
internal fun Method.cameraLaunchSite(): Int {
    val code = implementation?.instructions?.toList() ?: cameraChanged("no code")
    val builds = code.indices.filter { code[it].methodRef()?.isCameraIntent() == true }
    val b = builds.singleOrNull() ?: cameraChanged("${builds.size} camera intents")
    val build = code[b] as FiveRegisterInstruction
    val params = implementation!!.registerCount - 2
    if (code[b].opcode != Opcode.INVOKE_STATIC || build.registerCount != 3 || build.registerD != params || build.registerE != params + 1) {
        cameraChanged("camera intent arguments")
    }
    val moved = code.getOrNull(b + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
        ?: cameraChanged("camera intent result")
    val intent = moved.registerA
    val launches = code.indices.filter { code[it].methodRef()?.isFragmentLauncher() == true }
    val l = launches.singleOrNull() ?: cameraChanged("${launches.size} launches")
    val launch = code[l] as FiveRegisterInstruction
    val request = code[l - 1]
    if (code[l].opcode != Opcode.INVOKE_VIRTUAL || launch.registerCount != 4 || launch.registerD != intent ||
        request.opcode != Opcode.CONST_16 || request.literal() != CAMERA_REQUEST ||
        (request as OneRegisterInstruction).registerA != launch.registerF) cameraChanged("launch arguments")
    if (code.getOrNull(l + 1)?.opcode != Opcode.RETURN_VOID || l + 2 != code.size) cameraChanged("after the launch")
    if (code.count { it.literal() == CAMERA_REQUEST } != 1) cameraChanged("request codes")
    // Nothing between building the intent and the launch may write the intent register, and nothing may jump in.
    for (at in b + 2 until l) {
        if (!code[at].opcode.setsRegister()) continue
        val written = (code[at] as? OneRegisterInstruction)?.registerA ?: continue
        if (written == intent || (code[at].opcode.setsWideRegister() && written + 1 == intent)) cameraChanged("intent register reused")
    }
    if (jumpTargets().any { it in b + 1..l }) cameraChanged("a branch lands on the launch")
    // The helper calls are plain invokes, so both registers stay at v15 or below.
    if (intent > 15 || launch.registerF > 15 || intent == launch.registerF) cameraChanged("registers out of range")
    return l
}

/**
 * Index right after the factory copies the camera screen's extras for its launcher, where the copy gets marked:
 *
 *     new-instance vExtras, Landroid/os/Bundle;
 *     invoke-direct {vExtras, p1}, Landroid/os/Bundle;-><init>(Landroid/os/Bundle;)V
 *     iput-object vExtras, vBuilder, <builder>->extras    <- returned
 */
internal fun Method.chatCameraMarkSite(): Int {
    val code = implementation?.instructions?.toList() ?: cameraChanged("no code")
    val copies = code.indices.filter { code[it].opcode == Opcode.INVOKE_DIRECT && code[it].methodRef()?.toString() == BUNDLE_COPY }
    val c = copies.singleOrNull() ?: cameraChanged("${copies.size} launcher extras")
    val extras = (code[c] as FiveRegisterInstruction).registerC
    val store = code.getOrNull(c + 1)
    if (store?.opcode != Opcode.IPUT_OBJECT || (store as TwoRegisterInstruction).registerA != extras ||
        ((store as ReferenceInstruction).reference as FieldReference).type != BUNDLE) cameraChanged("launcher extras")
    if (code.count { it.literal() == CHAT_CAMERA_REQUEST } != 1 || code.count { it.isCameraClass() } != 1) cameraChanged("chat camera launcher")
    if (c + 1 in jumpTargets()) cameraChanged("a branch lands on the launcher extras")
    if (extras > 15) cameraChanged("launcher extras register out of range")
    return c + 1
}

/**
 * Index of the launcher's start, where every intent it starts for a result goes past the extension:
 *
 *     invoke-static {vContext, vClass}, <helper>(Context, Class)Intent
 *     move-result-object vIntent
 *     ...                                   (its extras)
 *     iget vCode, p0, <launcher>->requestCode
 *     invoke-static {vIntent, vFragment, vCode}, <launcher>(Intent, Fragment, I)Z   <- returned
 *     move-result vStarted
 */
internal fun Method.chatCameraLaunchSite(): Int {
    val code = implementation?.instructions?.toList() ?: cameraChanged("no code")
    val starts = code.indices.filter { code[it].methodRef()?.isFragmentLauncher() == true }
    val l = starts.singleOrNull() ?: cameraChanged("${starts.size} launcher starts")
    val start = code[l] as FiveRegisterInstruction
    if (code[l].opcode != Opcode.INVOKE_STATIC || start.registerCount != 3) cameraChanged("launcher start arguments")
    val intent = start.registerC
    val request = start.registerE
    val read = code.getOrNull(l - 1)
    if (read?.opcode != Opcode.IGET || (read as TwoRegisterInstruction).registerA != request) cameraChanged("launcher request code")
    if (code.getOrNull(l + 1)?.opcode != Opcode.MOVE_RESULT) cameraChanged("after the launcher start")
    if (l in jumpTargets()) cameraChanged("a branch lands on the launcher start")
    if (intent > 15 || request > 15 || intent == request) cameraChanged("launcher registers out of range")
    return l
}

/**
 * Index of the open's refusal, where its answer on whether Messenger owns the file goes past the extension first:
 *
 *     invoke-static {vDescriptor}, <helper>(ParcelFileDescriptor)Z
 *     move-result vInternal
 *     if-nez vInternal, :refuse        <- returned
 */
internal fun Method.internalFileSite(): Int {
    val code = implementation?.instructions?.toList() ?: cameraChanged("no code")
    val checks = code.indices.filter { at ->
        code[at].opcode == Opcode.INVOKE_STATIC && code[at].methodRef()?.let {
            it.returnType == "Z" && it.parameterTypes.map { type -> type.toString() } == listOf("Landroid/os/ParcelFileDescriptor;")
        } == true
    }
    val c = checks.singleOrNull() ?: cameraChanged("${checks.size} internal file checks")
    val internal = (code.getOrNull(c + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction)?.registerA
        ?: cameraChanged("internal file answer")
    val refuse = code.getOrNull(c + 2)
    if (refuse?.opcode != Opcode.IF_NEZ || (refuse as OneRegisterInstruction).registerA != internal) cameraChanged("internal file refusal")
    // The URI is the last parameter, and nothing before the refusal may write it.
    val uri = implementation!!.registerCount - 1
    for (at in 0..c + 1) {
        if (!code[at].opcode.setsRegister()) continue
        val written = (code[at] as? OneRegisterInstruction)?.registerA ?: continue
        if (written == uri || (code[at].opcode.setsWideRegister() && written + 1 == uri)) cameraChanged("uri register reused")
    }
    if (jumpTargets().any { it in c + 1..c + 2 }) cameraChanged("a branch lands on the internal file refusal")
    if (internal > 15 || uri > 15) cameraChanged("internal file registers out of range")
    return c + 2
}

internal fun MutableMethod.validateSystemCamera(): Int = when {
    isChatCameraFactory() -> chatCameraMarkSite()
    isLauncherStart() -> chatCameraLaunchSite()
    // The URI is the last parameter, and the hook passes it to a plain invoke.
    isThirdPartyUriCheck() -> 0.also {
        validateScratch()
        if (implementation!!.registerCount - 1 > 15) cameraChanged("uri register out of range")
    }
    isInternalFileOpen() -> internalFileSite()
    AccessFlags.STATIC.isSet(accessFlags) || !isCameraLaunch() -> throw PatchException("Messenger controls: unexpected camera listener ${hookId()}")
    else -> cameraLaunchSite()
}

/**
 * Swaps the intent and its request code right before the launch. With the switch on, the intent opens the extension's
 * capture screen, which hands the phone camera's photo back the way another app's picked photo comes back, so the chat
 * fragment's own code opens it in Messenger's editor for this chat. Off, Pause and safe mode get Messenger's camera.
 * Messenger 582's chat screen starts its camera through the activity launcher instead, so its factory marks that
 * launcher's extras, and the launcher's start swaps only an intent that carries the mark. Its checks on where a picked
 * photo comes from and who owns its file let the capture screen's own photo through, and keep Messenger's answer for
 * the rest.
 */
internal fun MutableMethod.injectSystemCamera() {
    val at = validateSystemCamera()
    if (isInternalFileOpen()) {
        val internal = (implementation!!.instructions.elementAt(at) as OneRegisterInstruction).registerA
        addInstructions(at, """
            invoke-static {v$internal, p1}, $INTERNAL_FILE_CALL
            move-result v$internal
        """.trimIndent())
        return
    }
    if (isThirdPartyUriCheck()) {
        addInstructionsWithLabels(0, """
            invoke-static {p2}, $TRUST_CAPTURE_CALL
            move-result v0
            if-eqz v0, :stock_behavior
            sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
            return-object v0
        """.trimIndent(), ExternalLabel("stock_behavior", getInstruction(0)))
        return
    }
    val site = implementation!!.instructions.elementAt(at)
    if (isChatCameraFactory()) {
        val extras = (implementation!!.instructions.elementAt(at - 1) as FiveRegisterInstruction).registerC
        addInstructions(at, "invoke-static {v$extras}, $CHAT_CAMERA_MARK_CALL")
        return
    }
    val chat = isLauncherStart()
    val launch = site as FiveRegisterInstruction
    val intent = if (chat) launch.registerC else launch.registerD
    val request = if (chat) launch.registerE else launch.registerF
    addInstructions(at, """
        invoke-static {v$intent}, ${if (chat) CHAT_CAMERA_CALL else CAMERA_INTENT_CALL}
        move-result-object v$intent
        invoke-static {v$intent, v$request}, $CAMERA_REQUEST_CALL
        move-result v$request
    """.trimIndent())
}

/**
 * Declares the capture screen and its photo provider once the switch's hook is in. The provider's authority follows the
 * manifest's package name at that point, and the clone patch moves it like Messenger's own when it runs later.
 */
internal fun Document.addSystemCamera() {
    val application = getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("Messenger controls: expected one application")
    val packageName = documentElement.getAttribute("package").ifEmpty { throw PatchException("Messenger controls: the manifest has no package") }
    fun child(tag: String, vararg attributes: Pair<String, String>) = createElement(tag).also { node ->
        attributes.forEach { (name, value) -> node.setAttribute("android:$name", value) }
        application.appendChild(node)
    }
    child("activity", "name" to CAMERA_ACTIVITY, "exported" to "false", "excludeFromRecents" to "true",
        "theme" to "@android:style/Theme.Translucent.NoTitleBar",
        "configChanges" to "orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden")
    child("provider", "name" to CAMERA_PROVIDER, "authorities" to packageName + CAMERA_AUTHORITY_SUFFIX,
        "exported" to "false", "grantUriPermissions" to "true")
}
