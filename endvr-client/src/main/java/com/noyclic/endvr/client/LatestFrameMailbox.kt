package com.noyclic.endvr.client

/**
 * A bounded, latest-wins mailbox for decoded preview frames.
 *
 * The producer may decode faster than the UI can draw. Keep at most one pending
 * frame and discard replaced values immediately. Session tokens also prevent a
 * callback from an old network connection from updating a newer preview.
 */
internal class LatestFrameMailbox<T : Any>(
    private val discard: (T) -> Unit
) {
    private val lock = Any()
    private var generation = 0L
    private var pending: T? = null
    private var deliveryPosted = false

    fun beginSession(): Long = synchronized(lock) {
        generation += 1
        discardPending()
        deliveryPosted = false
        generation
    }

    fun endSession() {
        synchronized(lock) {
            generation += 1
            discardPending()
            deliveryPosted = false
        }
    }

    /**
     * Publishes a value and returns true only when the caller should post a
     * delivery callback to the consumer thread.
     */
    fun publish(session: Long, value: T): Boolean = synchronized(lock) {
        if (session != generation) {
            discardSafely(value)
            return@synchronized false
        }

        pending?.let(::discardSafely)
        pending = value
        if (deliveryPosted) {
            false
        } else {
            deliveryPosted = true
            true
        }
    }

    /** Transfers ownership of the newest pending value to the consumer. */
    fun take(session: Long): T? = synchronized(lock) {
        if (session != generation) return@synchronized null
        deliveryPosted = false
        pending.also { pending = null }
    }

    fun isCurrent(session: Long): Boolean = synchronized(lock) {
        session == generation
    }

    private fun discardPending() {
        val old = pending
        pending = null
        if (old != null) discardSafely(old)
    }

    private fun discardSafely(value: T) {
        runCatching { discard(value) }
    }
}
