package com.noyclic.questphonexr.performance

/**
 * Lightweight hardware signals used to choose a conservative default workload.
 *
 * These values are capabilities, not benchmark results. In particular, core count and
 * reported OpenGL ES version do not measure actual CPU/GPU throughput.
 */
data class DeviceCapabilities(
    val totalRamMb: Int,
    val appMemoryClassMb: Int,
    val processorCount: Int,
    val openGlEsMajor: Int,
    val openGlEsMinor: Int,
    val isLowRamDevice: Boolean
)

enum class DevicePerformanceTier(val label: String) {
    ECONOMY("Экономный"),
    BALANCED("Сбалансированный"),
    CAPABLE("Ресурсный")
}

/** A small workload preset shared by the sandbox and, later, game adapters. */
data class DevicePerformancePreset(
    val tier: DevicePerformanceTier,
    val maxRenderFps: Int,
    val cameraWidthPx: Int,
    val cameraHeightPx: Int,
    val maxTrackedHands: Int,
    val minimumInferenceIntervalMs: Long
) {
    init {
        require(maxRenderFps > 0)
        require(cameraWidthPx > 0 && cameraHeightPx > 0)
        require(maxTrackedHands in 1..2)
        require(minimumInferenceIntervalMs >= 0L) { "Inference interval cannot be negative." }
    }
}

object DevicePerformancePolicy {
    private val economy = DevicePerformancePreset(
        tier = DevicePerformanceTier.ECONOMY,
        maxRenderFps = 20,
        cameraWidthPx = 320,
        cameraHeightPx = 240,
        maxTrackedHands = 2,
        minimumInferenceIntervalMs = 80L
    )

    private val balanced = DevicePerformancePreset(
        tier = DevicePerformanceTier.BALANCED,
        maxRenderFps = 30,
        cameraWidthPx = 480,
        cameraHeightPx = 360,
        maxTrackedHands = 2,
        minimumInferenceIntervalMs = 66L
    )

    private val capable = DevicePerformancePreset(
        tier = DevicePerformanceTier.CAPABLE,
        maxRenderFps = 45,
        cameraWidthPx = 640,
        cameraHeightPx = 480,
        maxTrackedHands = 2,
        minimumInferenceIntervalMs = 50L
    )

    fun recommend(capabilities: DeviceCapabilities): DevicePerformancePreset {
        val limitedMemory = capabilities.totalRamMb in 1..3_499 ||
            capabilities.appMemoryClassMb in 1..128
        val limitedCpu = capabilities.processorCount in 1..4
        val limitedGraphics = capabilities.openGlEsMajor in 1..2

        if (capabilities.isLowRamDevice || limitedMemory || limitedCpu || limitedGraphics) {
            return economy
        }

        val hasRoomierResources = capabilities.totalRamMb >= 8_192 &&
            capabilities.appMemoryClassMb >= 256 &&
            capabilities.processorCount >= 8 &&
            (capabilities.openGlEsMajor > 3 ||
                capabilities.openGlEsMajor == 3 && capabilities.openGlEsMinor >= 2)

        return if (hasRoomierResources) capable else balanced
    }

    /** Automatic policy can lower a user's cap, but never raises it above their choice. */
    fun effectiveRenderFps(
        userRequestedFps: Int,
        preset: DevicePerformancePreset
    ): Int = userRequestedFps.coerceAtLeast(1).coerceAtMost(preset.maxRenderFps)
}
