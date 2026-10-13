package app.hushmessenger.patches

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

/**
 * The stock arm64 Messenger builds the patches were checked against.
 *
 * A version name alone is insufficient: Meta publishes several DEX variants with that name. Each
 * build is recorded in scripts/profiles by CompatReport; the APKs themselves are local research
 * input, never part of this repository.
 */
internal object MessengerTarget {
    const val PACKAGE = "com.facebook.orca"
    const val MIN_SDK = 28

    /** Each supported version name and the version codes of its checked arm64 builds. */
    val VERSIONS: Map<String, List<Int>> = mapOf(
        "582.0.0.61.92" to listOf(
            346415686, 346415687, 346415690, 346415706, 346415707, 346415720, 346415721,
            346415723, 346415759, 346415772, 346415773, 346415774, 346415776, 346415777,
        ),
    )
    val VERSION_CODES = VERSIONS.values.flatten()

    private const val FACEBOOK_SIGNER =
        "e3f9e1e0cf99d0e56a055ba65e241b3399f7cea524326b0cdd6ec1327ed0fdc1"
    private const val META_SIGNER =
        "911d604446084ca7f4760b775bfc160fa8702441240a7258645d7a72c4312d27"

    /** "582.0.0.61.92 APK (version code 346415686 or 346415687)", joined with " or " across version names. */
    fun supportedApks(versions: Map<String, List<Int>> = VERSIONS): String =
        versions.entries.joinToString(" or ") { (name, codes) -> "$name APK (version code ${codes.joinToString(" or ")})" }

    fun compatibility(versions: Map<String, List<Int>>) = Compatibility(
        name = "Messenger",
        packageName = PACKAGE,
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0084FF,
        signatures = setOf(FACEBOOK_SIGNER, META_SIGNER),
        targets = versions.map { (name, codes) ->
            val builds = if (codes.size == 1) "build ${codes.single()}"
                else "builds ${codes.dropLast(1).joinToString(", ")} and ${codes.last()}"
            AppTarget(
                version = name,
                versionCodes = null,
                minSdk = MIN_SDK,
                description = "Arm64 $builds; checked again during patching",
            )
        },
    )

    val COMPATIBILITY = compatibility(VERSIONS)
}
