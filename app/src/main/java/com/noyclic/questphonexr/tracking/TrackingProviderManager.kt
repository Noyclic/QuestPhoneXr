package com.noyclic.questphonexr.tracking

/**
 * A source that can produce normalized [HandTrackingFrame] observations for EndXR.
 *
 * Providers do not own OpenXR state. They only produce observations; the same gesture,
 * pose, joint and controller encoders consume frames from every provider.
 */
enum class TrackingProviderId(val wireId: String) {
    MEDIAPIPE("mediapipe"),
    LITERT_CUSTOM("litert_custom"),
    GVXRS_DOCK("gvxrs_dock")
}

enum class TrackingProviderAvailability {
    READY,
    EXPERIMENTAL,
    PLANNED,
    UNAVAILABLE;

    val canPublishFrames: Boolean
        get() = this == READY || this == EXPERIMENTAL
}

enum class TrackingSelectionMode {
    AUTO,
    MANUAL
}

enum class TrackingFrameRejection {
    PROVIDER_UNAVAILABLE,
    INVALID_TIMESTAMP,
    INVALID_RECEIVE_TIMESTAMP,
    INVALID_QUALITY_SCORE,
    INVALID_PIPELINE_LATENCY,
    TOO_MANY_HANDS,
    INSUFFICIENT_LANDMARKS,
    INVALID_LANDMARK,
    OUT_OF_ORDER_FRAME
}

data class TrackingProviderCapabilities(
    /** Number of ordered landmarks required by the shared hand input contract. */
    val landmarkCount: Int = 21,
    val providesHandLocal3d: Boolean = false,
    val providesHandedness: Boolean = true,
    val maxHands: Int = 1
) {
    init {
        require(landmarkCount in 1..64)
        require(maxHands in 1..2)
    }
}

data class TrackingProviderDescriptor(
    val id: TrackingProviderId,
    val displayName: String,
    val description: String,
    val availability: TrackingProviderAvailability,
    /** Small tie-break preference; measured frame quality and latency dominate AUTO mode. */
    val autoPriority: Int,
    val capabilities: TrackingProviderCapabilities
) {
    init {
        require(displayName.isNotBlank())
        require(autoPriority in 0..100)
    }
}

/** An accepted frame and the receiver-clock time at which it entered EndXR. */
data class PublishedTrackingFrame(
    val providerId: TrackingProviderId,
    val frame: HandTrackingFrame,
    val qualityScore: Float,
    val receivedAtUptimeMs: Long
)

data class TrackingProviderHealthSnapshot(
    val availability: TrackingProviderAvailability,
    val acceptedFrames: Long,
    val rejectedFrames: Long,
    val latestFrameAgeMs: Long?,
    val latestPipelineLatencyMs: Long?,
    val averagePipelineLatencyMs: Float,
    val latestQualityScore: Float?,
    val lastRejection: TrackingFrameRejection?
)

data class TrackingProviderManagerSnapshot(
    val mode: TrackingSelectionMode,
    val requestedProvider: TrackingProviderId,
    val activeProvider: TrackingProviderId?,
    val activeFrameAgeMs: Long?,
    val statusByProvider: Map<TrackingProviderId, TrackingProviderAvailability>,
    val healthByProvider: Map<TrackingProviderId, TrackingProviderHealthSnapshot>,
    val reason: String
)

/**
 * Source arbitration for EndXR's common hand-frame pipeline.
 *
 * The manager intentionally does not instantiate camera/model/network resources. Each
 * provider owns its own lifecycle and submits completed observations here. Only READY or
 * EXPERIMENTAL providers can publish. AUTO chooses among fresh providers using measured
 * frame quality and end-to-end provider latency; MANUAL never silently substitutes a source.
 */
class TrackingProviderManager(
    descriptors: List<TrackingProviderDescriptor> = DEFAULT_PROVIDERS,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS
) {
    private data class ProviderState(
        var descriptor: TrackingProviderDescriptor,
        var latestFrame: PublishedTrackingFrame? = null,
        var acceptedFrames: Long = 0L,
        var rejectedFrames: Long = 0L,
        var totalPipelineLatencyMs: Long = 0L,
        var lastRejection: TrackingFrameRejection? = null
    )

    private val providers = LinkedHashMap<TrackingProviderId, ProviderState>()
    private var mode = TrackingSelectionMode.AUTO
    private var requestedProvider = TrackingProviderId.MEDIAPIPE
    private var autoActiveProviderId: TrackingProviderId? = null

    init {
        require(staleAfterMs in 1L..5_000L) { "Tracking freshness window must be between 1 and 5000 ms." }
        require(descriptors.isNotEmpty()) { "At least one tracking provider must be registered." }
        descriptors.forEach { descriptor ->
            require(providers.put(descriptor.id, ProviderState(descriptor)) == null) {
                "Duplicate tracking provider: ${descriptor.id}"
            }
        }
        require(providers.containsKey(TrackingProviderId.MEDIAPIPE)) {
            "The default MediaPipe provider must be registered."
        }
    }

    @Synchronized
    fun setAutoMode() {
        mode = TrackingSelectionMode.AUTO
        autoActiveProviderId = null
    }

    /** Selects a concrete provider only when its adapter is actually available. */
    @Synchronized
    fun selectProvider(providerId: TrackingProviderId): Boolean {
        val state = providers[providerId] ?: return false
        if (!state.descriptor.availability.canPublishFrames) return false
        requestedProvider = providerId
        mode = TrackingSelectionMode.MANUAL
        autoActiveProviderId = null
        return true
    }

    /** Called by an adapter when a model, camera, or remote transport becomes available. */
    @Synchronized
    fun updateAvailability(
        providerId: TrackingProviderId,
        availability: TrackingProviderAvailability
    ): Boolean {
        val state = providers[providerId] ?: return false
        state.descriptor = state.descriptor.copy(availability = availability)
        if (!availability.canPublishFrames) {
            state.latestFrame = null
            if (autoActiveProviderId == providerId) autoActiveProviderId = null
        }
        return true
    }

    /**
     * Submits one source observation. A non-null return means this exact frame is the
     * currently selected source and may be passed to the shared EndXR input encoder.
     */
    @Synchronized
    fun publishFrame(
        providerId: TrackingProviderId,
        frame: HandTrackingFrame,
        qualityScore: Float = 0.5f,
        receivedAtUptimeMs: Long
    ): PublishedTrackingFrame? {
        val state = providers[providerId] ?: return null
        if (!state.descriptor.availability.canPublishFrames) {
            return reject(state, TrackingFrameRejection.PROVIDER_UNAVAILABLE)
        }
        if (frame.timestampMs <= 0L) {
            return reject(state, TrackingFrameRejection.INVALID_TIMESTAMP)
        }
        if (receivedAtUptimeMs < 0L) {
            return reject(state, TrackingFrameRejection.INVALID_RECEIVE_TIMESTAMP)
        }
        if (!qualityScore.isFinite() || qualityScore !in 0f..1f) {
            return reject(state, TrackingFrameRejection.INVALID_QUALITY_SCORE)
        }
        if (frame.pipelineLatencyMs < 0L || frame.preprocessingLatencyMs < 0L) {
            return reject(state, TrackingFrameRejection.INVALID_PIPELINE_LATENCY)
        }
        if (frame.hands.size > state.descriptor.capabilities.maxHands) {
            return reject(state, TrackingFrameRejection.TOO_MANY_HANDS)
        }

        val requiredLandmarkCount = state.descriptor.capabilities.landmarkCount
        for (hand in frame.hands) {
            if (hand.points.size < requiredLandmarkCount) {
                return reject(state, TrackingFrameRejection.INSUFFICIENT_LANDMARKS)
            }
            for (point in hand.points.take(requiredLandmarkCount)) {
                if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) {
                    return reject(state, TrackingFrameRejection.INVALID_LANDMARK)
                }
            }
        }

        val previous = state.latestFrame
        if (previous != null && frame.timestampMs <= previous.frame.timestampMs) {
            return reject(state, TrackingFrameRejection.OUT_OF_ORDER_FRAME)
        }

        val published = PublishedTrackingFrame(
            providerId = providerId,
            frame = frame,
            qualityScore = qualityScore,
            receivedAtUptimeMs = receivedAtUptimeMs
        )
        state.latestFrame = published
        state.acceptedFrames += 1L
        state.totalPipelineLatencyMs += frame.pipelineLatencyMs
        state.lastRejection = null

        val active = resolveActiveFrame(receivedAtUptimeMs) ?: return null
        return active.takeIf {
            it.providerId == providerId && it.frame.timestampMs == frame.timestampMs
        }
    }

    @Synchronized
    fun activeFrame(nowUptimeMs: Long): PublishedTrackingFrame? =
        resolveActiveFrame(nowUptimeMs)

    @Synchronized
    fun clearFrames() {
        providers.values.forEach { it.latestFrame = null }
        autoActiveProviderId = null
    }

    @Synchronized
    fun descriptors(): List<TrackingProviderDescriptor> =
        providers.values.map { it.descriptor }

    @Synchronized
    fun snapshot(nowUptimeMs: Long): TrackingProviderManagerSnapshot {
        val active = resolveActiveFrame(nowUptimeMs)
        val age = active?.let { (nowUptimeMs - it.receivedAtUptimeMs).coerceAtLeast(0L) }
        val health = providers.mapValues { (_, state) ->
            val latest = state.latestFrame
            TrackingProviderHealthSnapshot(
                availability = state.descriptor.availability,
                acceptedFrames = state.acceptedFrames,
                rejectedFrames = state.rejectedFrames,
                latestFrameAgeMs = latest?.let {
                    (nowUptimeMs - it.receivedAtUptimeMs).coerceAtLeast(0L)
                },
                latestPipelineLatencyMs = latest?.frame?.pipelineLatencyMs,
                averagePipelineLatencyMs = if (state.acceptedFrames == 0L) {
                    0f
                } else {
                    state.totalPipelineLatencyMs.toFloat() / state.acceptedFrames.toFloat()
                },
                latestQualityScore = latest?.qualityScore,
                lastRejection = state.lastRejection
            )
        }
        val reason = when {
            active != null && mode == TrackingSelectionMode.MANUAL -> "manual_provider_active"
            active != null -> "automatic_provider_active"
            mode == TrackingSelectionMode.MANUAL -> "manual_provider_waiting_for_fresh_frame"
            else -> "no_ready_provider_has_a_fresh_frame"
        }
        return TrackingProviderManagerSnapshot(
            mode = mode,
            requestedProvider = requestedProvider,
            activeProvider = active?.providerId,
            activeFrameAgeMs = age,
            statusByProvider = providers.mapValues { it.value.descriptor.availability },
            healthByProvider = health,
            reason = reason
        )
    }

    private fun reject(
        state: ProviderState,
        reason: TrackingFrameRejection
    ): PublishedTrackingFrame? {
        state.rejectedFrames += 1L
        state.lastRejection = reason
        return null
    }

    private fun resolveActiveFrame(nowUptimeMs: Long): PublishedTrackingFrame? {
        fun isFresh(sample: PublishedTrackingFrame): Boolean {
            val age = nowUptimeMs - sample.receivedAtUptimeMs
            return age in 0L..staleAfterMs
        }

        if (mode == TrackingSelectionMode.MANUAL) {
            val requested = providers[requestedProvider] ?: return null
            if (!requested.descriptor.availability.canPublishFrames) return null
            return requested.latestFrame?.takeIf(::isFresh)
        }

        val candidates = providers.values.asSequence()
            .filter { it.descriptor.availability.canPublishFrames }
            .mapNotNull { state ->
                state.latestFrame?.takeIf(::isFresh)?.let { state.descriptor to it }
            }
            .toList()
        val best = candidates.maxWithOrNull(
            compareBy<Pair<TrackingProviderDescriptor, PublishedTrackingFrame>> {
                autoScore(it.first, it.second)
            }.thenBy { it.second.receivedAtUptimeMs }
        ) ?: run {
            autoActiveProviderId = null
            return null
        }

        val current = autoActiveProviderId?.let { activeId ->
            candidates.firstOrNull { it.first.id == activeId }
        }
        if (current != null && current.first.id != best.first.id) {
            val currentScore = autoScore(current.first, current.second)
            val bestScore = autoScore(best.first, best.second)
            // Avoid oscillating between two cameras/providers whose measured scores
            // differ only by noise. A lost/stale active provider is replaced immediately.
            if (bestScore < currentScore + AUTO_SWITCH_MARGIN) return current.second
        }

        autoActiveProviderId = best.first.id
        return best.second
    }

    private fun autoScore(
        descriptor: TrackingProviderDescriptor,
        sample: PublishedTrackingFrame
    ): Float {
        // Latency is scored against a 200 ms budget. Provider priority is only a small
        // tie-break: a measurably faster/better provider should beat a default preference.
        val latencyScore = 1f -
            (sample.frame.pipelineLatencyMs.toFloat() / AUTO_LATENCY_BUDGET_MS).coerceIn(0f, 1f)
        val preferenceScore = descriptor.autoPriority / 100f
        return sample.qualityScore * QUALITY_WEIGHT +
            latencyScore * LATENCY_WEIGHT +
            preferenceScore * PRIORITY_WEIGHT
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 300L
        private const val AUTO_LATENCY_BUDGET_MS = 200f
        private const val QUALITY_WEIGHT = 0.65f
        private const val LATENCY_WEIGHT = 0.30f
        private const val PRIORITY_WEIGHT = 0.05f
        private const val AUTO_SWITCH_MARGIN = 0.04f

        val DEFAULT_PROVIDERS = listOf(
            TrackingProviderDescriptor(
                id = TrackingProviderId.MEDIAPIPE,
                displayName = "MediaPipe",
                description = "Current CameraX hand-landmark provider.",
                availability = TrackingProviderAvailability.READY,
                autoPriority = 80,
                capabilities = TrackingProviderCapabilities(
                    providesHandLocal3d = true,
                    providesHandedness = true,
                    maxHands = 2
                )
            ),
            TrackingProviderDescriptor(
                id = TrackingProviderId.LITERT_CUSTOM,
                displayName = "LiteRT 2.x custom hand-landmark model",
                description = "Reserved adapter for device-specific hand models; not connected yet.",
                availability = TrackingProviderAvailability.PLANNED,
                autoPriority = 75,
                capabilities = TrackingProviderCapabilities(
                    providesHandLocal3d = false,
                    providesHandedness = true,
                    maxHands = 2
                )
            ),
            TrackingProviderDescriptor(
                id = TrackingProviderId.GVXRS_DOCK,
                displayName = "GVXRS Dock Station",
                description = "Second-phone landmark source; packet contract exists, live transport is not connected.",
                availability = TrackingProviderAvailability.PLANNED,
                autoPriority = 70,
                capabilities = TrackingProviderCapabilities(
                    providesHandLocal3d = false,
                    providesHandedness = true,
                    maxHands = 2
                )
            )
        )
    }
}
