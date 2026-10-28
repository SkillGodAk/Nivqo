/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isModelEnumAccessor
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.storyModelAccessors
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Posts from groups you aren't in (#195). Read from 583, 2026-10-20; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * A friend's post in a public group reaches Home as an ordinary story with a Join button next to the
 * group's name. The story's `to` (GraphQLStory.A0i, key 0xe7b as Profile 0xcce99c59) is the group, and
 * answers getTypeName() "Group", which is how Facebook's own LX/CE5;->A00 tells a group post apart.
 * The profile model (arm64 LX/40B, a direct BaseModelWithTree) reads viewer_join_state (0x5b7a8189)
 * through getCachedEnum as LX/3u7, whose constants are UNSET_OR_UNRECOGNIZED_ENUM_VALUE,
 * CANNOT_JOIN_OR_REQUEST, CAN_JOIN, CAN_REQUEST, MEMBER and REQUESTED. Facebook's join flow
 * (LX/2lC;->A01) writes the new state into that same `to`, and its Join button rule (LX/CMJ;->A01)
 * shows for CAN_JOIN and CAN_REQUEST. Home asks for the state only while MobileConfig
 * 0x10109e50005571b says so (LX/1aT;->A01, "should_include_viewer_join_state"), so an account without
 * it reads the unset default and keeps every post. The S22's account read unset for most group posts
 * (2026-10-20), so the patch hands that MobileConfig answer to the extension too, which says yes
 * while the switch is on.
 */

internal const val STORY_TARGET_FIELD = "to"
internal const val STORY_TARGET_TYPE = "Profile"
internal const val VIEWER_JOIN_STATE_FIELD = "viewer_join_state"

/**
 * Names the join state enum carries. The two Join button states are the extension's
 * `GroupPosts.NOT_JOINED`; MEMBER is the state that keeps your groups' posts.
 */
internal val JOIN_STATE_NAMES = listOf("CAN_JOIN", "CAN_REQUEST", "MEMBER")

/** The extension class that reads the join state, and the two accessors this patch fills in. */
internal const val GROUP_POSTS = "$EXTENSION_PACKAGE/feed/GroupPosts;"
internal const val STORY_TARGET_STUB = "storyTarget"
internal const val JOIN_STATE_STUB = "joinState"

/** Every accessor [story] declares of its `to` as a Profile. The patch wants exactly one. */
internal fun storyTargetAccessors(story: ClassDef): List<Method> =
    storyModelAccessors(story, STORY_TARGET_FIELD, STORY_TARGET_TYPE)

/** Every accessor [profile] declares of its join state. The patch wants exactly one. */
internal fun joinStateAccessors(profile: ClassDef): List<Method> =
    profile.methods.filter { isModelEnumAccessor(it, VIEWER_JOIN_STATE_FIELD) }

/** Whether [classDef] is the join state enum: an Enum whose static initializer names [JOIN_STATE_NAMES]. */
internal fun isJoinStateEnum(classDef: ClassDef): Boolean {
    if (classDef.superclass != "Ljava/lang/Enum;") return false
    val init = classDef.methods.firstOrNull { it.name == "<clinit>" } ?: return false
    return JOIN_STATE_NAMES.all { holdsString(init, it) }
}

/** The story's accessor of its `to`, and that profile's accessor of its join state. */
internal class GroupJoinAccessors(val target: Method, val joinState: Method)

/**
 * The two accessors, held to the evidence the rule rests on: one `to` accessor on GraphQLStory, its
 * profile a tree model the extension can ask for a type name, one join state accessor on that
 * profile, and an enum answering it that names the states. Throws a [PatchException] otherwise.
 */
internal fun BytecodePatchContext.groupJoinAccessors(): GroupJoinAccessors {
    val targets = storyTargetAccessors(classDefBy(GRAPHQL_STORY))
    val target = targets.singleOrNull() ?: throw PatchException(
        "GraphQLStory has ${targets.size} accessors of $STORY_TARGET_FIELD as $STORY_TARGET_TYPE, expected one: " +
            targets.joinToString { it.name },
    )
    val profile = classDefByOrNull(target.returnType)
        ?: throw PatchException("${target.returnType}, GraphQLStory's $STORY_TARGET_FIELD, isn't in this Facebook build")
    if (profile.superclass != BASE_MODEL_WITH_TREE) {
        throw PatchException("${profile.type} isn't a BaseModelWithTree, so its type name can't be read")
    }
    val states = joinStateAccessors(profile)
    val state = states.singleOrNull() ?: throw PatchException(
        "${profile.type} has ${states.size} accessors of $VIEWER_JOIN_STATE_FIELD, expected one: " +
            states.joinToString { it.name },
    )
    val enum = classDefByOrNull(state.returnType)
    if (enum == null || !isJoinStateEnum(enum)) {
        throw PatchException("${state.returnType} isn't an enum naming ${JOIN_STATE_NAMES.joinToString()}")
    }
    return GroupJoinAccessors(target, state)
}

/** Fills in the extension's two GroupPosts stubs with the accessors [groupJoinAccessors] finds. */
internal fun BytecodePatchContext.fillGroupJoinStubs() {
    val found = groupJoinAccessors()
    fillStoryModelStub(GROUP_POSTS, STORY_TARGET_STUB, found.target)
    fillStoryModelStub(GROUP_POSTS, JOIN_STATE_STUB, found.joinState)
}

/** The query parameter asking Home for each group's join state. */
internal const val JOIN_STATE_QUERY_FLAG = "should_include_viewer_join_state"

internal const val ASK_FOR_JOIN_STATE = "$GROUP_POSTS->askForJoinState(Z)Z"

/**
 * The index of the `move-result` holding the boolean [method] hands over as [JOIN_STATE_QUERY_FLAG]:
 * right before the name, right after a call answering a boolean. Null unless the method holds the
 * name once, in that shape.
 */
internal fun joinStateFlagAnswer(method: Method): Int? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val name = code.indices.singleOrNull { index ->
        ((code[index] as? ReferenceInstruction)?.reference as? StringReference)?.string == JOIN_STATE_QUERY_FLAG
    } ?: return null
    val answer = name - 1
    if (answer < 1 || code[answer].opcode != Opcode.MOVE_RESULT) return null
    val asked = (code[answer - 1] as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    return if (asked.returnType == "Z") answer else null
}

/** The methods outside the extension holding [JOIN_STATE_QUERY_FLAG]. */
internal fun BytecodePatchContext.joinStateFlagHolders(): List<Method> =
    classDefByStrings(JOIN_STATE_QUERY_FLAG, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        .distinctBy { it.type }
        .flatMap { methodsHolding(it, JOIN_STATE_QUERY_FLAG) }

/**
 * Hands Home's answer for [JOIN_STATE_QUERY_FLAG] to the extension as it comes back, in its own
 * register. Refuses unless one method sets the flag, once, from a boolean answer, and nothing jumps
 * to the name past the hook.
 */
internal fun BytecodePatchContext.askForJoinState() {
    val holders = joinStateFlagHolders()
    val method = holders.singleOrNull()
        ?: throw PatchException("expected one method setting \"$JOIN_STATE_QUERY_FLAG\", found ${holders.size}")
    val answer = joinStateFlagAnswer(method)
        ?: throw PatchException("${method.definingClass}->${method.name} doesn't set \"$JOIN_STATE_QUERY_FLAG\" from a boolean answer")
    val register = (method.implementation!!.instructions.elementAt(answer) as OneRegisterInstruction).registerA
    val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
    val name = mutable.implementation!!.instructions[answer + 1] as BuilderInstruction
    if (name.location.labels.isNotEmpty()) {
        throw PatchException("${method.definingClass}->${method.name} jumps to \"$JOIN_STATE_QUERY_FLAG\" past its answer")
    }
    mutable.addInstructions(
        answer + 1,
        """
            invoke-static/range { v$register .. v$register }, $ASK_FOR_JOIN_STATE
            move-result v$register
        """,
    )
}
