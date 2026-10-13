/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.notifications

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.FACEBOOK_APPLICATION
import app.morphe.patches.facebook.misc.extension.PatchLogCapture
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.*
import org.junit.Test

class PushDiagnosticsShapesTest {
    private val route = "Lfixture/PushRoute;"
    private val registrar = "Lfixture/Registrar;"
    private val validator = "Lfixture/Validator;"
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"
    private val operation = "Lcom/facebook/fbservice/service/OperationResult;"
    private val objectType = "Ljava/lang/Object;"

    private fun method(type: String, name: String, result: String = "V", params: List<String> = emptyList(),
        static: Boolean = false, locals: Int = 4, code: String): Method = MutableMethod(ImmutableMethod(type, name,
        params.map { ImmutableMethodParameter(it, null, null) }, result, AccessFlags.PUBLIC.value or
            (if (static) AccessFlags.STATIC.value else 0), null, null,
        ImmutableMethodImplementation(locals + params.sumOf { if (it == "J" || it == "D") 2 else 1 } + (if (static) 0 else 1), emptyList(), null, null)))
        .apply { addInstructionsWithLabels(0, code) }
    private fun owner(type: String, methods: List<Method> = emptyList(), parent: String = objectType): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, parent, null, null, null, emptyList(), methods)
    private fun extension() = owner(PUSH_DIAGNOSTICS, listOf("hasRegistrationObserver", "hasMqttObserver", "hasGatewayObserver", "hasFcmReceiptObserver").map {
        method(PUSH_DIAGNOSTICS, it, "Z", static = true, code = "const/4 v0, 0x0\nreturn v0")
    })
    private fun server(typed: Boolean = true, clobberedRoute: Boolean = false, widePrefix: Boolean = false): List<ClassDef> {
        val parameters = listOf(if (widePrefix) "J" else session, operation, objectType, route, objectType, "Ljava/lang/String;")
        val result = method(validator, "validate", "Z", parameters, static = true, code = """
            ${if (clobberedRoute) "const/4 p${if (widePrefix) 4 else 3}, 0x0" else ""}
            invoke-virtual { p${if (widePrefix) 2 else 1} }, $operation->getResult()Ljava/lang/Object;
            move-result-object v0
            ${if (typed) "check-cast v0, $REGISTER_RESULT" else ""}
            if-eqz v0, :rejected
            iget-boolean v1, v0, $REGISTER_RESULT->accepted:Z
            goto :answer
            :rejected
            const/4 v1, 0x0
            :answer
            return v1
        """.trimIndent())
        val callbackCode = if (widePrefix) """
            const-wide/16 v0, 0x0
            move-object v2, p3
            const/4 v3, 0x0
            move-object v4, p2
            const/4 v5, 0x0
            const-string v6, "FacebookPushServerRegistrar"
            invoke-static/range { v0 .. v6 }, $validator->validate(${parameters.joinToString("")})Z
            return-void
        """.trimIndent() else """
            move-object v0, p1
            move-object v1, p3
            const/4 v2, 0x0
            move-object v3, p2
            const/4 v4, 0x0
            const-string v5, "FacebookPushServerRegistrar"
            invoke-static/range { v0 .. v5 }, $validator->validate(${parameters.joinToString("")})Z
            return-void
        """.trimIndent()
        val callback = method(registrar, "onRegisterPushTokenSucceeded", params = listOf(session, route, operation, objectType),
            locals = if (widePrefix) 7 else 6, code = callbackCode)
        val failure = method(registrar, "onRegisterPushTokenFailed", params = listOf(route, session, "Ljava/lang/Throwable;", objectType),
            code = "const-string v0, \"FacebookPushServerRegistrar\"\nreturn-void")
        val enum = owner(route, listOf(method(route, "<clinit>", static = true, code =
            listOf("FCM", "GCM", "GCM_V3", "FBNS", "FBNS_LITE").joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void")), "Ljava/lang/Enum;")
        return listOf(owner(registrar, listOf(callback, failure)), owner(validator, listOf(result)), enum, extension())
    }

    @Test fun `a branch to the Boolean return visits the observer and preserves false`() {
        val context = PatchContexts.of(server())
        val anchors = context.findRegistrationAnchors()
        context.applyRegistrationAnchors(anchors)
        val code = context.mutableClassDefBy(validator).methods.single().pushCode()
        val branch = code.single { it.opcode == Opcode.GOTO } as BuilderOffsetInstruction
        val target = branch.target.location.index
        assertEquals("the old return label now starts the read-only observation", Opcode.MOVE_OBJECT_FROM16, code[target].opcode)
        assertEquals("registrationResult", code[target + 1].pushCall!!.name)
        assertEquals(Opcode.RETURN, code[target + 2].opcode)
        assertEquals("the false-result branch stays present", 1, code.count { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0 })
    }

    @Test fun `a callback without typed validation cannot claim registration success`() {
        val context = PatchContexts.of(server(typed = false))
        assertThrows(PatchException::class.java) { context.findRegistrationAnchors() }
        assertFalse(context.mutableClassDefBy(validator).methods.single().pushCode().any { it.pushCall?.definingClass == PUSH_DIAGNOSTICS })
    }

    @Test fun `a wide prefix uses the route parameter register rather than p3`() {
        val context = PatchContexts.of(server(widePrefix = true))
        val anchors = context.findRegistrationAnchors()
        context.applyRegistrationAnchors(anchors)
        val code = context.mutableClassDefBy(validator).methods.single().pushCode()
        val call = code.indexOfFirst { it.pushCall?.definingClass == PUSH_DIAGNOSTICS }
        val moved = code[call - 1] as TwoRegisterInstruction
        assertEquals(anchors.result.parameterRegisterNumber(3), moved.registerB)
        assertEquals(8, moved.registerB)
    }

    @Test fun `a reused route register is refused before any observer is installed`() {
        val context = PatchContexts.of(server(clobberedRoute = true))
        assertThrows(PatchException::class.java) { context.findRegistrationAnchors() }
        assertFalse(context.mutableClassDefBy(registrar).methods.flatMap { it.pushCode() }.any { it.pushCall?.definingClass == PUSH_DIAGNOSTICS })
    }

    @Test fun `missing transport groups leave registration available and keep their own status unknown`() {
        val context = PatchContexts.of(server())
        assertEquals(3, PatchLogCapture.warnings { pushDiagnosticsPatch.execute(context) }.size)
        val status = context.mutableClassDefBy(PUSH_DIAGNOSTICS).methods.associate { method ->
            method.name to (method.pushCode().first() as NarrowLiteralInstruction).narrowLiteral
        }
        assertEquals(1, status.getValue("hasRegistrationObserver"))
        assertEquals(0, status.getValue("hasMqttObserver"))
        assertEquals(0, status.getValue("hasGatewayObserver"))
        assertEquals(0, status.getValue("hasFcmReceiptObserver"))
    }

    @Test fun `opaque builds keep the settings refusal before looking for observers`() {
        val context = PatchContexts.of(listOf(owner(FACEBOOK_APPLICATION)))
        assertEquals(emptyList<String>(), PatchLogCapture.warnings { pushDiagnosticsPatch.execute(context) })
    }
}
