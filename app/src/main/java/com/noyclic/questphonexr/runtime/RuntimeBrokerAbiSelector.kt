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
        val libraryDirectory = installedNativeLibraryDir.orEmpty().lowercase()
        val directoryName = libraryDirectory.substringAfterLast('/')
        val installedAbi = when (directoryName) {
            "arm64", "arm64-v8a" -> "arm64-v8a"
            "arm", "armeabi-v7a" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }
        return installedAbi?.takeIf { it in deviceSupportedAbis }
            ?: deviceSupportedAbis.firstOrNull { it in apkNativeAbis }
            ?: deviceSupportedAbis.firstOrNull()
    }
}
