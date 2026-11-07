package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InFlightFrameGateTest {
    @Test
    fun onlyOneInferenceRequestCanOwnTheGate() {
        val gate = InFlightFrameGate<Any>()
        val firstFrame = Any()
        val secondFrame = Any()

        val first = gate.begin(firstFrame, timestampMs = 10L)

        assertNotNull(first)
        assertNull(gate.begin(secondFrame, timestampMs = 11L))
        assertSame(first, gate.currentForTimestamp(10L))
        assertNull(gate.currentForTimestamp(11L))
    }

    @Test
    fun callbackImageWrapperIsMatchedByTimestampRatherThanObjectIdentity() {
        val gate = InFlightFrameGate<Any>()
        val submittedImage = Any()
        // MediaPipe's Java adapter creates a separate MPImage for its output image packet.
        val callbackImageWrapper = Any()
        val ticket = gate.begin(submittedImage, timestampMs = 42L)

        assertNotSame(submittedImage, callbackImageWrapper)
        assertSame(ticket, gate.currentForTimestamp(42L))
        assertNull(gate.currentForTimestamp(43L))
    }

    @Test
    fun lateCompletionCannotReleaseANewerFrameAfterCancellation() {
        val gate = InFlightFrameGate<Any>()
        val oldFrame = Any()
        val newFrame = Any()
        val oldTicket = gate.begin(oldFrame, timestampMs = 100L)

        assertSame(oldTicket, gate.cancel())
        val newTicket = gate.begin(newFrame, timestampMs = 101L)

        assertNotNull(newTicket)
        assertFalse(gate.finish(checkNotNull(oldTicket)))
        assertNull(gate.currentForTimestamp(100L))
        assertTrue(gate.isCurrent(checkNotNull(newTicket)))
        assertSame(newTicket, gate.currentForTimestamp(101L))
    }

    @Test
    fun equalTimestampIsRequiredToFindCurrentRequest() {
        val gate = InFlightFrameGate<Any>()
        val ticket = gate.begin(Any(), timestampMs = 7L)

        assertNull(gate.currentForTimestamp(6L))
        assertNull(gate.currentForTimestamp(8L))
        assertSame(ticket, gate.currentForTimestamp(7L))
    }

    @Test
    fun finishingCurrentTicketReleasesGateExactlyOnce() {
        val gate = InFlightFrameGate<Any>()
        val ticket = gate.begin(Any(), timestampMs = 1L)

        assertTrue(gate.finish(checkNotNull(ticket)))
        assertFalse(gate.finish(checkNotNull(ticket)))
        assertNull(gate.cancel())
    }
}
