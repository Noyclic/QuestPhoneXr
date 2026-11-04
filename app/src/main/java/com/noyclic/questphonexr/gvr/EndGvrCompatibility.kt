package com.noyclic.questphonexr.gvr

enum class EndGvrClassification {
    NO_GEAR_VR_EVIDENCE,
    LEGACY_OCULUS_MOBILE_SDK,
    GEAR_VR_MARKED
}

data class EndGvrAssessment(
    val classification: EndGvrClassification,
    val gearVrMarkers: List<String>,
    val legacyOculusMarkers: List<String>
) {
    val isGearVrMarked: Boolean
        get() = classification == EndGvrClassification.GEAR_VR_MARKED

    val hasLegacyOculusEvidence: Boolean
        get() = classification != EndGvrClassification.NO_GEAR_VR_EVIDENCE
}

/**
 * Static first-pass classifier. It does not install, load or execute APK code.
 * Explicit Samsung Gear VR evidence is kept separate from generic Oculus Mobile
 * SDK evidence so Quest apps are not automatically labelled as Gear VR titles.
 */
object EndGvrCompatibility {
    private val gearVrMetadataMarkers = listOf(
        "com.samsung.android.vr.application",
        "com.samsung.android.hmt.vr",
        "gearvr",
        "gear_vr",
        "gear-vr"
    )

    fun inspect(
        packageName: String,
        metadataKeys: Collection<String>,
        requiredFeatures: Collection<String>,
        archiveEntries: Collection<String>
    ): EndGvrAssessment {
        val normalizedPackage = packageName.lowercase()
        val normalizedMetadata = metadataKeys.map { it.lowercase() }
        val normalizedFeatures = requiredFeatures.map { it.lowercase() }
        val normalizedEntries = archiveEntries.map { it.lowercase() }

        val gearMarkers = linkedSetOf<String>()
        if (
            normalizedPackage.startsWith("com.samsung.android.hmt.vr") ||
            normalizedPackage.startsWith("com.samsung.android.vr")
        ) {
            gearMarkers += "Samsung Gear VR package namespace"
        }

        normalizedMetadata
            .filter { key -> gearVrMetadataMarkers.any { marker -> key.contains(marker) } }
            .forEach { gearMarkers += "Gear VR manifest metadata: $it" }

        normalizedFeatures
            .filter { feature ->
                feature.contains("gearvr") || feature.contains("gear_vr") ||
                    feature.contains("samsung.android.vr")
            }
            .forEach { gearMarkers += "Gear VR required feature: $it" }

        normalizedEntries
            .filter { entry ->
                entry.contains("gearvr") || entry.contains("gear_vr") ||
                    entry.contains("gear-vr")
            }
            .forEach { gearMarkers += "Gear VR archive path: $it" }

        val legacyOculusMarkers = linkedSetOf<String>()
        normalizedEntries
            .filter { it.substringAfterLast('/') == "libvrapi.so" }
            .forEach {
                legacyOculusMarkers += "Legacy Oculus Mobile SDK library: libvrapi.so"
            }

        val classification = when {
            gearMarkers.isNotEmpty() -> EndGvrClassification.GEAR_VR_MARKED
            legacyOculusMarkers.isNotEmpty() -> EndGvrClassification.LEGACY_OCULUS_MOBILE_SDK
            else -> EndGvrClassification.NO_GEAR_VR_EVIDENCE
        }

        return EndGvrAssessment(
            classification = classification,
            gearVrMarkers = gearMarkers.toList(),
            legacyOculusMarkers = legacyOculusMarkers.toList()
        )
    }
}
