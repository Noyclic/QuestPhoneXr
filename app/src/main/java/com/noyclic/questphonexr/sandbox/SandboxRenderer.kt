package com.noyclic.questphonexr.sandbox

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.vr.PhoneVrMath
import com.noyclic.questphonexr.vr.ViewerProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Stereo OpenGL ES renderer for the interactive phone-VR sandbox. */
class SandboxRenderer : GLSurfaceView.Renderer {
    private val rotationLock = Any()
    private var pendingHeadRotation = identity3()
    private var referenceHeadRotation: FloatArray? = null
    @Volatile private var viewerProfile = ViewerProfile()
    @Volatile private var scene = emptyScene()

    private val headView = FloatArray(16)
    private val eyeTranslation = FloatArray(16)
    private val playerTranslation = FloatArray(16)
    private val translatedView = FloatArray(16)
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private lateinit var cubeMesh: Mesh
    private lateinit var ballMesh: Mesh
    private lateinit var leftCursorMesh: Mesh
    private lateinit var rightCursorMesh: Mesh
    private lateinit var leftHandMesh: Mesh
    private lateinit var rightHandMesh: Mesh
    private lateinit var pinchAccentMesh: Mesh
    /** Reused interleaved world-space vertex buffer for both hands and both stereo eyes. */
    private lateinit var handBatchBuffer: FloatBuffer
    private var handBatchVertexCount = 0
    // Per-hand render-only pivot. This never feeds back into tracking or interaction.
    private var handVisualCenterX = 0f
    private var handVisualCenterY = 0f
    private var handVisualCenterZ = 0f
    private var program = 0
    private var positionHandle = 0
    private var browserTextureProgram = 0
    private var browserTexturePositionHandle = 0
    private var browserTextureUvHandle = 0
    private var browserTextureMvpHandle = 0
    private var browserTextureSamplerHandle = 0
    private var browserTextureId = 0
    private lateinit var browserQuadBuffer: FloatBuffer
    private val browserBitmapLock = Any()
    private var pendingBrowserBitmap: Bitmap? = null
    @Volatile private var browserVisible = false
    private var colorHandle = 0
    private var mvpHandle = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var startedAtNanos = 0L
    private val frameRateCounter = FrameRateCounter()
    @Volatile private var measuredFramesPerSecond = 0f
    @Volatile var lastFrameCompleted: Boolean = false
        private set

    val measuredFps: Float
        get() = measuredFramesPerSecond

    fun updateHeadRotation(rotationMatrix: FloatArray) {
        if (rotationMatrix.size < 9) return
        synchronized(rotationLock) {
            pendingHeadRotation = rotationMatrix.copyOfRange(0, 9)
        }
    }

    fun applyViewerProfile(profile: ViewerProfile) {
        viewerProfile = profile.normalized()
    }

    fun updateScene(snapshot: SandboxSceneSnapshot) {
        scene = snapshot
    }

    fun setBrowserVisible(visible: Boolean) {
        browserVisible = visible
    }

    /** Thread-safe handoff; GLES uploads the newest browser frame on the render thread. */
    fun submitBrowserBitmap(bitmap: Bitmap) {
        synchronized(browserBitmapLock) {
            pendingBrowserBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            pendingBrowserBitmap = bitmap
        }
    }

    fun recenterHead() {
        synchronized(rotationLock) {
            referenceHeadRotation = pendingHeadRotation.copyOf()
        }
    }

    /** Returns the current sensor rotation relative to the recentered world orientation. */
    fun cameraToWorldRotation(): FloatArray = synchronized(rotationLock) {
        PhoneVrMath.relativeRotation(pendingHeadRotation, referenceHeadRotation)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.018f, 0.025f, 0.04f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        browserTextureProgram = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        browserTexturePositionHandle = GLES20.glGetAttribLocation(browserTextureProgram, "aPosition")
        browserTextureUvHandle = GLES20.glGetAttribLocation(browserTextureProgram, "aTexCoord")
        browserTextureMvpHandle = GLES20.glGetUniformLocation(browserTextureProgram, "uMvp")
        browserTextureSamplerHandle = GLES20.glGetUniformLocation(browserTextureProgram, "uTexture")
        browserQuadBuffer = createBrowserQuadBuffer()
        val textureNames = IntArray(1)
        GLES20.glGenTextures(1, textureNames, 0)
        browserTextureId = textureNames[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        cubeMesh = createCubeMesh()
        ballMesh = createSphereMesh(0.95f, 0.42f, 0.18f)
        leftCursorMesh = createSphereMesh(0.16f, 0.88f, 0.62f)
        rightCursorMesh = createSphereMesh(0.95f, 0.58f, 0.25f)
        // Hand primitives use a compact sphere tessellation; batching them into one draw
        // call is cheaper than issuing a GLES call for every bone and knuckle.
        leftHandMesh = createSphereMesh(
            0.12f, 0.76f, 0.72f,
            HAND_SPHERE_LATITUDE_SEGMENTS, HAND_SPHERE_LONGITUDE_SEGMENTS
        )
        rightHandMesh = createSphereMesh(
            1.0f, 0.52f, 0.22f,
            HAND_SPHERE_LATITUDE_SEGMENTS, HAND_SPHERE_LONGITUDE_SEGMENTS
        )
        pinchAccentMesh = createSphereMesh(
            1.0f, 0.88f, 0.38f,
            HAND_SPHERE_LATITUDE_SEGMENTS, HAND_SPHERE_LONGITUDE_SEGMENTS
        )
        val handVertexCapacity = MAX_TRACKED_HANDS * MAX_HAND_PRIMITIVES *
            leftHandMesh.vertexCount * FLOATS_PER_VERTEX
        handBatchBuffer = ByteBuffer.allocateDirect(handVertexCapacity * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        startedAtNanos = System.nanoTime()
        frameRateCounter.reset()
        measuredFramesPerSecond = 0f
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(2, width)
        surfaceHeight = max(1, height)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        lastFrameCompleted = false
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (program == 0 || !::cubeMesh.isInitialized) return

        val rotations = synchronized(rotationLock) {
            pendingHeadRotation.copyOf() to referenceHeadRotation?.copyOf()
        }
        buildHeadView(PhoneVrMath.relativeRotation(rotations.first, rotations.second))

        val profile = viewerProfile
        val snapshot = scene
        val eyeWidth = max(1, surfaceWidth / 2)
        val aspect = eyeWidth.toFloat() / surfaceHeight.toFloat()
        Matrix.perspectiveM(
            projection,
            0,
            profile.fieldOfViewDegrees.toFloat(),
            aspect,
            0.05f,
            80f
        )

        val elapsed = (System.nanoTime() - startedAtNanos) / 1_000_000_000f
        val ambientBob = sin(elapsed * 1.4f) * 0.015f
        // Hand geometry is identical for both eyes; build it once per phone frame.
        buildHandBatch(snapshot)
        uploadPendingBrowserBitmap()

        for (eye in 0..1) {
            val viewportX = eye * eyeWidth
            val viewportWidth = if (eye == 1) surfaceWidth - eyeWidth else eyeWidth
            GLES20.glViewport(viewportX, 0, max(1, viewportWidth), surfaceHeight)

            val renderedEye = if (profile.swapEyes) 1 - eye else eye
            val halfIpdMeters = profile.ipdMillimeters / 2000f
            val eyeOffset = if (renderedEye == 0) -halfIpdMeters else halfIpdMeters
            Matrix.setIdentityM(eyeTranslation, 0)
            Matrix.translateM(eyeTranslation, 0, -eyeOffset, 0f, 0f)
            Matrix.multiplyMM(view, 0, eyeTranslation, 0, headView, 0)
            // Move the camera through the world; hands and objects keep their world positions.
            Matrix.setIdentityM(playerTranslation, 0)
            Matrix.translateM(
                playerTranslation, 0,
                -snapshot.playerPosition.x,
                -snapshot.playerPosition.y,
                -snapshot.playerPosition.z
            )
            Matrix.multiplyMM(translatedView, 0, view, 0, playerTranslation, 0)
            System.arraycopy(translatedView, 0, view, 0, 16)

            // A broad walkable-looking platform replaces the tiny floating slab.
            // Low perimeter rails and distant pylons give the scene a readable horizon
            // without the cost of a skybox or heavy environment meshes.
            drawMesh(cubeMesh, 0f, -1.27f, -4.2f, 12f, 0.12f, 12f)
            drawMesh(cubeMesh, -5.94f, -1.02f, -4.2f, 0.12f, 0.42f, 12f)
            drawMesh(cubeMesh, 5.94f, -1.02f, -4.2f, 0.12f, 0.42f, 12f)
            drawMesh(cubeMesh, 0f, -1.02f, 1.74f, 12f, 0.42f, 0.12f)
            drawMesh(cubeMesh, 0f, -1.02f, -10.14f, 12f, 0.42f, 0.12f)
            // Distant low-poly landmarks make the space feel like an arena, not a void.
            drawMesh(cubeMesh, -4.8f, 0.05f, -9.45f, 0.34f, 2.6f, 0.34f)
            drawMesh(cubeMesh, 0f, 0.05f, -9.45f, 0.34f, 2.6f, 0.34f)
            drawMesh(cubeMesh, 4.8f, 0.05f, -9.45f, 0.34f, 2.6f, 0.34f)
            drawMesh(cubeMesh, -5.35f, -0.12f, -4.2f, 0.28f, 1.15f, 0.28f)
            drawMesh(cubeMesh, 5.35f, -0.12f, -4.2f, 0.28f, 1.15f, 0.28f)
            snapshot.objects.forEach { item ->
                val bob = if (item.heldBy == null && item.type == SandboxObjectType.TARGET) {
                    ambientBob
                } else {
                    0f
                }
                when (item.type) {
                    SandboxObjectType.BALL -> drawMesh(
                        ballMesh,
                        item.position.x,
                        item.position.y + bob,
                        item.position.z,
                        0.19f,
                        0.19f,
                        0.19f,
                        item.rotationZRadians
                    )
                    SandboxObjectType.CUBE -> drawMesh(
                        cubeMesh,
                        item.position.x,
                        item.position.y + bob,
                        item.position.z,
                        0.25f,
                        0.25f,
                        0.25f,
                        item.rotationZRadians
                    )
                    SandboxObjectType.TARGET -> drawMesh(
                        cubeMesh,
                        item.position.x,
                        item.position.y + bob,
                        item.position.z,
                        0.45f,
                        0.08f,
                        0.45f
                    )
                }
            }

            if (browserVisible && browserTextureId != 0) {
                drawBrowserPanel()
                drawBrowserPointer(snapshot.leftHand, leftCursorMesh)
                drawBrowserPointer(snapshot.rightHand, rightCursorMesh)
            }

            val leftHand = snapshot.leftHand
            val rightHand = snapshot.rightHand
            if (!(leftHand.cameraTracked && leftHand.landmarks.size >= HAND_LANDMARK_COUNT)) {
                val cursor = leftHand.cursor
                val size = if (leftHand.grabbing) 0.13f else 0.09f
                drawMesh(leftCursorMesh, cursor.x, cursor.y, cursor.z, size, size, size)
            }
            if (!(rightHand.cameraTracked && rightHand.landmarks.size >= HAND_LANDMARK_COUNT)) {
                val cursor = rightHand.cursor
                val size = if (rightHand.grabbing) 0.13f else 0.09f
                drawMesh(rightCursorMesh, cursor.x, cursor.y, cursor.z, size, size, size)
            }
            drawHandBatch()
        }
        frameRateCounter.onFrame(System.nanoTime())?.let { measured ->
            measuredFramesPerSecond = measured
        }
        lastFrameCompleted = true
    }

    private fun uploadPendingBrowserBitmap() {
        if (browserTextureId == 0) return
        synchronized(browserBitmapLock) {
            val bitmap = pendingBrowserBitmap ?: return
            if (!bitmap.isRecycled) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            }
            pendingBrowserBitmap = null
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Draw the browser as a true world-space quad, once in each stereo eye. */
    private fun drawBrowserPanel() {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model, 0,
            SandboxBrowserInteraction.PANEL_CENTER_X,
            SandboxBrowserInteraction.PANEL_CENTER_Y,
            SandboxBrowserInteraction.PANEL_CENTER_Z
        )
        Matrix.scaleM(
            model, 0,
            SandboxBrowserInteraction.PANEL_WIDTH,
            SandboxBrowserInteraction.PANEL_HEIGHT,
            1f
        )
        Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0)

        GLES20.glUseProgram(browserTextureProgram)
        GLES20.glUniformMatrix4fv(browserTextureMvpHandle, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTextureId)
        GLES20.glUniform1i(browserTextureSamplerHandle, 0)

        browserQuadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(browserTexturePositionHandle)
        GLES20.glVertexAttribPointer(
            browserTexturePositionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            TEXTURED_VERTEX_FLOATS * BYTES_PER_FLOAT,
            browserQuadBuffer
        )
        browserQuadBuffer.position(3)
        GLES20.glEnableVertexAttribArray(browserTextureUvHandle)
        GLES20.glVertexAttribPointer(
            browserTextureUvHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            TEXTURED_VERTEX_FLOATS * BYTES_PER_FLOAT,
            browserQuadBuffer
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, BROWSER_QUAD_VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(browserTexturePositionHandle)
        GLES20.glDisableVertexAttribArray(browserTextureUvHandle)
        browserQuadBuffer.position(0)
    }

    private fun drawBrowserPointer(hand: SandboxHandState, pointerMesh: Mesh) {
        if (!hand.cameraTracked) return
        val pixel = SandboxBrowserInteraction.map(hand.cursor) ?: return
        val position = SandboxBrowserInteraction.toWorld(pixel)
        val size = if (hand.pinching) 0.048f else 0.030f
        drawMesh(pointerMesh, position.x, position.y, position.z, size, size, size)
    }

    private fun createBrowserQuadBuffer(): FloatBuffer {
        val values = floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 1f,
             0.5f, -0.5f, 0f, 1f, 1f,
            -0.5f,  0.5f, 0f, 0f, 0f,
             0.5f,  0.5f, 0f, 1f, 0f
        )
        return ByteBuffer.allocateDirect(values.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }

    private fun buildHeadView(rotation: FloatArray) {
        Matrix.setIdentityM(headView, 0)
        headView[0] = rotation[0]
        headView[1] = rotation[1]
        headView[2] = rotation[2]
        headView[4] = rotation[3]
        headView[5] = rotation[4]
        headView[6] = rotation[5]
        headView[8] = rotation[6]
        headView[9] = rotation[7]
        headView[10] = rotation[8]
    }

    private fun drawMesh(
        mesh: Mesh,
        x: Float,
        y: Float,
        z: Float,
        scaleX: Float,
        scaleY: Float,
        scaleZ: Float,
        rotationZRadians: Float = 0f
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        if (rotationZRadians.isFinite() && kotlin.math.abs(rotationZRadians) > 0.0001f) {
            Matrix.rotateM(model, 0, rotationZRadians * 57.29578f, 0f, 0f, 1f)
        }
        Matrix.scaleM(model, 0, scaleX, scaleY, scaleZ)
        drawTransformedMesh(mesh, model)
    }

    /**
     * Builds one interleaved triangle batch for all visible hands. The old path submitted
     * a draw call for each palm link, finger bone and knuckle sphere, repeated for both eyes.
     * This path expands compact rounded primitives into one reusable CPU buffer per frame;
     * the same finished batch is then drawn once for each stereo eye.
     */
    private fun buildHandBatch(snapshot: SandboxSceneSnapshot) {
        handBatchBuffer.clear()
        val left = snapshot.leftHand
        if (left.cameraTracked && left.landmarks.size >= HAND_LANDMARK_COUNT) {
            appendHandGeometry(left.landmarks, leftHandMesh, left.grabbing)
        }
        val right = snapshot.rightHand
        if (right.cameraTracked && right.landmarks.size >= HAND_LANDMARK_COUNT) {
            appendHandGeometry(right.landmarks, rightHandMesh, right.grabbing)
        }
        handBatchVertexCount = handBatchBuffer.position() / FLOATS_PER_VERTEX
        handBatchBuffer.flip()
    }

    private fun appendHandGeometry(
        points: List<SandboxVector3>,
        handMesh: Mesh,
        isPinching: Boolean
    ) {
        val palmWidth = distance(points[5], points[17]).coerceIn(0.12f, 0.62f)
        val palmLength = distance(points[0], points[9]).coerceIn(0.10f, 0.62f)
        var centerX = 0f
        var centerY = 0f
        var centerZ = 0f
        for (index in PALM_CENTER_INDICES) {
            centerX += points[index].x
            centerY += points[index].y
            centerZ += points[index].z
        }
        centerX /= PALM_CENTER_INDICES.size
        centerY /= PALM_CENTER_INDICES.size
        centerZ /= PALM_CENTER_INDICES.size

        handVisualCenterX = centerX
        handVisualCenterY = centerY
        handVisualCenterZ = centerZ

        // Ellipsoidal palm with a little extra thickness for a more solid dorsal surface.
        appendEllipsoid(
            handMesh,
            centerX, centerY, centerZ,
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            palmWidth * 0.56f,
            palmLength * 0.48f,
            (palmWidth * 0.18f).coerceIn(0.035f, 0.075f)
        )

        val palmLinkRadius = SandboxHandMeshGeometry.palmLinkRadius(palmWidth)
        for (link in SandboxHandMeshGeometry.PALM_LINKS) {
            val start = points[link[0]]
            val end = points[link[1]]
            appendBone(handMesh, start, end, palmLinkRadius)
        }

        // The thumb web is a common visual gap in landmark-driven hands. A short rounded
        // bridge from thumb MCP to index MCP gives the palm a continuous silhouette.
        appendBone(
            handMesh,
            points[SandboxHandMeshGeometry.THUMB_WEB[0]],
            points[SandboxHandMeshGeometry.THUMB_WEB[1]],
            (palmLinkRadius * 0.82f).coerceAtLeast(0.012f)
        )

        val chains = SandboxHandMeshGeometry.FINGER_CHAINS
        for (digitIndex in chains.indices) {
            val chain = chains[digitIndex]
            for (segmentIndex in 0 until chain.size - 1) {
                val radius = SandboxHandMeshGeometry.fingerSegmentRadius(
                    palmWidth, digitIndex, segmentIndex
                )
                appendBone(
                    handMesh,
                    points[chain[segmentIndex]],
                    points[chain[segmentIndex + 1]],
                    radius
                )
            }
        }

        // Every bone is an overlapping rounded capsule, so its ends also form the joint
        // transitions. Separate full-size joint balls were removed to avoid a "beads on
        // a wire" appearance and to reduce the amount of geometry.
        if (isPinching) {
            val accentRadius = (palmWidth * 0.034f).coerceIn(0.014f, 0.024f) * 1.22f
            appendEllipsoid(
                pinchAccentMesh,
                points[4].x, points[4].y, points[4].z,
                1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
                accentRadius, accentRadius, accentRadius
            )
            appendEllipsoid(
                pinchAccentMesh,
                points[8].x, points[8].y, points[8].z,
                1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f,
                accentRadius, accentRadius, accentRadius
            )
        }
    }

    /** Appends a rounded capsule aligned from start to end using one shared sphere mesh. */
    private fun appendBone(
        mesh: Mesh,
        start: SandboxVector3,
        end: SandboxVector3,
        radius: Float
    ) {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (!length.isFinite() || length < MIN_BONE_LENGTH) return

        val directionX = dx / length
        val directionY = dy / length
        val directionZ = dz / length

        // Build an orthonormal frame with local +Y following the bone direction.
        // Choosing a different reference near the Z poles prevents a zero cross product.
        val axisXx: Float
        val axisXy: Float
        val axisXz: Float
        if (abs(directionZ) < 0.90f) {
            val basisLength = sqrt(directionX * directionX + directionY * directionY)
                .coerceAtLeast(MIN_BONE_LENGTH)
            axisXx = -directionY / basisLength
            axisXy = directionX / basisLength
            axisXz = 0f
        } else {
            val basisLength = sqrt(directionY * directionY + directionZ * directionZ)
                .coerceAtLeast(MIN_BONE_LENGTH)
            axisXx = 0f
            axisXy = -directionZ / basisLength
            axisXz = directionY / basisLength
        }
        // Local X × local Y must equal local Z; keep the basis right-handed.
        val axisZx = axisXy * directionZ - axisXz * directionY
        val axisZy = axisXz * directionX - axisXx * directionZ
        val axisZz = axisXx * directionY - axisXy * directionX

        appendEllipsoid(
            mesh,
            (start.x + end.x) * 0.5f,
            (start.y + end.y) * 0.5f,
            (start.z + end.z) * 0.5f,
            axisXx, axisXy, axisXz,
            directionX, directionY, directionZ,
            axisZx, axisZy, axisZz,
            radius,
            length * 0.5f + radius,
            radius
        )
    }

    /**
     * Expands one shaded ellipsoid into the reusable interleaved batch. Vertex normals are
     * approximated from the unit sphere and inverse-scaled before a soft directional light
     * is applied to the vertex color; no new GLES shader attributes or per-bone draw calls.
     */
    private fun appendEllipsoid(
        mesh: Mesh,
        centerX: Float,
        centerY: Float,
        centerZ: Float,
        axisXx: Float, axisXy: Float, axisXz: Float,
        axisYx: Float, axisYy: Float, axisYz: Float,
        axisZx: Float, axisZy: Float, axisZz: Float,
        radiusX: Float,
        radiusY: Float,
        radiusZ: Float
    ) {
        val source = mesh.vertices
        val safeRadiusX = radiusX.coerceAtLeast(MIN_ELLIPSOID_RADIUS)
        val safeRadiusY = radiusY.coerceAtLeast(MIN_ELLIPSOID_RADIUS)
        val safeRadiusZ = radiusZ.coerceAtLeast(MIN_ELLIPSOID_RADIUS)
        for (vertex in 0 until mesh.vertexCount) {
            val offset = vertex * FLOATS_PER_VERTEX
            val localX = source.get(offset)
            val localY = source.get(offset + 1)
            val localZ = source.get(offset + 2)
            val worldX = centerX + axisXx * localX * safeRadiusX +
                axisYx * localY * safeRadiusY + axisZx * localZ * safeRadiusZ
            val worldY = centerY + axisXy * localX * safeRadiusX +
                axisYy * localY * safeRadiusY + axisZy * localZ * safeRadiusZ
            val worldZ = centerZ + axisXz * localX * safeRadiusX +
                axisYz * localY * safeRadiusY + axisZz * localZ * safeRadiusZ

            val normalX = axisXx * (localX / safeRadiusX) +
                axisYx * (localY / safeRadiusY) + axisZx * (localZ / safeRadiusZ)
            val normalY = axisXy * (localX / safeRadiusX) +
                axisYy * (localY / safeRadiusY) + axisZy * (localZ / safeRadiusZ)
            val normalZ = axisXz * (localX / safeRadiusX) +
                axisYz * (localY / safeRadiusY) + axisZz * (localZ / safeRadiusZ)
            val normalLength = sqrt(
                normalX * normalX + normalY * normalY + normalZ * normalZ
            ).coerceAtLeast(MIN_ELLIPSOID_RADIUS)
            val diffuse = (
                normalX * LIGHT_DIRECTION_X +
                    normalY * LIGHT_DIRECTION_Y +
                    normalZ * LIGHT_DIRECTION_Z
                ) / normalLength
            val shade = (AMBIENT_LIGHT + DIFFUSE_LIGHT * diffuse.coerceAtLeast(0f))
                .coerceIn(AMBIENT_LIGHT, 1f)

            // Shrink only the rendered hand mesh around its palm centre. The original
            // MediaPipe landmarks remain untouched for pinch, grabbing and browser touch.
            handBatchBuffer.put(
                SandboxHandMeshGeometry.scaleVisualCoordinate(worldX, handVisualCenterX)
            )
            handBatchBuffer.put(
                SandboxHandMeshGeometry.scaleVisualCoordinate(worldY, handVisualCenterY)
            )
            handBatchBuffer.put(
                SandboxHandMeshGeometry.scaleVisualCoordinate(worldZ, handVisualCenterZ)
            )
            handBatchBuffer.put(source.get(offset + 3) * shade)
            handBatchBuffer.put(source.get(offset + 4) * shade)
            handBatchBuffer.put(source.get(offset + 5) * shade)
        }
    }

    /** One GLES draw call per eye for both complete hands. */
    private fun drawHandBatch() {
        if (handBatchVertexCount <= 0) return
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)

        val batch = handBatchBuffer
        val floatCount = handBatchVertexCount * FLOATS_PER_VERTEX
        batch.position(0)
        batch.limit(floatCount)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle, 3, GLES20.GL_FLOAT, false,
            FLOATS_PER_VERTEX * BYTES_PER_FLOAT, batch
        )
        batch.position(3)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(
            colorHandle, 3, GLES20.GL_FLOAT, false,
            FLOATS_PER_VERTEX * BYTES_PER_FLOAT, batch
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, handBatchVertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        batch.clear()
    }

    private fun drawTransformedMesh(mesh: Mesh, transform: FloatArray) {
        Matrix.multiplyMM(mvp, 0, view, 0, transform, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        mesh.vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            FLOATS_PER_VERTEX * BYTES_PER_FLOAT,
            mesh.vertices
        )
        mesh.vertices.position(3)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(
            colorHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            FLOATS_PER_VERTEX * BYTES_PER_FLOAT,
            mesh.vertices
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        mesh.vertices.position(0)
    }

    private fun distance(a: SandboxVector3, b: SandboxVector3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val linkedProgram = GLES20.glCreateProgram()
        check(linkedProgram != 0) { "Не удалось создать OpenGL ES программу." }
        GLES20.glAttachShader(linkedProgram, vertex)
        GLES20.glAttachShader(linkedProgram, fragment)
        GLES20.glLinkProgram(linkedProgram)
        val status = IntArray(1)
        GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(linkedProgram)
            GLES20.glDeleteProgram(linkedProgram)
            error("OpenGL ES link error: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return linkedProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Не удалось создать OpenGL ES shader." }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("OpenGL ES shader error: $log")
        }
        return shader
    }

    private fun createCubeMesh(): Mesh {
        val faces = listOf(
            Face(
                arrayOf(
                    point(-0.5f, -0.5f, 0.5f), point(0.5f, -0.5f, 0.5f),
                    point(0.5f, 0.5f, 0.5f), point(-0.5f, 0.5f, 0.5f)
                ), color(0.16f, 0.65f, 0.88f)
            ),
            Face(
                arrayOf(
                    point(0.5f, -0.5f, -0.5f), point(-0.5f, -0.5f, -0.5f),
                    point(-0.5f, 0.5f, -0.5f), point(0.5f, 0.5f, -0.5f)
                ), color(0.12f, 0.43f, 0.72f)
            ),
            Face(
                arrayOf(
                    point(-0.5f, 0.5f, 0.5f), point(0.5f, 0.5f, 0.5f),
                    point(0.5f, 0.5f, -0.5f), point(-0.5f, 0.5f, -0.5f)
                ), color(0.27f, 0.78f, 0.55f)
            ),
            Face(
                arrayOf(
                    point(-0.5f, -0.5f, -0.5f), point(0.5f, -0.5f, -0.5f),
                    point(0.5f, -0.5f, 0.5f), point(-0.5f, -0.5f, 0.5f)
                ), color(0.11f, 0.34f, 0.44f)
            ),
            Face(
                arrayOf(
                    point(-0.5f, -0.5f, -0.5f), point(-0.5f, -0.5f, 0.5f),
                    point(-0.5f, 0.5f, 0.5f), point(-0.5f, 0.5f, -0.5f)
                ), color(0.82f, 0.53f, 0.19f)
            ),
            Face(
                arrayOf(
                    point(0.5f, -0.5f, 0.5f), point(0.5f, -0.5f, -0.5f),
                    point(0.5f, 0.5f, -0.5f), point(0.5f, 0.5f, 0.5f)
                ), color(0.84f, 0.31f, 0.28f)
            )
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        val values = ArrayList<Float>(CUBE_VERTEX_COUNT * FLOATS_PER_VERTEX)
        faces.forEach { face ->
            indices.forEach { index ->
                val vertex = face.points[index]
                appendVertex(values, vertex, face.color)
            }
        }
        return mesh(values)
    }

    private fun createSphereMesh(
        r: Float,
        g: Float,
        b: Float,
        latitudeSegments: Int = 12,
        longitudeSegments: Int = 18
    ): Mesh {
        val values = ArrayList<Float>()
        for (latitude in 0 until latitudeSegments) {
            val lat0 = Math.PI * (-0.5 + latitude.toDouble() / latitudeSegments)
            val lat1 = Math.PI * (-0.5 + (latitude + 1).toDouble() / latitudeSegments)
            val y0 = kotlin.math.sin(lat0).toFloat()
            val y1 = kotlin.math.sin(lat1).toFloat()
            val ring0 = kotlin.math.cos(lat0).toFloat()
            val ring1 = kotlin.math.cos(lat1).toFloat()

            for (longitude in 0 until longitudeSegments) {
                val lon0 = 2.0 * Math.PI * longitude.toDouble() / longitudeSegments
                val lon1 = 2.0 * Math.PI * (longitude + 1).toDouble() / longitudeSegments
                val p00 = point(ring0 * kotlin.math.cos(lon0).toFloat(), y0, ring0 * kotlin.math.sin(lon0).toFloat())
                val p10 = point(ring1 * kotlin.math.cos(lon0).toFloat(), y1, ring1 * kotlin.math.sin(lon0).toFloat())
                val p11 = point(ring1 * kotlin.math.cos(lon1).toFloat(), y1, ring1 * kotlin.math.sin(lon1).toFloat())
                val p01 = point(ring0 * kotlin.math.cos(lon1).toFloat(), y0, ring0 * kotlin.math.sin(lon1).toFloat())
                val color = color(r, g, b)
                appendVertex(values, p00, color)
                appendVertex(values, p10, color)
                appendVertex(values, p11, color)
                appendVertex(values, p00, color)
                appendVertex(values, p11, color)
                appendVertex(values, p01, color)
            }
        }
        return mesh(values)
    }

    private fun appendVertex(values: MutableList<Float>, position: FloatArray, color: FloatArray) {
        values.add(position[0])
        values.add(position[1])
        values.add(position[2])
        values.add(color[0])
        values.add(color[1])
        values.add(color[2])
    }

    private fun mesh(values: List<Float>): Mesh {
        val buffer = ByteBuffer.allocateDirect(values.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                values.forEach { put(it) }
                position(0)
            }
        return Mesh(buffer, values.size / FLOATS_PER_VERTEX)
    }

    private data class Mesh(val vertices: FloatBuffer, val vertexCount: Int)
    private data class Face(val points: Array<FloatArray>, val color: FloatArray)

    private fun point(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun color(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    private fun identity3() = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    private fun emptyScene() = SandboxSceneSnapshot(
        objects = emptyList(),
        leftHand = SandboxHandState(HandSide.LEFT, SandboxVector3(-0.65f, -0.1f, -2.35f), false, false),
        rightHand = SandboxHandState(HandSide.RIGHT, SandboxVector3(0.65f, -0.1f, -2.35f), false, false),
        grabbedObjectCount = 0
    )

    companion object {
        private const val BYTES_PER_FLOAT = 4
        private const val FLOATS_PER_VERTEX = 6
        private const val TEXTURED_VERTEX_FLOATS = 5
        private const val BROWSER_QUAD_VERTEX_COUNT = 4
        private const val CUBE_VERTEX_COUNT = 36
        private const val HAND_LANDMARK_COUNT = 21
        private const val MAX_TRACKED_HANDS = 2
        // 1 palm + 5 palm links + 1 thumb web + 15 digit bones + 2 pinch accents.
        private const val MAX_HAND_PRIMITIVES = SandboxHandMeshGeometry.MAX_PRIMITIVES_PER_HAND
        private const val HAND_SPHERE_LATITUDE_SEGMENTS = 8
        private const val HAND_SPHERE_LONGITUDE_SEGMENTS = 12
        private const val MIN_BONE_LENGTH = 0.0001f
        private const val MIN_ELLIPSOID_RADIUS = 0.0001f
        private const val LIGHT_DIRECTION_X = -0.32f
        private const val LIGHT_DIRECTION_Y = 0.76f
        private const val LIGHT_DIRECTION_Z = 0.57f
        private const val AMBIENT_LIGHT = 0.60f
        private const val DIFFUSE_LIGHT = 0.40f
        private val PALM_CENTER_INDICES = intArrayOf(0, 5, 9, 13, 17)

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vColor = aColor;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """

        private const val TEXTURE_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
