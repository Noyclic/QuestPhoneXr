package com.noyclic.questphonexr.runtime

/**
 * Tracks the GLES surface lifecycle on the GLSurfaceView render thread.
 * This is a lifecycle boundary for a future persistent native OpenXR session,
 * not an OpenXR session implementation by itself.
 */
class OpenXrGlSurfaceLifecycle {
    enum class State {
        WAITING_FOR_SURFACE, WAITING_FOR_SIZE, READY, PAUSED, DESTROYED
    }

    @Volatile var state: State = State.WAITING_FOR_SURFACE
        private set
    @Volatile var surfaceGeneration: Long = 0L
        private set
    @Volatile var surfaceWidth: Int = 0
        private set
    @Volatile var surfaceHeight: Int = 0
        private set

    private var renderThreadId: Long? = null
    private var activityPaused = false
    private var surfaceAvailable = false

    fun onGlSurfaceCreated() {
        checkRenderThread()
        check(state != State.DESTROYED) { "GL surface lifecycle is destroyed." }
        surfaceGeneration += 1L
        surfaceAvailable = true
        surfaceWidth = 0
        surfaceHeight = 0
        state = if (activityPaused) State.PAUSED else State.WAITING_FOR_SIZE
    }

    fun onGlSurfaceChanged(width: Int, height: Int) {
        checkRenderThread()
        if (state == State.DESTROYED) return
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        state = when {
            activityPaused -> State.PAUSED
            surfaceAvailable && width > 0 && height > 0 -> State.READY
            else -> State.WAITING_FOR_SIZE
        }
    }

    fun onActivityPaused() {
        checkRenderThread()
        if (state == State.DESTROYED) return
        activityPaused = true
        state = State.PAUSED
    }

    fun onActivityResumed() {
        checkRenderThread()
        if (state == State.DESTROYED) return
        activityPaused = false
        state = when {
            !surfaceAvailable -> State.WAITING_FOR_SURFACE
            surfaceWidth <= 0 || surfaceHeight <= 0 -> State.WAITING_FOR_SIZE
            else -> State.READY
        }
    }

    fun onGlSurfaceDestroyed() {
        checkRenderThread()
        if (state == State.DESTROYED) return
        surfaceAvailable = false
        surfaceWidth = 0
        surfaceHeight = 0
        state = if (activityPaused) State.PAUSED else State.WAITING_FOR_SURFACE
    }

    fun onGlFrame() {
        checkRenderThread()
        check(state != State.DESTROYED) { "GL frame received after lifecycle destruction." }
    }

    fun destroy() {
        checkRenderThread()
        surfaceAvailable = false
        surfaceWidth = 0
        surfaceHeight = 0
        state = State.DESTROYED
    }

    private fun checkRenderThread() {
        val current = Thread.currentThread().id
        val owner = renderThreadId
        if (owner == null) renderThreadId = current
        else check(owner == current) {
            "OpenXR surface lifecycle must only be accessed from the GLSurfaceView render thread."
        }
    }
}
