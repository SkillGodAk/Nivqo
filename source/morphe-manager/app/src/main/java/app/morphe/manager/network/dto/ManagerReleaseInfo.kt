package app.morphe.manager.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data structure for app-release.json file.
 */
@Serializable
data class ManagerReleaseInfo(
    @SerialName("version")
    val version: String,
    @SerialName("download_url")
    val downloadUrl: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("description")
    val description: String,
    @SerialName("signature_download_url")
    val signatureDownloadUrl: String? = null,
    @SerialName("version_code")
    val versionCode: Long? = null,
)
