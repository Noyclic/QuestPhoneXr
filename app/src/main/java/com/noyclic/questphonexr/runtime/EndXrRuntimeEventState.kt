package com.noyclic.questphonexr.runtime

/**
 * Pure lifecycle reducer for the last EndXR instance observed by NextVRera.
 *
 * It rejects out-of-order or cross-instance events and makes duplicate IPC
 * deliveries idempotent. The caller persists the returned state atomically.
 */
internal data class EndXrRuntimeEventState(
    val instanceId: String? = null,
    val sessionId: String? = null,
    val instanceCreated: Boolean = false,
    val sessionCreated: Boolean = false,
    val sessionRunning: Boolean = false,
    val instanceSeen: Boolean = false,
    val sessionSeen: Boolean = false,
    val sessionEverRunning: Boolean = false,
    val frameCount: Long = 0L
) {
    data class Reduction(
        val state: EndXrRuntimeEventState,
        val changed: Boolean
    )

    fun reduce(
        event: String,
        nextInstanceId: String,
        nextSessionId: String?,
        nextFrameCount: Long
    ): Reduction? {
        if (nextInstanceId.isBlank() || nextInstanceId.length > 80 || nextFrameCount < 0L) {
            return null
        }
        if (nextSessionId != null &&
            (nextSessionId.isBlank() || nextSessionId.length > 80)
        ) {
            return null
        }

        return when (event) {
            EndXrRuntimeEventContentProvider.EVENT_CREATE_INSTANCE -> {
                if (instanceId == nextInstanceId && instanceCreated) {
                    Reduction(this, changed = false)
                } else {
                    Reduction(
                        EndXrRuntimeEventState(
                            instanceId = nextInstanceId,
                            instanceCreated = true,
                            instanceSeen = true
                        ),
                        changed = true
                    )
                }
            }

            EndXrRuntimeEventContentProvider.EVENT_CREATE_SESSION -> {
                val session = nextSessionId ?: return null
                if (instanceId != nextInstanceId || !instanceCreated) return null
                if (sessionCreated) {
                    if (sessionId == session) Reduction(this, changed = false) else null
                } else {
                    Reduction(
                        copy(
                            sessionId = session,
                            sessionCreated = true,
                            sessionRunning = false,
                            sessionSeen = true,
                            frameCount = 0L
                        ),
                        changed = true
                    )
                }
            }

            EndXrRuntimeEventContentProvider.EVENT_BEGIN_SESSION -> {
                val session = nextSessionId ?: return null
                if (instanceId != nextInstanceId || !instanceCreated ||
                    sessionId != session || !sessionCreated
                ) return null
                if (sessionRunning) {
                    Reduction(this, changed = false)
                } else {
                    Reduction(
                        copy(
                            sessionRunning = true,
                            sessionEverRunning = true
                        ),
                        changed = true
                    )
                }
            }

            EndXrRuntimeEventContentProvider.EVENT_END_FRAME -> {
                val session = nextSessionId ?: return null
                if (instanceId != nextInstanceId || !instanceCreated ||
                    sessionId != session || !sessionCreated || !sessionRunning ||
                    nextFrameCount == 0L
                ) return null
                when {
                    nextFrameCount == frameCount -> Reduction(this, changed = false)
                    nextFrameCount < frameCount -> null
                    else -> Reduction(copy(frameCount = nextFrameCount), changed = true)
                }
            }

            EndXrRuntimeEventContentProvider.EVENT_DESTROY_SESSION -> {
                val session = nextSessionId ?: return null
                if (instanceId != nextInstanceId || sessionId != session) return null
                if (!sessionCreated) {
                    Reduction(this, changed = false)
                } else {
                    Reduction(
                        copy(
                            sessionCreated = false,
                            sessionRunning = false
                        ),
                        changed = true
                    )
                }
            }

            EndXrRuntimeEventContentProvider.EVENT_DESTROY_INSTANCE -> {
                if (instanceId != nextInstanceId) return null
                if (!instanceCreated) {
                    Reduction(this, changed = false)
                } else {
                    Reduction(
                        copy(
                            instanceCreated = false,
                            sessionCreated = false,
                            sessionRunning = false
                        ),
                        changed = true
                    )
                }
            }

            else -> null
        }
    }
}
