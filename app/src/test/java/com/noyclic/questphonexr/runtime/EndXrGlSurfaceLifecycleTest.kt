package com.noyclic.questphonexr.runtime

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrGlSurfaceLifecycleTest {
    @Test
    fun surfaceIsNotReadyUntilItHasNonZeroDimensions() {
        val lifecycle = EndXrGlSurfaceLifecycle()

        lifecycle.onGlSurfaceCreated()
        assertEquals(EndXrGlSurfaceLifecycle.State.WAITING_FOR_SIZE, lifecycle.state)

        lifecycle.onGlSurfaceChanged(0, 720)
        assertEquals(EndXrGlSurfaceLifecycle.State.WAITING_FOR_SIZE, lifecycle.state)

        lifecycle.onGlSurfaceChanged(1280, 720)
        assertEquals(EndXrGlSurfaceLifecycle.State.READY, lifecycle.state)
        assertEquals(1280, lifecycle.surfaceWidth)
        assertEquals(720, lifecycle.surfaceHeight)
    }

    @Test
    fun pauseAndResumePreserveTheCurrentSurfaceGeneration() {
        val lifecycle = EndXrGlSurfaceLifecycle()
        lifecycle.onGlSurfaceCreated()
        lifecycle.onGlSurfaceChanged(1280, 720)
        val generation = lifecycle.surfaceGeneration

        lifecycle.onActivityPaused()
        assertEquals(EndXrGlSurfaceLifecycle.State.PAUSED, lifecycle.state)

        lifecycle.onActivityResumed()
        assertEquals(EndXrGlSurfaceLifecycle.State.READY, lifecycle.state)
        assertEquals(generation, lifecycle.surfaceGeneration)
    }

    @Test
    fun surfaceRecreationIncrementsItsGeneration() {
        val lifecycle = EndXrGlSurfaceLifecycle()

        lifecycle.onGlSurfaceCreated()
        lifecycle.onGlSurfaceChanged(1280, 720)
        lifecycle.onGlSurfaceDestroyed()
        assertEquals(
            EndXrGlSurfaceLifecycle.State.WAITING_FOR_SURFACE,
            lifecycle.state
        )

        lifecycle.onGlSurfaceCreated()
        assertEquals(2L, lifecycle.surfaceGeneration)
        assertEquals(EndXrGlSurfaceLifecycle.State.WAITING_FOR_SIZE, lifecycle.state)
    }

    @Test
    fun destroyedLifecycleCannotBecomeReadyAgain() {
        val lifecycle = EndXrGlSurfaceLifecycle()
        lifecycle.onGlSurfaceCreated()
        lifecycle.onGlSurfaceChanged(1280, 720)
        lifecycle.destroy()

        lifecycle.onGlSurfaceChanged(1920, 1080)

        assertEquals(EndXrGlSurfaceLifecycle.State.DESTROYED, lifecycle.state)
        assertEquals(0, lifecycle.surfaceWidth)
        assertEquals(0, lifecycle.surfaceHeight)
    }

    @Test
    fun lifecycleRejectsCallsFromAnotherThread() {
        val lifecycle = EndXrGlSurfaceLifecycle()
        lifecycle.onGlSurfaceCreated()
        val failure = AtomicReference<Throwable?>()

        val otherThread = Thread {
            try {
                lifecycle.onGlFrame()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        otherThread.start()
        otherThread.join()

        assertTrue(failure.get() is IllegalStateException)
    }
}
