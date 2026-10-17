/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.extension

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import java.util.logging.Logger

/**
 * Where a patch writes at patch time. Morphe's desktop CLI prints every logger named under
 * `app.morphe`, a warning as a `WARNING:` line, and Morphe Manager shows them in its patch log.
 */
internal val patchLog: Logger = Logger.getLogger("app.morphe.patches.facebook")

/**
 * What a patch that works down a list of separate targets does when a build lacks some of them.
 *
 * [handle] is asked about each target once, in order, and does the patch's work on it. It answers
 * null when the target was there and has been dealt with, or why it wasn't, naming the target. A
 * build with some of the list gets the protection that's left, and the patch log names each target
 * the patch went without, one warning apiece, so a partly renamed build no longer passes quietly.
 * A build with none of them stops the patch, naming every one: applying then would claim a
 * protection the app doesn't get.
 *
 * Targets in [expectedMissing] are the ones Facebook took out before the declared build, kept on
 * the list for a build that brings them back. Those get one plain line between them instead of a
 * warning each, so a log that turns yellow means something is missing that shouldn't be (issue
 * #162). One of them turning up is handled like any other target.
 *
 * @param what the targets' kind in the plural, as the messages say it ("ad prefetch schedulers")
 * @return how many targets were dealt with
 */
internal fun <T> handleTargets(
    patch: String,
    what: String,
    targets: List<T>,
    expectedMissing: Set<T> = emptySet(),
    handle: (T) -> String?,
): Int {
    require(targets.isNotEmpty()) { "$patch has no $what to look for" }
    val missing = targets.mapNotNull { target -> handle(target)?.let { reason -> target to reason } }
    if (missing.size == targets.size) {
        throw PatchException(
            "$patch: this Facebook build has none of the ${targets.size} $what the patch works on. " +
                missing.joinToString("; ", postfix = ".") { it.second },
        )
    }
    val handled = targets.size - missing.size
    val (expected, unexpected) = missing.partition { it.first in expectedMissing }
    if (expected.isNotEmpty()) {
        patchLog.info(
            "$patch: ${expected.size} of the ${targets.size} $what left Facebook before " +
                "${AppCompatibilities.FACEBOOK_TARGET_VERSION}, which is expected. The patch goes on with the $handled it found.",
        )
    }
    unexpected.forEach { (_, reason) ->
        patchLog.warning("$patch: $reason. The patch goes on with the $handled of ${targets.size} $what it found.")
    }
    return handled
}

/** A class descriptor as Java writes the name: `Lcom/facebook/Foo;` is `com.facebook.Foo`. */
internal fun javaName(descriptor: String): String =
    descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')
