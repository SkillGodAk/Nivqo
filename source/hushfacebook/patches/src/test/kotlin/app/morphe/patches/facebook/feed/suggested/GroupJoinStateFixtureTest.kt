/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStoryModelAccessor
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide posts from groups you're not in (#195) on every Facebook build the bundle declares: one
 * GraphQLStory accessor of `to` as a Profile, its model a BaseModelWithTree with one accessor of
 * viewer_join_state, and the enum answering it naming every state the extension reads. The patch
 * fills the extension's two stubs with calls to those accessors, and hands Home's MobileConfig answer
 * for "should_include_viewer_join_state" to the extension. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class GroupJoinStateFixtureTest {
    /** Every name of Facebook 583's join state enum, the two that hide a post and the four that keep it. */
    private val allStates = listOf(
        "CAN_JOIN", "CAN_REQUEST", "MEMBER", "REQUESTED", "CANNOT_JOIN_OR_REQUEST", "UNSET_OR_UNRECOGNIZED_ENUM_VALUE",
    )

    private fun ClassDef.stub(name: String): Method = methods.single { it.name == name }

    /** The first two instructions of a filled stub: the cast to the accessor's class, and the call. */
    private fun Method.castAndCall(): Pair<String, String> {
        val code = implementation!!.instructions.toList()
        assertEquals("$name: cast first", Opcode.CHECK_CAST, code[0].opcode)
        assertEquals("$name: call second", Opcode.INVOKE_VIRTUAL, code[1].opcode)
        val cast = ((code[0] as ReferenceInstruction).reference as TypeReference).type
        val call = (code[1] as ReferenceInstruction).reference as MethodReference
        return cast to "${call.definingClass}->${call.name}()${call.returnType}"
    }

    @Test
    fun `each declared build has one group target and one join state, and the stubs call them`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val story = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY))[GRAPHQL_STORY]
                    ?: throw AssertionError("$name has no $GRAPHQL_STORY")
                val targets = storyTargetAccessors(story)
                assertEquals("$name: ${targets.map { it.name }}", 1, targets.size)
                val target = targets.single()
                // The control: the story's other accessors of the same model class load other keys,
                // so only `to` matches.
                val siblings = story.methods.filter {
                    it.name != target.name && it.parameterTypes.isEmpty() && it.returnType == target.returnType
                }
                for (sibling in siblings) {
                    assertFalse("$name: ${sibling.name}() matches too",
                        isStoryModelAccessor(sibling, STORY_TARGET_FIELD, STORY_TARGET_TYPE))
                }

                val profile = FixtureDex.classes(bundle, setOf(target.returnType))[target.returnType]
                    ?: throw AssertionError("$name has no ${target.returnType}")
                assertEquals("$name: ${profile.type} answers getTypeName as a tree model", BASE_MODEL_WITH_TREE,
                    profile.superclass)
                val states = joinStateAccessors(profile)
                assertEquals("$name: ${states.map { it.name }}", 1, states.size)
                val state = states.single()
                val enum = FixtureDex.classes(bundle, setOf(state.returnType))[state.returnType]
                    ?: throw AssertionError("$name has no ${state.returnType}")
                assertTrue("$name: ${enum.type} isn't the join state enum", isJoinStateEnum(enum))
                val init = enum.methods.single { it.name == "<clinit>" }
                for (constant in allStates) {
                    assertTrue("$name: ${enum.type} doesn't name $constant", holdsString(init, constant))
                }

                val context = PatchContexts.of(listOf(story, profile, enum, ExtensionDex.classDef(GROUP_POSTS)))
                context.fillGroupJoinStubs()
                val filled = context.mutableClassDefBy(GROUP_POSTS)
                assertEquals("$name: storyTarget",
                    GRAPHQL_STORY to "$GRAPHQL_STORY->${target.name}()${target.returnType}",
                    filled.stub(STORY_TARGET_STUB).castAndCall())
                assertEquals("$name: joinState",
                    profile.type to "${profile.type}->${state.name}()${state.returnType}",
                    filled.stub(JOIN_STATE_STUB).castAndCall())
                checked[version] = "${target.name}()${target.returnType} -> ${state.name}()${state.returnType}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.called() = (this as? ReferenceInstruction)?.reference as? MethodReference

    @Test
    fun `each declared build asks Home for join states through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val classes = FixtureDex.classesHolding(bundle, JOIN_STATE_QUERY_FLAG)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val context = PatchContexts.of(classes)
                val holders = context.joinStateFlagHolders()
                assertEquals("$name: methods setting \"$JOIN_STATE_QUERY_FLAG\"", 1, holders.size)
                val method = holders.single()
                val answer = joinStateFlagAnswer(method)
                assertNotNull("$name: the flag isn't set from a boolean answer", answer)
                val code = method.code()
                // Positive control: the answer is MobileConfig's (asked of the interface, or of its
                // static helper since 584), and the name goes to a setter with it.
                val asked = code[answer!! - 1]
                assertTrue("$name: answer asked with ${asked.opcode}",
                    asked.opcode == Opcode.INVOKE_INTERFACE || asked.opcode == Opcode.INVOKE_STATIC)
                assertTrue("$name: ${asked.called()} isn't MobileConfig",
                    asked.called()!!.definingClass.endsWith("/MobileConfigUnsafeContext;"))
                val register = (code[answer] as OneRegisterInstruction).registerA
                val setter = code[answer + 2].called()
                assertEquals("$name: $setter doesn't take the name and the answer",
                    listOf("Ljava/lang/String;", "Z"), setter?.parameterTypes?.takeLast(2)?.map(CharSequence::toString))
                assertEquals("$name: the setter's last register is the answer", register,
                    (code[answer + 2] as FiveRegisterInstruction).registerE)

                context.askForJoinState()
                val patched = context.mutableClassDefBy(method.definingClass).methods
                    .single { patchedMethod ->
                        patchedMethod.name == method.name &&
                            patchedMethod.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                    }.code()
                assertEquals("$name: two instructions added", code.size + 2, patched.size)
                val ask = patched[answer + 1]
                assertEquals("$name: the extension asked", ASK_FOR_JOIN_STATE, ask.called()?.toString())
                assertEquals("$name: handed the boolean",
                    listOf(Opcode.INVOKE_STATIC_RANGE, register, 1),
                    listOf(ask.opcode, (ask as RegisterRangeInstruction).startRegister, ask.registerCount))
                assertEquals("$name: its answer back in the same register",
                    listOf(Opcode.MOVE_RESULT, register),
                    listOf(patched[answer + 2].opcode, (patched[answer + 2] as OneRegisterInstruction).registerA))
                assertEquals("$name: the name right after", JOIN_STATE_QUERY_FLAG,
                    ((patched[answer + 3] as ReferenceInstruction).reference as StringReference).string)
                val rest = patched.filterIndexed { index, _ -> index != answer + 1 && index != answer + 2 }
                assertEquals("$name: the rest is Facebook's", code.map { it.opcode }, rest.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The field names and the group type the extension reads by are the ones the patch finds by. */
    @Test
    fun `the extension reads the names the patch anchors on`() {
        assertEquals(STORY_TARGET_FIELD, ExtensionDex.stringConstant(GROUP_POSTS, "TARGET_FIELD"))
        assertEquals(VIEWER_JOIN_STATE_FIELD, ExtensionDex.stringConstant(GROUP_POSTS, "JOIN_STATE_FIELD"))
        assertEquals("Group", ExtensionDex.stringConstant(GROUP_POSTS, "GROUP_TYPE"))
        val stubs = ExtensionDex.classDef(GROUP_POSTS).methods.map { it.name }
        assertTrue("the extension has no $STORY_TARGET_STUB", STORY_TARGET_STUB in stubs)
        assertTrue("the extension has no $JOIN_STATE_STUB", JOIN_STATE_STUB in stubs)
        val hook = ExtensionDex.classDef(GROUP_POSTS).methods.singleOrNull { it.name == "askForJoinState" }
        assertNotNull("the extension has no GroupPosts.askForJoinState", hook)
        assertEquals("askForJoinState's shape", ASK_FOR_JOIN_STATE,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("askForJoinState isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
