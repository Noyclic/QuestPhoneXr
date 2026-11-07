package com.noyclic.endvr.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestFrameMailboxTest {
    @Test
    fun keepsOnlyTheNewestPendingFrameAndPostsOneDelivery() {
        val discarded = mutableListOf<Int>()
        val mailbox = LatestFrameMailbox<Int>(discarded::add)
        val session = mailbox.beginSession()

        assertTrue(mailbox.publish(session, 1))
        assertFalse(mailbox.publish(session, 2))
        assertFalse(mailbox.publish(session, 3))

        assertEquals(listOf(1, 2), discarded)
        assertEquals(3, mailbox.take(session))
        assertNull(mailbox.take(session))

        assertTrue(mailbox.publish(session, 4))
        assertEquals(4, mailbox.take(session))
        assertEquals(listOf(1, 2), discarded)
    }

    @Test
    fun staleSessionCannotPublishIntoTheNextConnection() {
        val discarded = mutableListOf<Int>()
        val mailbox = LatestFrameMailbox<Int>(discarded::add)
        val oldSession = mailbox.beginSession()
        assertTrue(mailbox.publish(oldSession, 10))

        val newSession = mailbox.beginSession()
        assertFalse(mailbox.isCurrent(oldSession))
        assertTrue(mailbox.isCurrent(newSession))
        assertEquals(listOf(10), discarded)

        assertFalse(mailbox.publish(oldSession, 11))
        assertTrue(mailbox.publish(newSession, 12))
        assertNull(mailbox.take(oldSession))
        assertEquals(12, mailbox.take(newSession))
        assertEquals(listOf(10, 11), discarded)
    }

    @Test
    fun endingSessionDiscardsPendingFrameAndInvalidatesQueuedDelivery() {
        val discarded = mutableListOf<Int>()
        val mailbox = LatestFrameMailbox<Int>(discarded::add)
        val session = mailbox.beginSession()
        assertTrue(mailbox.publish(session, 20))

        mailbox.endSession()

        assertFalse(mailbox.isCurrent(session))
        assertNull(mailbox.take(session))
        assertFalse(mailbox.publish(session, 21))
        assertEquals(listOf(20, 21), discarded)
    }
}
