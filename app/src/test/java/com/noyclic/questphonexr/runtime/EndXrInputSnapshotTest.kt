package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrInputSnapshotTest {
    @Test
    fun publishSanitizesAndPreservesIndependentLeftAndRightSamples() {
        EndXrInputSnapshotStore.publish(
            floatArrayOf(1f, 1.4f, -0.2f, -1.5f, 0.25f, 5f),
            floatArrayOf(1f, Float.NaN, 0.75f, 0.4f, -0.6f, 10f)
        )

        val snapshot = EndXrInputSnapshotStore.snapshotForIpc()
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
        EndXrInputSnapshotStore.clear()
    }

    @Test
    fun inactiveControllerCannotLeakNonZeroAxesOrButtons() {
        EndXrInputSnapshotStore.publish(
            floatArrayOf(0f, 0.9f, 0.8f, 0.7f, -0.7f, 31f),
            floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        )

        val snapshot = EndXrInputSnapshotStore.snapshotForIpc()
        assertEquals(EndXrControllerSample.EMPTY, snapshot.left)
        assertEquals(EndXrControllerSample.EMPTY, snapshot.right)
    }

    @Test
    fun oldSnapshotsBecomeNeutralAfterTheThreeHundredMillisecondLease() {
        EndXrInputSnapshotStore.publish(
            floatArrayOf(1f, 1f, 1f, 0.5f, -0.5f, 1f),
            floatArrayOf(1f, 0.5f, 0.5f, -0.5f, 0.5f, 2f)
        )
        val fresh = EndXrInputSnapshotStore.snapshotForIpc()
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
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 31f)
        )
        val before = EndXrInputSnapshotStore.snapshotForIpc()
        EndXrInputSnapshotStore.clear()
        val after = EndXrInputSnapshotStore.snapshotForIpc()

        assertTrue(after.sequence > before.sequence)
        assertTrue(after.updatedAtElapsedRealtimeNanos >= before.updatedAtElapsedRealtimeNanos)
        assertEquals(EndXrControllerSample.EMPTY, after.left)
        assertEquals(EndXrControllerSample.EMPTY, after.right)
    }
}
