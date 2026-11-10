package com.noyclic.questphonexr.runtime

/**
 * Predicts the ABI used by an installed Android app for RuntimeBroker lookup.
 * The extracted native-library directory is strongest evidence because Package
 * Manager has already selected an ABI for that installed package.
 */
internal object RuntimeBrokerAbiSelector {
    fun select(
        deviceSupportedAbis: List<String>,
        apkNativeAbis: Collection<String>,
        installedNativeLibraryDir: String?
    ): String? {
        if (deviceSupportedAbis.isEmpty()) return null
        val path = installedNativeLibraryDir.orEmpty().lowercase()
        val installedAbi = when {
            path.contains("arm64") -> "arm64-v8a"
            path.contains("/arm") -> "armeabi-v7a"
            path.contains("x86_64") -> "x86_64"
            path.contains("/x86") -> "x86"
            else -> null
        }
        return installedAbi?.takeIf { it in deviceSupportedAbis }
            ?: deviceSupportedAbis.firstOrNull { it in apkNativeAbis }
            ?: deviceSupportedAbis.firstOrNull()
    }
}
