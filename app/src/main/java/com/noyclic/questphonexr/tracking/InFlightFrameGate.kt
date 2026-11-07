package com.noyclic.questphonexr.tracking

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Associates each asynchronous inference callback with the exact frame request that created it.
 *
 * MediaPipe callbacks can arrive after an error/cancellation. A ticket uses both a monotonically
 * increasing sequence and resource identity, so an old callback cannot release a newer request.
 */
internal class InFlightFrameGate<T : Any> {
    internal data class Ticket<T : Any>(
        val sequence: Long,
        val frame: T
    )

    private val nextSequence = AtomicLong(0L)
    private val active = AtomicReference<Ticket<T>?>(null)

    fun begin(frame: T): Ticket<T>? {
        val ticket = Ticket(nextSequence.incrementAndGet(), frame)
        return if (active.compareAndSet(null, ticket)) ticket else null
    }

    fun currentFor(frame: T): Ticket<T>? {
        return active.get()?.takeIf { it.frame === frame }
    }

    fun isCurrent(ticket: Ticket<T>): Boolean = active.get() === ticket

    fun finish(ticket: Ticket<T>): Boolean = active.compareAndSet(ticket, null)

    fun cancel(): Ticket<T>? = active.getAndSet(null)
}
