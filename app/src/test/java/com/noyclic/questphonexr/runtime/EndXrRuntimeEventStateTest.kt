package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrRuntimeEventStateTest {
    private val createInstance = EndXrRuntimeEventContentProvider.EVENT_CREATE_INSTANCE
    private val createSession = EndXrRuntimeEventContentProvider.EVENT_CREATE_SESSION
    private val beginSession = EndXrRuntimeEventContentProvider.EVENT_BEGIN_SESSION
    private val endFrame = EndXrRuntimeEventContentProvider.EVENT_END_FRAME
    private val destroySession = EndXrRuntimeEventContentProvider.EVENT_DESTROY_SESSION
    private val destroyInstance = EndXrRuntimeEventContentProvider.EVENT_DESTROY_INSTANCE

    @Test
    fun completeLifecycleRetainsEvidenceAfterDestroy() {
        var state = EndXrRuntimeEventState()

        state = state.reduce(createInstance, "game-instance", null, 0L)!!.state
        state = state.reduce(createSession, "game-instance", "session-1", 0L)!!.state
        state = state.reduce(beginSession, "game-instance", "session-1", 0L)!!.state
        state = state.reduce(endFrame, "game-instance", "session-1", 1L)!!.state
        state = state.reduce(endFrame, "game-instance", "session-1", 30L)!!.state
        state = state.reduce(destroySession, "game-instance", "session-1", 0L)!!.state
        state = state.reduce(destroyInstance, "game-instance", null, 0L)!!.state

        assertFalse(state.instanceCreated)
        assertFalse(state.sessionCreated)
        assertFalse(state.sessionRunning)
        assertTrue(state.instanceSeen)
        assertTrue(state.sessionSeen)
        assertTrue(state.sessionEverRunning)
        assertEquals(30L, state.frameCount)
    }

    @Test
    fun eventsFromAnotherInstanceAreRejected() {
        val state = EndXrRuntimeEventState()
            .reduce(createInstance, "first", null, 0L)!!.state
            .reduce(createSession, "first", "session-1", 0L)!!.state

        assertNull(state.reduce(beginSession, "second", "session-1", 0L))
        assertNull(state.reduce(endFrame, "second", "session-1", 1L))
        assertNull(state.reduce(destroyInstance, "second", null, 0L))
    }

    @Test
    fun sessionCannotBeginBeforeCreationAndFramesCannotArriveBeforeRunning() {
        val instance = EndXrRuntimeEventState()
            .reduce(createInstance, "game", null, 0L)!!.state

        assertNull(instance.reduce(beginSession, "game", "session", 0L))
        assertNull(instance.reduce(endFrame, "game", "session", 1L))
        assertNull(instance.reduce(destroySession, "game", "session", 0L))
    }

    @Test
    fun frameCountsMustBePositiveAndMonotonic() {
        val running = EndXrRuntimeEventState()
            .reduce(createInstance, "game", null, 0L)!!.state
            .reduce(createSession, "game", "session", 0L)!!.state
            .reduce(beginSession, "game", "session", 0L)!!.state

        assertNull(running.reduce(endFrame, "game", "session", 0L))
        val frame30 = running.reduce(endFrame, "game", "session", 30L)!!.state
        assertTrue(frame30.reduce(endFrame, "game", "session", 30L)!!.changed.not())
        assertNull(frame30.reduce(endFrame, "game", "session", 29L))
    }

    @Test
    fun duplicateCreateAndBeginEventsAreIdempotent() {
        val instance = EndXrRuntimeEventState()
            .reduce(createInstance, "game", null, 0L)!!.state
        assertFalse(instance.reduce(createInstance, "game", null, 0L)!!.changed)

        val session = instance.reduce(createSession, "game", "session", 0L)!!.state
        assertFalse(session.reduce(createSession, "game", "session", 0L)!!.changed)

        val running = session.reduce(beginSession, "game", "session", 0L)!!.state
        assertFalse(running.reduce(beginSession, "game", "session", 0L)!!.changed)
    }

    @Test
    fun newInstanceResetsPreviousSessionAndFrameEvidence() {
        val old = EndXrRuntimeEventState()
            .reduce(createInstance, "old", null, 0L)!!.state
            .reduce(createSession, "old", "old-session", 0L)!!.state
            .reduce(beginSession, "old", "old-session", 0L)!!.state
            .reduce(endFrame, "old", "old-session", 30L)!!.state

        val fresh = old.reduce(createInstance, "new", null, 0L)!!.state

        assertEquals("new", fresh.instanceId)
        assertNull(fresh.sessionId)
        assertTrue(fresh.instanceCreated)
        assertFalse(fresh.sessionCreated)
        assertFalse(fresh.sessionRunning)
        assertTrue(fresh.instanceSeen)
        assertFalse(fresh.sessionSeen)
        assertFalse(fresh.sessionEverRunning)
        assertEquals(0L, fresh.frameCount)
    }

    @Test
    fun malformedIdentityAndUnknownEventAreRejected() {
        val state = EndXrRuntimeEventState()
        assertNull(state.reduce(createInstance, "", null, 0L))
        assertNull(state.reduce(createInstance, "game", null, -1L))
        assertNull(state.reduce("fake_event", "game", null, 0L))
    }
}
