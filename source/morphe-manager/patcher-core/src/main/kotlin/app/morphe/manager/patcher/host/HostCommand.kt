/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the app sends the patcher host, one per line. The counterpart of [HostEvent], built from the
 * same code on both ends.
 */
@Serializable
sealed interface HostCommand {
    /** Loads the patch bundles the run is likely to patch with, in place of any loaded before. */
    @Serializable
    @SerialName("preload")
    data class Preload(val bundles: List<String>) : HostCommand

    /**
     * Patches with the [app.morphe.manager.patcher.PatchRequest] written to [requestFile]. The run
     * always ends with [HostEvent.Finished], and the process with it.
     */
    @Serializable
    @SerialName("run")
    data class Run(val requestFile: String) : HostCommand

    companion object {
        fun encode(command: HostCommand): String = Json.encodeToString(serializer(), command)

        fun decode(line: String): HostCommand = Json.decodeFromString(serializer(), line)
    }
}
