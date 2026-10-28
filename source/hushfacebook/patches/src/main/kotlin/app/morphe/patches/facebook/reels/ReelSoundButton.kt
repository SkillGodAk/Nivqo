/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.search.branchTarget
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * The speaker button at the bottom of a reel's right side (#190). Read from 583, 2026-10-19, arm64
 * and armv7; the obfuscated names here are for reviewers, the code never writes one down.
 *
 * Two places can build it, and one server flag (0x1010a290004583a on 583) picks which: on, the
 * video controls build it; off, the footer does, and each checks the flag the opposite way. The S22's
 * account had it off on 2026-10-19, so the footer's was the speaker on screen. The patch hooks both.
 *
 * The video controls:
 * - The reel's video control component (arm64 LX/7w1;, armv7 LX/7x8;, the one loading
 *   "FbShortsVideoControlComponent") builds the speaker in A1C: the unified sound toggle (LX/5SL;
 *   on both, whose render loads "video_unified_sound_toggle_copyright_force_mute"), wrapped with the
 *   transition key "mute_button_transition_key" and added to the column.
 * - The whole block sits behind one static check answering Z (A0Z: the flag, or the viewer config
 *   and player both present), read with move-result and branched on with if-eqz straight past the
 *   key. Facebook takes that branch itself when the check says no, so the reel builds without the
 *   button and nothing else in the column changes.
 * - The other method loading the key (A1E) has no such gate, and no other if-eqz between the check
 *   and the key follows a static call's answer.
 * - Every component in that column gets its own "..._transition_key" (play, pause, the info chip),
 *   and the gate skips the speaker's alone. A check that skipped another key too would take more
 *   than the speaker with it, so the matcher refuses one.
 * - The hook goes between the move-result and the if-eqz: the extension is handed the check's
 *   answer and answers back in the same register, so false takes Facebook's own branch.
 *
 * The footer:
 * - The methods loading exactly "FbShortsViewerFooterComponent" (arm64 LX/AOL;'s constructor, A1C,
 *   A1E and A1F, plus one invoke elsewhere) new up the pieces under and beside the reel. One of them (arm64 LX/APW;) builds the column's last rows
 *   in render, and there one static builder (arm64 LX/BRD;->A00) news up the same sound toggle and
 *   answers it wrapped, or null when the flag is on or a player part is missing.
 * - render moves that answer with move-result-object and null-checks it with if-eqz before adding
 *   it, so a null leaves the column without the speaker and the rows after it as they were. The
 *   video settings row calls the same builder, and isn't a piece of the footer, so it's left alone.
 * - The hook goes between the move-result-object and the if-eqz: the extension is handed what was
 *   built and answers it or null in the same register, cast back to the builder's return type for
 *   the verifier.
 */

internal const val REEL_SOUND_BUTTON = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->showsSoundButton(Z)Z"
internal const val REEL_FOOTER_SOUND_BUTTON = "$EXTENSION_PACKAGE/reels/ReelDeclutter;->soundButton(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val SOUND_BUTTON_KEY = "mute_button_transition_key"
internal const val FOOTER_COMPONENT = "FbShortsViewerFooterComponent"
internal const val SOUND_TOGGLE_STRING = "video_unified_sound_toggle_copyright_force_mute"
private const val TRANSITION_KEY = "_transition_key"

private const val PATCH = "Clean up Reels"

/** A static check answering Z ([call]), its move-result, and the if-eqz at [branch] on [answer] that jumps to [target]. */
internal data class SoundButtonGate(val call: Int, val branch: Int, val answer: Int, val target: Int)

/**
 * The places in [method] where a static call's Z answer goes straight into an if-eqz that jumps past
 * a load of [key], and past no other transition key: false skips only what's built with that key.
 */
internal fun soundButtonGates(method: Method, key: String = SOUND_BUTTON_KEY): List<SoundButtonGate> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val strings = code.map { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
    if (key !in strings) return emptyList()
    return code.indices.mapNotNull { at ->
        if (code[at].opcode != Opcode.IF_EQZ || at < 2) return@mapNotNull null
        val answer = (code[at] as OneRegisterInstruction).registerA
        val result = code[at - 1]
        if (result.opcode != Opcode.MOVE_RESULT || (result as OneRegisterInstruction).registerA != answer) return@mapNotNull null
        val call = code[at - 2]
        if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        if (((call as ReferenceInstruction).reference as MethodReference).returnType != "Z") return@mapNotNull null
        val target = branchTarget(code, at) ?: return@mapNotNull null
        val skipped = strings.subList(at + 1, maxOf(at + 1, target)).filterNotNull()
        if (key !in skipped || skipped.any { it != key && it.endsWith(TRANSITION_KEY) }) return@mapNotNull null
        SoundButtonGate(at - 2, at, answer, target)
    }
}

/**
 * Hands the reel speaker button's check to the extension before Facebook branches on it. Refuses
 * unless exactly one gate jumps past [SOUND_BUTTON_KEY] outside the extension.
 */
internal fun BytecodePatchContext.hideReelSoundButton() {
    val found = classDefByStrings(SOUND_BUTTON_KEY, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { owner -> methodsHolding(owner, SOUND_BUTTON_KEY).flatMap { method -> soundButtonGates(method).map { owner.type to method } } }
    val (owner, holder) = found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one static check whose false skips \"$SOUND_BUTTON_KEY\", found ${found.size}",
    )
    val method = mutableClassDefBy(owner).methods.single {
        it.name == holder.name && it.parameterTypes.map(CharSequence::toString) == holder.parameterTypes.map(CharSequence::toString)
    }
    val gate = soundButtonGates(method).singleOrNull()
        ?: throw PatchException("$PATCH: $owner->${holder.name} lost its speaker button check before it was hooked")
    method.addInstructions(
        gate.branch,
        """
            invoke-static/range { v${gate.answer} .. v${gate.answer} }, $REEL_SOUND_BUTTON
            move-result v${gate.answer}
        """,
    )
}

/** A static call at [call] whose answer of [type] goes by move-result-object into [register], null-checked by the if-eqz at [call] + 2. */
internal data class SpeakerBuild(val call: Int, val register: Int, val type: String)

/** The types [method] news up. */
internal fun newInstanceTypes(method: Method): Set<String> =
    method.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapTo(mutableSetOf()) { ((it as ReferenceInstruction).reference as TypeReference).type }

/** Whether [method] is the one [call] names. */
internal fun isCalledBy(method: Method, call: MethodReference): Boolean =
    method.name == call.name && method.returnType == call.returnType &&
        method.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString)

/**
 * The static calls in [method] that [buildsSpeaker] picks, whose object answer goes by
 * move-result-object straight into an if-eqz on the same register: built, or nothing to add.
 */
internal fun speakerBuilds(method: Method, buildsSpeaker: (MethodReference) -> Boolean): List<SpeakerBuild> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return (0 until code.size - 2).mapNotNull { at ->
        val call = code[at]
        if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        val reference = (call as ReferenceInstruction).reference as MethodReference
        if (reference.returnType.first() != 'L' && reference.returnType.first() != '[') return@mapNotNull null
        val result = code[at + 1]
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        val register = (result as OneRegisterInstruction).registerA
        val check = code[at + 2]
        if (check.opcode != Opcode.IF_EQZ || (check as OneRegisterInstruction).registerA != register) return@mapNotNull null
        if (!buildsSpeaker(reference)) return@mapNotNull null
        SpeakerBuild(at, register, reference.returnType)
    }
}

/**
 * Hands the speaker button the reel footer builds to the extension before the footer's null check.
 * Refuses unless exactly one piece the footer news up calls a static builder of the sound toggle
 * that way.
 */
internal fun BytecodePatchContext.hideReelFooterSoundButton() {
    fun outsideExtension(type: String) = !type.startsWith(EXTENSION_PACKAGE)
    val toggles = classDefByStrings(SOUND_TOGGLE_STRING, StringComparisonType.EQUALS)
        .map { it.type }.filter(::outsideExtension).toSet()
    if (toggles.isEmpty()) throw PatchException("$PATCH: no class loads \"$SOUND_TOGGLE_STRING\"")
    val pieces = classDefByStrings(FOOTER_COMPONENT, StringComparisonType.EQUALS)
        .filter { outsideExtension(it.type) }.distinctBy { it.type }
        .flatMap { methodsHolding(it, FOOTER_COMPONENT) }.flatMap(::newInstanceTypes).toSet()
    val buildsSpeaker = { call: MethodReference ->
        classDefByOrNull(call.definingClass)?.methods?.any { isCalledBy(it, call) && newInstanceTypes(it).any(toggles::contains) } == true
    }
    val found = pieces.filter(::outsideExtension).mapNotNull { classDefByOrNull(it) }
        .flatMap { piece -> piece.methods.filter { speakerBuilds(it, buildsSpeaker).isNotEmpty() }.map { piece.type to it } }
    val (owner, holder) = found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one piece of \"$FOOTER_COMPONENT\" to build the speaker button, found ${found.size}",
    )
    val method = mutableClassDefBy(owner).methods.single {
        it.name == holder.name && it.parameterTypes.map(CharSequence::toString) == holder.parameterTypes.map(CharSequence::toString)
    }
    val build = speakerBuilds(method, buildsSpeaker).singleOrNull()
        ?: throw PatchException("$PATCH: $owner->${holder.name} doesn't build the speaker button exactly once")
    // check-cast takes an 8-bit register.
    if (build.register > 0xFF) throw PatchException("$PATCH: $owner->${holder.name} keeps the speaker in v${build.register}")
    method.addInstructions(
        build.call + 2,
        """
            invoke-static/range { v${build.register} .. v${build.register} }, $REEL_FOOTER_SOUND_BUTTON
            move-result-object v${build.register}
            check-cast v${build.register}, ${build.type}
        """,
    )
}
