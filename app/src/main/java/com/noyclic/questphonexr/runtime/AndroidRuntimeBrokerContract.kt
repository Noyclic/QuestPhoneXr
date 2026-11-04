package com.noyclic.questphonexr.runtime

/**
 * URI builders for the Android OpenXR RuntimeBroker contract.
 *
 * Khronos specifies:
 * /openxr/<major>/abi/<abi>/runtimes/active
 * /openxr/<major>/abi/<abi>/runtimes/<package>/functions
 */
internal object AndroidRuntimeBrokerContract {
    const val INSTALLABLE_BROKER_PACKAGE = "org.khronos.openxr.runtime_broker"
    const val INSTALLABLE_BROKER_MAIN_ACTIVITY =
        "org.khronos.openxr.runtime_broker.MainActivity"
    const val INSTALLABLE_BROKER_PLAY_STORE_URL =
        "https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker"

    private val allowedAuthorities = setOf(
        "org.khronos.openxr.runtime_broker",
        "org.khronos.openxr.system_runtime_broker"
    )

    fun activeRuntimeUri(authority: String, majorVersion: Int, abi: String): String {
        validate(authority, majorVersion, abi)
        return "content://$authority/openxr/$majorVersion/abi/$abi/runtimes/active"
    }

    fun functionMappingsUri(
        authority: String,
        majorVersion: Int,
        abi: String,
        packageName: String
    ): String {
        validate(authority, majorVersion, abi)
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*"))) {
            "Invalid Android package name."
        }
        return "content://$authority/openxr/$majorVersion/abi/$abi/runtimes/$packageName/functions"
    }

    private fun validate(authority: String, majorVersion: Int, abi: String) {
        require(authority in allowedAuthorities) { "Unknown OpenXR RuntimeBroker authority." }
        require(majorVersion > 0) { "OpenXR major version must be positive." }
        require(abi.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Android ABI." }
    }
}
