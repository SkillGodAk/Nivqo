/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.runtime.jvm

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * How [app.morphe.manager.patcher.runtime.PatcherService] starts the JVM, settled by the app so
 * the service only has to carry it out.
 *
 * @param preload Libraries of the runtime that others of it link against, dependencies first.
 * @param mainClass The class whose main runs, in JNI form such as `a/b/MainKt`.
 * @param heapLimitMb The heap to give the JVM, which a 32-bit process may only partly find room for.
 */
@Parcelize
data class JvmLaunch(
    val libjvm: String,
    val preload: List<String>,
    val options: List<String>,
    val mainClass: String,
    val heapLimitMb: Int
) : Parcelable
