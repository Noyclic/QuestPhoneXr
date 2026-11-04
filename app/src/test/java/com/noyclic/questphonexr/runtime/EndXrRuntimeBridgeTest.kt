package com.noyclic.questphonexr.runtime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
class EndXrRuntimeBridgeTest {
    @Test fun nativeSnapshotValuesAreMappedToNamedFields() {
        val snapshot = parseEndXrRuntimeSnapshot(
            longArrayOf(3, 960, 1080, 30, 300, 245, 12, 8, 16_000_000)
        )
        assertNotNull(snapshot)
        assertEquals(EndXrRuntimeState.RUNNING, snapshot!!.state)
        assertEquals(960, snapshot.eyeWidth)
        assertEquals(1080, snapshot.eyeHeight)
        assertEquals(30, snapshot.targetFps)
        assertEquals(300L, snapshot.framesStarted)
        assertEquals(245L, snapshot.submittedFrames)
        assertEquals(12L, snapshot.droppedFrames)
        assertEquals(8L, snapshot.lateFrames)
        assertEquals(16_000_000L, snapshot.lastLatenessNanos)
        assertEquals(245L, snapshot.completedPhoneFrames)
    }
    @Test fun unknownStateCodeFallsBackToCold() {
        val snapshot = parseEndXrRuntimeSnapshot(longArrayOf(999, 640, 720, 30, 0, 0, 0, 0, 0))
        assertEquals(EndXrRuntimeState.COLD, snapshot!!.state)
    }
    @Test fun incompleteSnapshotIsRejected() {
        assertNull(parseEndXrRuntimeSnapshot(longArrayOf(3, 960, 1080, 30, 1, 1, 0, 0)))
        assertNull(parseEndXrRuntimeSnapshot(null))
    }
}
