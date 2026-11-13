package com.noyclic.questphonexr.tracking

/**
 * A source that can produce normalized [HandTrackingFrame] observations for EndXR.
 *
 * Providers do not own OpenXR state. They only produce observations; the same gesture,
 * pose, joint and controller encoders consume frames from every provider.
 */
enum class TrackingProviderId(val wireId: String) {
    MEDIAPIPE("mediapipe"),
    TFLITE_CUSTOM("tflite_custom"),
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
    /** Static device/profile preference; measured frame quality is considered as a tie-break. */
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

data class TrackingProviderManagerSnapshot(
    val mode: TrackingSelectionMode,
    val requestedProvider: TrackingProviderId,
    val activeProvider: TrackingProviderId?,
    val activeFrameAgeMs: Long?,
    val statusByProvider: Map<TrackingProviderId, TrackingProviderAvailability>,
    val reason: String
)

/**
 * Source arbitration for EndXR's common hand-frame pipeline.
 *
 * The manager intentionally does not instantiate camera/model/network resources. Each
 * provider owns its own lifecycle and submits completed observations here. Only READY or
 * EXPERIMENTAL providers can publish. AUTO chooses among fresh providers; MANUAL never
 * silently substitutes a different source when the requested one is missing or stale.
 */
class TrackingProviderManager(
    descriptors: List<TrackingProviderDescriptor> = DEFAULT_PROVIDERS,
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS
) {
    private data class ProviderState(
        var descriptor: TrackingProviderDescriptor,
        var latestFrame: PublishedTrackingFrame? = null
    )

    private val providers = LinkedHashMap<TrackingProviderId, ProviderState>()
    private var mode = TrackingSelectionMode.AUTO
    private var requestedProvider = TrackingProviderId.MEDIAPIPE

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
    }

    /** Selects a concrete provider only when its adapter is actually available. */
    @Synchronized
    fun selectProvider(providerId: TrackingProviderId): Boolean {
        val state = providers[providerId] ?: return false
        if (!state.descriptor.availability.canPublishFrames) return false
        requestedProvider = providerId
        mode = TrackingSelectionMode.MANUAL
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
        if (!availability.canPublishFrames) state.latestFrame = null
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
        qualityScore: Float = 1f,
        receivedAtUptimeMs: Long
    ): PublishedTrackingFrame? {
        val state = providers[providerId] ?: return null
        if (!state.descriptor.availability.canPublishFrames) return null
        if (frame.timestampMs <= 0L || receivedAtUptimeMs < 0L ||
            !qualityScore.isFinite() || qualityScore !in 0f..1f ||
            frame.hands.size > state.descriptor.capabilities.maxHands
        ) return null

        val previous = state.latestFrame
        if (previous != null && frame.timestampMs <= previous.frame.timestampMs) return null

        val published = PublishedTrackingFrame(
            providerId = providerId,
            frame = frame,
            qualityScore = qualityScore,
            receivedAtUptimeMs = receivedAtUptimeMs
        )
        state.latestFrame = published

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
    }

    @Synchronized
    fun descriptors(): List<TrackingProviderDescriptor> =
        providers.values.map { it.descriptor }

    @Synchronized
    fun snapshot(nowUptimeMs: Long): TrackingProviderManagerSnapshot {
        val active = resolveActiveFrame(nowUptimeMs)
        val age = active?.let { (nowUptimeMs - it.receivedAtUptimeMs).coerceAtLeast(0L) }
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
            reason = reason
        )
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

        return providers.values.asSequence()
            .filter { it.descriptor.availability.canPublishFrames }
            .mapNotNull { state ->
                state.latestFrame?.takeIf(::isFresh)?.let { state.descriptor to it }
            }
            .maxWithOrNull(
                compareBy<Pair<TrackingProviderDescriptor, PublishedTrackingFrame>> {
                    it.first.autoPriority * PRIORITY_WEIGHT + it.second.qualityScore
                }.thenBy { it.second.receivedAtUptimeMs }
            )
            ?.second
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 300L
        private const val PRIORITY_WEIGHT = 0.01f

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
                id = TrackingProviderId.TFLITE_CUSTOM,
                displayName = "Custom TFLite model",
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
