/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.patch

import app.morphe.patcher.patch.ApkArchitecture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The architecture patches declare their availability against. Reading it wrong hides a patch the
 * app needs, or offers one the APK has no native code for.
 */
class ApkArchitectureResolverTest {
    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val arm32Device = listOf("armeabi-v7a", "armeabi")

    private fun resolve(vararg abis: String, device: List<String> = arm64Device) =
        ApkArchitectureResolver.of(abis.toList(), device)

    @Test
    fun `an APK without native libraries is universal`() {
        assertEquals(ApkArchitecture.UNIVERSAL, resolve())
    }

    @Test
    fun `a single ABI is reported as it is`() {
        assertEquals(ApkArchitecture.ARM64_V8A, resolve("arm64-v8a"))
        assertEquals(ApkArchitecture.ARMEABI_V7A, resolve("armeabi-v7a"))
        assertEquals(ApkArchitecture.X86_64, resolve("x86_64"))
        assertEquals(ApkArchitecture.X86, resolve("x86"))
    }

    @Test
    fun `a fat APK is reported by the ABI the device prefers`() {
        val abis = arrayOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

        assertEquals(ApkArchitecture.ARM64_V8A, resolve(*abis))
        assertEquals(ApkArchitecture.ARMEABI_V7A, resolve(*abis, device = arm32Device))
    }

    @Test
    fun `an APK the device cannot run is reported by what it carries`() {
        assertEquals(ApkArchitecture.X86_64, resolve("x86_64", "x86"))
    }

    @Test
    fun `an unknown ABI is not an architecture patches can be asked about`() {
        assertEquals(ApkArchitecture.UNIVERSAL, resolve("riscv64"))
        assertEquals(ApkArchitecture.ARM64_V8A, resolve("riscv64", "arm64-v8a"))
    }

    @Test
    fun `legacy armeabi counts as the 32 bit ARM target`() {
        assertEquals(ApkArchitecture.ARMEABI_V7A, resolve("armeabi", device = arm32Device))
    }
}
