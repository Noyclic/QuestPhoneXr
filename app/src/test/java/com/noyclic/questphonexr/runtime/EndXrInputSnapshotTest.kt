package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrInputSnapshotTest {
    private class TestClock(var now: Long = 1_000_000_000L) {
        fun advance(nanos: Long) { now += nanos }
    }

    private val clock = TestClock()

    @Test
    fun publishSanitizesAndPreservesIndependentLeftAndRightSamples() {
        EndXrInputSnapshotStore.publish(
            floatArrayOf(1f, 1.4f, -0.2f, -1.5f, 0.25f, 5f),
            floatArrayOf(1f, Float.NaN, 0.75f, 0.4f, -0.6f, 10f),
            nowElapsedRealtimeNanos = clock.now
        )

        val snapshot = EndXrInputSnapshotStore.snapshotForIpc(clock.now)
        assertTrue(snapshot.left.active)
        assertEquals(1f, snapshot.left.trigger)
        assertEquals(0f, snapshot.left.grip)
        assertEquals(-1f, snapshot.left.stickX)
        assertEquals(0.25f, snapshot.left.stickY)
        assertEquals(5, snapshot.left.buttons)

        assertTrue(snapshot.right.active)
        assertEquals(0f, snapshot.right.trigger)
        assertEquals(0.75f, snapshot.right.grip)
        assertEquals(0.4f, snapshot.right.stickX)
        assertEquals(-0.6f, snapshot.right.stickY)
        assertEquals(10, snapshot.right.buttons)
        assertFalse(snapshot.stale)
        EndXrInputSnapshotStore.clear(clock.now)
    }

    @Test
    fun inactiveControllerCannotLeakNonZeroAxesOrButtons() {
        EndXrInputSnapshotStore.publish(
            floatArrayOf(0f, 0.9f, 0.8f, 0.7f, -0.7f, 31f),
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f),
            nowElapsedRealtimeNanos = clock.now
        )

        val snapshot = EndXrInputSnapshotStore.snapshotForIpc(clock.now)
        assertEquals(EndXrControllerSample.EMPTY, snapshot.left)
        assertEquals(EndXrControllerSample.EMPTY, snapshot.right)
    }

    @Test
    fun oldSnapshotsBecomeNeutralAfterTheThreeHundredMillisecondLease() {
        EndXrInputSnapshotStore.publish(
            floatArrayOf(1f, 1f, 1f, 0.5f, -0.5f, 1f),
            floatArrayOf(1f, 0.5f, 0.5f, -0.5f, 0.5f, 2f),
            nowElapsedRealtimeNanos = clock.now
        )
        val fresh = EndXrInputSnapshotStore.snapshotForIpc(clock.now)
        val stale = EndXrInputSnapshotStore.snapshotForIpc(
            fresh.updatedAtElapsedRealtimeNanos +
                EndXrInputSnapshotStore.MAX_SAMPLE_AGE_NANOS + 1L
        )

        assertTrue(stale.stale)
        assertEquals(EndXrControllerSample.EMPTY, stale.left)
        assertEquals(EndXrControllerSample.EMPTY, stale.right)
    }

    @Test
    fun clearImmediatelyPublishesNeutralInput() {
        EndXrInputSnapshotStore.publish(
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 31f),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 31f),
            nowElapsedRealtimeNanos = clock.now
        )
        val before = EndXrInputSnapshotStore.snapshotForIpc(clock.now)
        clock.advance(1_000L)
        EndXrInputSnapshotStore.clear(clock.now)
        val after = EndXrInputSnapshotStore.snapshotForIpc(clock.now)

        assertTrue(after.sequence > before.sequence)
        assertTrue(after.updatedAtElapsedRealtimeNanos >= before.updatedAtElapsedRealtimeNanos)
        assertEquals(EndXrControllerSample.EMPTY, after.left)
        assertEquals(EndXrControllerSample.EMPTY, after.right)
    }
}
