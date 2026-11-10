package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeBrokerAbiSelectorTest {
    private val deviceAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a"
    )

    @Test
    fun selects32BitGameAbiEvenWhenPhonePrefers64Bit() {
        assertEquals(
            "armeabi-v7a",
            RuntimeBrokerAbiSelector.select(
                deviceSupportedAbis = deviceAbis,
                apkNativeAbis = listOf("armeabi-v7a"),
                installedNativeLibraryDir = "/data/app/game/lib/arm"
            )
        )
    }

    @Test
    fun installedPackageAbiOverridesStaticApkAbiList() {
        assertEquals(
            "armeabi-v7a",
            RuntimeBrokerAbiSelector.select(
                deviceSupportedAbis = deviceAbis,
                apkNativeAbis = listOf("arm64-v8a", "armeabi-v7a"),
                installedNativeLibraryDir = "/data/app/game/lib/arm"
            )
        )
    }

    @Test
    fun choosesFirstDevicePreferredAbiWhenStaticApkHasBoth() {
        assertEquals(
            "arm64-v8a",
            RuntimeBrokerAbiSelector.select(
                deviceSupportedAbis = deviceAbis,
                apkNativeAbis = listOf("armeabi-v7a", "arm64-v8a"),
                installedNativeLibraryDir = null
            )
        )
    }

    @Test
    fun fallsBackToDeviceAbiForManagedOnlyApp() {
        assertEquals(
            "arm64-v8a",
            RuntimeBrokerAbiSelector.select(
                deviceSupportedAbis = deviceAbis,
                apkNativeAbis = emptyList(),
                installedNativeLibraryDir = null
            )
        )
    }

    @Test
    fun returnsNullWhenDeviceHasNoSupportedAbis() {
        assertNull(
            RuntimeBrokerAbiSelector.select(
                deviceSupportedAbis = emptyList(),
                apkNativeAbis = listOf("arm64-v8a"),
                installedNativeLibraryDir = "/data/app/game/lib/arm64"
            )
        )
    }
}
