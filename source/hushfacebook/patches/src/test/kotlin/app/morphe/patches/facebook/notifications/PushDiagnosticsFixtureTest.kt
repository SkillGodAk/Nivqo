/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.notifications

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.PatchLogCapture
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.*
import org.junit.Test

/** Both supported ABIs prove kept, typed outcomes and read-only injection without Redex class names. */
class PushDiagnosticsFixtureTest {
    @Test fun `each declared ABI gets server transport and receive observers without payload reads`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            val bundles = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }
            assertTrue("$version: arm64 fixture missing", bundles.any { it.name.contains("-arm64-v8a.") })
            assertTrue("$version: armv7 fixture missing", bundles.any { it.name.contains("-armeabi-v7a.") })
            for (bundle in bundles) {
                val label = bundle.name
                val registrars = FixtureDex.classesHolding(bundle, "FacebookPushServerRegistrar")
                val callback = registrars.flatMap { it.methods }.single { it.name == "onRegisterPushTokenSucceeded" }
                val validation = callback.pushCode().mapNotNull { it.pushCall }.single {
                    it.returnType == "Z" && it.parameterTypes.size == 6 && it.parameterTypes[1].toString() ==
                        "Lcom/facebook/fbservice/service/OperationResult;"
                }
                val kept = FixtureDex.classes(bundle, setOf(MQTT_DELEGATE, GATEWAY_HANDLER, FCM_LISTENER,
                    callback.parameterTypes[1].toString(), validation.definingClass))
                val mqtt = kept.getValue(MQTT_DELEGATE)
                val enumTypes = mqtt.methods.flatMap { it.pushCode() }.filter { it.opcode == Opcode.IPUT_OBJECT }
                    .mapNotNull { ((it as ReferenceInstruction).reference as? FieldReference)?.takeIf { field ->
                        field.definingClass == MQTT_DELEGATE
                    }?.type }.toSet()
                val enums = FixtureDex.classes(bundle, enumTypes)
                val context = PatchContexts.of((registrars + kept.values + enums.values + ExtensionDex.classDef(PUSH_DIAGNOSTICS))
                    .distinctBy { it.type })
                val registration = context.findRegistrationAnchors()
                val transport = context.findMqttAnchor()
                val gateway = context.findGatewayAnchors()
                val receipt = context.findFcmReceipt()
                assertTrue("$label: server result cast", registration.result.pushCode().any {
                    it.opcode == Opcode.CHECK_CAST && (it as ReferenceInstruction).reference.toString() == REGISTER_RESULT
                })
                assertTrue("$label: server Boolean is read", registration.result.pushCode().any {
                    it.opcode == Opcode.IGET_BOOLEAN && ((it as ReferenceInstruction).reference as FieldReference).definingClass == REGISTER_RESULT
                })
                assertEquals("$label: missing observer", emptyList<String>(), PatchLogCapture.warnings { pushDiagnosticsPatch.execute(context) })
                fun patched(original: Method) = context.mutableClassDefBy(original.definingClass).methods.single {
                    it.name == original.name && it.returnType == original.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == original.parameterTypes.map(CharSequence::toString)
                }
                fun unchanged(original: Method, inserted: Set<Int>) {
                    val after = patched(original)
                    assertEquals("$label: no new registers in ${original.name}", original.implementation!!.registerCount, after.implementation!!.registerCount)
                    val body = after.pushCode().filterIndexed { index, _ -> index !in inserted }
                    assertEquals("$label: original opcodes in ${original.name}", original.pushCode().map { it.opcode }, body.map { it.opcode })
                    assertEquals("$label: original references in ${original.name}", original.pushCode().mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() },
                        body.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() })
                }
                val resultCode = patched(registration.result).pushCode()
                val injected = resultCode.indices.filter { resultCode[it].pushCall?.definingClass == PUSH_DIAGNOSTICS }.toSet()
                assertEquals("$label: every Boolean return observed", registration.returns.size, injected.size)
                for (index in injected) {
                    val call = resultCode[index] as FiveRegisterInstruction
                    val move = resultCode[index - 1] as TwoRegisterInstruction
                    assertEquals("registrationResult", resultCode[index].pushCall!!.name)
                    assertEquals(listOf("Ljava/lang/Enum;", "Z"), resultCode[index].pushCall!!.parameterTypes.map(CharSequence::toString))
                    assertEquals("$label: reads the route", registration.result.parameterRegisterNumber(3), move.registerB)
                    assertEquals(move.registerA, call.registerC)
                    assertEquals("$label: reads the actual returned Boolean", (resultCode[index + 1] as OneRegisterInstruction).registerA, call.registerD)
                    assertTrue(call.registerC < registration.result.localRegisterCount())
                }
                unchanged(registration.result, injected + injected.map { it - 1 })
                val failed = patched(registration.failure).pushCode().first() as RegisterRangeInstruction
                assertEquals(registration.failure.parameterRegisterNumber(0), failed.startRegister)
                assertEquals("$label: failure reads only route, never Throwable", 1, failed.registerCount)
                unchanged(registration.failure, setOf(0))
                assertEquals("$label: callback alone is not registration acceptance", registration.callback.pushCode().map { it.opcode },
                    patched(registration.callback).pushCode().map { it.opcode })

                val mqttCall = patched(transport.method).pushCode()[transport.write + 1] as FiveRegisterInstruction
                assertEquals(transport.receiver, mqttCall.registerC)
                assertEquals(transport.state, mqttCall.registerD)
                unchanged(transport.method, setOf(transport.write + 1))
                for ((method, at, name) in listOf(Triple(gateway.ready, gateway.readyAfter, "gatewayReady"),
                    Triple(gateway.closed, gateway.closedAfter, "gatewayClosed"))) {
                    val code = patched(method).pushCode()
                    val call = code[at] as RegisterRangeInstruction
                    assertEquals(name, code[at].pushCall!!.name)
                    assertEquals("$label: no stream, request UUID or error passed", listOf("Ljava/lang/Object;"), code[at].pushCall!!.parameterTypes.map(CharSequence::toString))
                    assertEquals(method.localRegisterCount(), call.startRegister)
                    assertEquals(1, call.registerCount)
                    unchanged(method, setOf(at))
                }
                assertEquals("$label: readiness follows actual stream storage", Opcode.IPUT_OBJECT,
                    patched(gateway.ready).pushCode()[gateway.readyAfter - 1].opcode)
                assertEquals("$label: closure follows the committed getAndSet result", Opcode.MOVE_RESULT_OBJECT,
                    patched(gateway.closed).pushCode()[gateway.closedAfter - 1].opcode)
                val receive = patched(receipt).pushCode().first()
                assertEquals("fcmCallback", receive.pushCall!!.name)
                assertEquals("$label: RemoteMessage is never passed", 0, (receive as FiveRegisterInstruction).registerCount)
                unchanged(receipt, setOf(0))
                for (name in listOf("hasRegistrationObserver", "hasMqttObserver", "hasGatewayObserver", "hasFcmReceiptObserver")) {
                    val code = context.mutableClassDefBy(PUSH_DIAGNOSTICS).methods.single { it.name == name }.pushCode()
                    assertEquals("$label: $name enabled", 1, (code.first() as NarrowLiteralInstruction).narrowLiteral)
                }
            }
        }
        assertNull("the diagnostic must not add a visible patch", pushDiagnosticsPatch.name)
    }
}
