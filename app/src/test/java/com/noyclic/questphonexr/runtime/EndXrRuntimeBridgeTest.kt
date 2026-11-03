package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class EndXrRuntimeBridgeTest {
    @Test
    fun nativeSnapshotValuesAreMappedToNamedFields() {
        val snapshot = parseEndXrRuntimeSnapshot(longArrayOf(3, 1920, 1080, 30, 245))

        assertNotNull(snapshot)
        assertEquals(EndXrRuntimeState.RUNNING, snapshot!!.state)
        assertEquals(1920, snapshot.width)
        assertEquals(1080, snapshot.height)
        assertEquals(30, snapshot.targetFps)
        assertEquals(245L, snapshot.completedPhoneFrames)
    }

    @Test
    fun unknownStateCodeFallsBackToCold() {
        val snapshot = parseEndXrRuntimeSnapshot(longArrayOf(999, 1280, 720, 30, 0))

        assertEquals(EndXrRuntimeState.COLD, snapshot!!.state)
    }

    @Test
    fun incompleteSnapshotIsRejected() {
        assertNull(parseEndXrRuntimeSnapshot(longArrayOf(3, 1280, 720)))
        assertNull(parseEndXrRuntimeSnapshot(null))
    }
}
