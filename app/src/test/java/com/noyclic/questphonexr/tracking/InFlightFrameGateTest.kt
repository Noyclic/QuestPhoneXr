package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightFrameGateTest {
    @Test
    fun onlyOneInferenceRequestCanOwnTheGate() {
        val gate = InFlightFrameGate<Any>()
        val firstFrame = Any()
        val secondFrame = Any()

        val first = gate.begin(firstFrame)

        assertNotNull(first)
        assertNull(gate.begin(secondFrame))
        assertSame(first, gate.currentFor(firstFrame))
        assertNull(gate.currentFor(secondFrame))
    }

    @Test
    fun lateCompletionCannotReleaseANewerFrameAfterCancellation() {
        val gate = InFlightFrameGate<Any>()
        val oldFrame = Any()
        val newFrame = Any()
        val oldTicket = gate.begin(oldFrame)

        assertSame(oldTicket, gate.cancel())
        val newTicket = gate.begin(newFrame)

        assertNotNull(newTicket)
        assertFalse(gate.finish(checkNotNull(oldTicket)))
        assertTrue(gate.isCurrent(checkNotNull(newTicket)))
        assertSame(newTicket, gate.currentFor(newFrame))
    }

    @Test
    fun callbackMustMatchFrameIdentityRatherThanEquality() {
        val gate = InFlightFrameGate<EqualFrame>()
        val activeFrame = EqualFrame(7)
        val equalButDifferentFrame = EqualFrame(7)
        val ticket = gate.begin(activeFrame)

        assertEquals(activeFrame, equalButDifferentFrame)
        assertNull(gate.currentFor(equalButDifferentFrame))
        assertSame(ticket, gate.currentFor(activeFrame))
    }

    @Test
    fun finishingCurrentTicketReleasesGateExactlyOnce() {
        val gate = InFlightFrameGate<Any>()
        val ticket = gate.begin(Any())

        assertTrue(gate.finish(checkNotNull(ticket)))
        assertFalse(gate.finish(checkNotNull(ticket)))
        assertNull(gate.cancel())
    }

    private data class EqualFrame(val value: Int)
}
