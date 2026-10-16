package app.hushmessenger.patches.controls

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The checks behind the Meta AI, imagine and imagine yourself rows in Messenger 583's newer @ list. That list is a
 * render of its own that asks these checks directly instead of running the mention plugin dispatchers. The Meta AI row
 * asks a different check per chat type: regular chats, encrypted chats and community channels.
 */
internal const val AI_MENTION_ROWS = "ai_mention_rows"

internal const val CAPABILITIES = "Lcom/facebook/xapp/messaging/capability/vector/Capabilities;"

/**
 * The server flags each Meta AI row check reads in 583, and the one each imagine check reads. Meta renumbers them with
 * each release, so a check reading another release's numbers isn't taken.
 */
internal val META_AI_ROW_FLAGS = setOf(0x810637011b2d49L, 0x81063701042d42L)
internal val META_AI_CHAT_FLAGS = setOf(0x810637011b2d49L, 0x81063700042cffL)
internal const val META_AI_ENCRYPTED_FLAG = 0x81063700052d00L
internal const val IMAGINE_ROW_FLAG = 0x81063700402d10L
internal const val IMAGINE_YOURSELF_ROW_FLAG = 0x81063700f92d3eL

private fun rowsChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the @ list's Meta AI checks moved ($detail)")

private const val CAPABILITY = "$CAPABILITIES->A00(I)Z"

private fun Method.serverFlags(): Set<Long> = implementation?.instructions
    ?.filter { it.opcode == Opcode.CONST_WIDE }
    ?.mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }
    ?.toSet().orEmpty()

/**
 * The (FbUserSession)Z sibling the encrypted-chat check defers to, as named by its call, or null when it asks none or
 * a capability isn't checked first.
 */
private fun Method.encryptedChatSibling(): String? {
    val calls = implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()
    if (calls.none { it.toString() == CAPABILITY }) return null
    return calls.singleOrNull { it.definingClass == definingClass && it.returnType == "Z" &&
        it.parameterTypes.map { p -> p.toString() } == listOf(FB_USER_SESSION) }?.toString()
}

/**
 * Which row this instance check answers for, by its parameters and the server flag it reads:
 *
 *     ()Z                                                  both META_AI_ROW_FLAGS: Meta AI in community channels
 *     (FbUserSession, Capabilities)Z                       both META_AI_CHAT_FLAGS: Meta AI in regular chats
 *     (FbUserSession, Capabilities)Z, no flag of its own   a capability, then a sibling reading META_AI_ENCRYPTED_FLAG:
 *                                                          Meta AI in encrypted chats
 *     (FbUserSession, ThreadKey, Capabilities, Z, Z)Z      IMAGINE_ROW_FLAG: imagine
 *     (FbUserSession, ThreadKey, Capabilities)Z            IMAGINE_YOURSELF_ROW_FLAG: imagine yourself
 *
 * [sibling] looks a method up by its id; without it the encrypted-chat check is judged by its own shape alone.
 */
internal fun Method.aiMentionRow(sibling: ((String) -> Method?)? = null): String? {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "Z" || implementation == null) return null
    val params = parameterTypes.map { it.toString() }
    val flags = serverFlags()
    return when {
        params.isEmpty() && flags.containsAll(META_AI_ROW_FLAGS) -> "meta_ai"
        params == listOf(FB_USER_SESSION, CAPABILITIES) && flags.containsAll(META_AI_CHAT_FLAGS) -> "meta_ai_chats"
        params == listOf(FB_USER_SESSION, CAPABILITIES) && flags.isEmpty() && encryptedChatSibling()?.let { id ->
            sibling == null || sibling(id)?.serverFlags()?.contains(META_AI_ENCRYPTED_FLAG) == true } == true -> "meta_ai_encrypted"
        params == listOf(FB_USER_SESSION, THREAD_KEY_TYPE, CAPABILITIES, "Z", "Z") && IMAGINE_ROW_FLAG in flags -> "imagine"
        params == listOf(FB_USER_SESSION, THREAD_KEY_TYPE, CAPABILITIES) && IMAGINE_YOURSELF_ROW_FLAG in flags -> "imagine_yourself"
        else -> null
    }
}

/** The five checks, exactly one of each, all on one class, or none. */
internal fun findAiMentionRows(classes: Iterable<ClassDef>): List<Method> {
    val rows = classes.flatMap { cls ->
        val sibling = { id: String -> cls.methods.firstOrNull { it.hookId() == id } }
        cls.methods.mapNotNull { method -> method.aiMentionRow(sibling)?.let { it to method } }
    }.groupBy({ it.first }, { it.second })
    if (rows.size != 5 || rows.values.any { it.size != 1 }) return emptyList()
    return rows.values.map { it.single() }.takeIf { checks -> checks.map { it.definingClass }.toSet().size == 1 }.orEmpty()
}

internal fun MutableMethod.validateAiMentionRows() {
    if (aiMentionRow() == null) rowsChanged("unexpected check ${hookId()}")
    validateSwitch()
}

/** With the switch on each check answers false, as it does for an account without the feature, so its row isn't built. */
internal fun MutableMethod.injectAiMentionRows() {
    validateAiMentionRows()
    injectSwitch("$SETTINGS->hideMetaAi()Z", "0x0")
}
