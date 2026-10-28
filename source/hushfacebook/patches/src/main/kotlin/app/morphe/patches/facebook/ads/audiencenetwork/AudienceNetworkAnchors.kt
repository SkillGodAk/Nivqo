/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.audiencenetwork

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

/*
 * What turned the Audience Network components back on after the manifest switched them off (#168).
 *
 * Facebook's AppComponentManager runs after an install or update and turns on every component
 * the manifest declares off, unless the component carries a meta-data "default-state" of false.
 * With that meta-data it puts the component back to the manifest's state, or leaves it, so the
 * patch adds it to each one. A component already turned on by an earlier install can be left on
 * that way, so the extension also puts any that's on back to the manifest's state at each start.
 *
 * AudienceNetworkRemoteAPIsSetup, a startup task kept under its own name, separately turns on the
 * three ipc components, in a state the server picks. Its setComponentEnabledSetting calls go
 * through the extension, which keeps an Audience Network component off.
 */

/** The meta-data AppComponentManager reads to leave a component the manifest declares off. */
internal const val DEFAULT_STATE = "default-state"

/** Facebook's startup task that turns the ipc components on. */
internal const val REMOTE_APIS_SETUP = "Lcom/facebook/audiencenetwork/AudienceNetworkRemoteAPIsSetup;"

private const val SET_COMPONENT_STATE = "Landroid/content/pm/PackageManager;->setComponentEnabledSetting(Landroid/content/ComponentName;II)V"
internal const val COMPONENT_STATE =
    "$EXTENSION_PACKAGE/ads/AudienceNetwork;->componentState(Landroid/content/pm/PackageManager;Landroid/content/ComponentName;II)V"

private fun MethodReference.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** Adds `<meta-data android:name="default-state" android:value="false"/>` to [component] unless it has one. */
internal fun keepOffByDefault(component: Element) {
    val children = component.getElementsByTagName("meta-data")
    for (index in 0 until children.length) {
        val child = children.item(index) as? Element ?: continue
        if (child.getAttribute("android:name") == DEFAULT_STATE) {
            child.setAttribute("android:value", "false")
            return
        }
    }
    val meta = component.ownerDocument.createElement("meta-data")
    meta.setAttribute("android:name", DEFAULT_STATE)
    meta.setAttribute("android:value", "false")
    component.appendChild(meta)
}

/** The indexes of [method]'s PackageManager.setComponentEnabledSetting calls. */
internal fun componentStateCalls(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.filter { index ->
        code[index].opcode == Opcode.INVOKE_VIRTUAL &&
            ((code[index] as ReferenceInstruction).reference as? MethodReference)?.signature() == SET_COMPONENT_STATE
    }
}

/** Sends the call at [index] through [COMPONENT_STATE], with the same four registers. */
internal fun MutableMethod.passComponentState(patch: String, index: Int) {
    val call = getInstruction(index) as? FiveRegisterInstruction
    if (call == null || getInstruction(index).opcode != Opcode.INVOKE_VIRTUAL || call.registerCount != 4) {
        throw PatchException("$patch: instruction $index of $definingClass->$name isn't a component state call")
    }
    replaceInstruction(
        index,
        "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF} }, $COMPONENT_STATE",
    )
}
