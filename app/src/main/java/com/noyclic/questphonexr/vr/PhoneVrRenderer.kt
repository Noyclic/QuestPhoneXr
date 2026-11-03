package com.noyclic.questphonexr.vr

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * Phone-VR stereo renderer for a smartphone viewer.
 *
 * Each eye is rendered into its own texture and then composited with an adjustable radial
 * lens warp. If framebuffer targets are unavailable, rendering falls back to direct stereo.
 * This remains a 3DoF phone viewer, not a complete OpenXR runtime.
 */
class PhoneVrRenderer : GLSurfaceView.Renderer {
    private val rotationLock = Any()
    private var pendingHeadRotation = identity3()
    private var referenceHeadRotation: FloatArray? = null
    @Volatile private var viewerProfile = ViewerProfile()
    private val headView = FloatArray(16)
    private val eyeTranslation = FloatArray(16)
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private lateinit var cubeBuffer: FloatBuffer
    private lateinit var lensQuadBuffer: FloatBuffer
    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var lensProgram = 0
    private var lensPositionHandle = 0
    private var lensTexCoordHandle = 0
    private var lensTextureHandle = 0
    private var lensDistortionHandle = 0
    private val eyeFramebuffers = IntArray(EYE_COUNT)
    private val eyeTextures = IntArray(EYE_COUNT)
    private val eyeDepthBuffers = IntArray(EYE_COUNT)
    private val eyeWidths = IntArray(EYE_COUNT) { 1 }
    private var lensTargetsReady = false
    private var surfaceWidth = 2
    private var surfaceHeight = 1
    private var startedAtNanos = 0L

    fun updateHeadRotation(rotationMatrix: FloatArray) {
        if (rotationMatrix.size < 9) return
        synchronized(rotationLock) {
            pendingHeadRotation = rotationMatrix.copyOfRange(0, 9)
        }
    }

    fun applyViewerProfile(profile: ViewerProfile) {
        viewerProfile = profile.normalized()
    }

    /** Uses the latest phone orientation as the neutral forward direction. */
    fun recenterHead() {
        synchronized(rotationLock) {
            referenceHeadRotation = pendingHeadRotation.copyOf()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.025f, 0.035f, 0.055f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")

        lensProgram = createProgram(LENS_VERTEX_SHADER, LENS_FRAGMENT_SHADER)
        lensPositionHandle = GLES20.glGetAttribLocation(lensProgram, "aPosition")
        lensTexCoordHandle = GLES20.glGetAttribLocation(lensProgram, "aTexCoord")
        lensTextureHandle = GLES20.glGetUniformLocation(lensProgram, "uTexture")
        lensDistortionHandle = GLES20.glGetUniformLocation(lensProgram, "uDistortion")

        cubeBuffer = createCubeBuffer()
        lensQuadBuffer = createLensQuadBuffer()
        // A recreated EGL context invalidates all previous GL object names.
        eyeFramebuffers.fill(0)
        eyeTextures.fill(0)
        eyeDepthBuffers.fill(0)
        lensTargetsReady = false
        startedAtNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(2, width)
        surfaceHeight = max(1, height)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        createEyeTargets()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (program == 0 || !::cubeBuffer.isInitialized) return

        val rotations = synchronized(rotationLock) {
            pendingHeadRotation.copyOf() to referenceHeadRotation?.copyOf()
        }
        val relativeRotation = PhoneVrMath.relativeRotation(rotations.first, rotations.second)
        buildHeadView(relativeRotation)

        val profile = viewerProfile
        val elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000f
        val cubeAngle = (elapsedSeconds * 22f) % 360f
        val distortion = PhoneVrMath.lensDistortionStrength(profile.lensDistortionPercent)

        if (lensTargetsReady && lensProgram != 0 && ::lensQuadBuffer.isInitialized) {
            drawOffscreenEyes(profile, cubeAngle)
            compositeEyes(distortion)
        } else {
            drawDirectEyes(profile, cubeAngle)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawOffscreenEyes(profile: ViewerProfile, cubeAngle: Float) {
        for (eye in 0 until EYE_COUNT) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, eyeFramebuffers[eye])
            GLES20.glViewport(0, 0, eyeWidths[eye], surfaceHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            prepareEyeView(eye, profile)
            drawScene(eyeWidths[eye].toFloat() / surfaceHeight.toFloat(), profile, cubeAngle)
        }
    }

    private fun compositeEyes(distortion: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        for (eye in 0 until EYE_COUNT) {
            val viewportX = if (eye == 0) 0 else eyeWidths[0]
            GLES20.glViewport(viewportX, 0, eyeWidths[eye], surfaceHeight)
            GLES20.glUseProgram(lensProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, eyeTextures[eye])
            GLES20.glUniform1i(lensTextureHandle, 0)
            GLES20.glUniform1f(lensDistortionHandle, distortion)

            lensQuadBuffer.position(0)
            GLES20.glEnableVertexAttribArray(lensPositionHandle)
            GLES20.glVertexAttribPointer(
                lensPositionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                LENS_FLOATS_PER_VERTEX * BYTES_PER_FLOAT,
                lensQuadBuffer
            )
            lensQuadBuffer.position(2)
            GLES20.glEnableVertexAttribArray(lensTexCoordHandle)
            GLES20.glVertexAttribPointer(
                lensTexCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                LENS_FLOATS_PER_VERTEX * BYTES_PER_FLOAT,
                lensQuadBuffer
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, LENS_VERTEX_COUNT)
            GLES20.glDisableVertexAttribArray(lensPositionHandle)
            GLES20.glDisableVertexAttribArray(lensTexCoordHandle)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawDirectEyes(profile: ViewerProfile, cubeAngle: Float) {
        for (eye in 0 until EYE_COUNT) {
            val viewportX = if (eye == 0) 0 else eyeWidths[0]
            GLES20.glViewport(viewportX, 0, eyeWidths[eye], surfaceHeight)
            prepareEyeView(eye, profile)
            drawScene(eyeWidths[eye].toFloat() / surfaceHeight.toFloat(), profile, cubeAngle)
        }
    }

    private fun prepareEyeView(eye: Int, profile: ViewerProfile) {
        val renderedEye = if (profile.swapEyes) 1 - eye else eye
        val halfIpdMeters = profile.ipdMillimeters / 2000f
        val eyeOffset = if (renderedEye == 0) -halfIpdMeters else halfIpdMeters
        Matrix.setIdentityM(eyeTranslation, 0)
        Matrix.translateM(eyeTranslation, 0, -eyeOffset, 0f, 0f)
        Matrix.multiplyMM(view, 0, eyeTranslation, 0, headView, 0)
    }

    private fun drawScene(aspect: Float, profile: ViewerProfile, cubeAngle: Float) {
        Matrix.perspectiveM(
            projection,
            0,
            profile.fieldOfViewDegrees.toFloat(),
            aspect,
            0.05f,
            80f
        )
        drawCube(0f, 0f, -3.0f, 0.72f, 0f, cubeAngle)
        drawCube(-1.15f, -0.30f, -4.2f, 0.34f, 0f, -cubeAngle * 1.4f)
        drawCube(1.12f, 0.25f, -4.8f, 0.48f, cubeAngle * 0.5f, cubeAngle * 0.7f)
        drawCube(0f, -1.05f, -5.2f, 0.52f, 0f, 0f)
    }

    private fun createEyeTargets() {
        releaseEyeTargets()
        eyeWidths[0] = max(1, surfaceWidth / 2)
        eyeWidths[1] = max(1, surfaceWidth - eyeWidths[0])

        GLES20.glGenFramebuffers(EYE_COUNT, eyeFramebuffers, 0)
        GLES20.glGenTextures(EYE_COUNT, eyeTextures, 0)
        GLES20.glGenRenderbuffers(EYE_COUNT, eyeDepthBuffers, 0)

        for (eye in 0 until EYE_COUNT) {
            if (eyeFramebuffers[eye] == 0 || eyeTextures[eye] == 0 || eyeDepthBuffers[eye] == 0) {
                releaseEyeTargets()
                return
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, eyeTextures[eye])
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                eyeWidths[eye],
                surfaceHeight,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )

            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, eyeDepthBuffers[eye])
            GLES20.glRenderbufferStorage(
                GLES20.GL_RENDERBUFFER,
                GLES20.GL_DEPTH_COMPONENT16,
                eyeWidths[eye],
                surfaceHeight
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, eyeFramebuffers[eye])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                eyeTextures[eye],
                0
            )
            GLES20.glFramebufferRenderbuffer(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER,
                eyeDepthBuffers[eye]
            )

            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) !=
                GLES20.GL_FRAMEBUFFER_COMPLETE
            ) {
                releaseEyeTargets()
                return
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
        lensTargetsReady = true
    }

    private fun releaseEyeTargets() {
        if (eyeFramebuffers.any { it != 0 }) {
            GLES20.glDeleteFramebuffers(EYE_COUNT, eyeFramebuffers, 0)
        }
        if (eyeTextures.any { it != 0 }) {
            GLES20.glDeleteTextures(EYE_COUNT, eyeTextures, 0)
        }
        if (eyeDepthBuffers.any { it != 0 }) {
            GLES20.glDeleteRenderbuffers(EYE_COUNT, eyeDepthBuffers, 0)
        }
        eyeFramebuffers.fill(0)
        eyeTextures.fill(0)
        eyeDepthBuffers.fill(0)
        lensTargetsReady = false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun buildHeadView(rotation: FloatArray) {
        Matrix.setIdentityM(headView, 0)
        // SensorManager returns a row-major 3x3 device-to-world matrix.
        // This stores the orientation in the OpenGL 4x4 view matrix.
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

    private fun drawCube(
        x: Float,
        y: Float,
        z: Float,
        scale: Float,
        rotationX: Float,
        rotationY: Float
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.rotateM(model, 0, rotationY, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, rotationX, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, scale, scale, scale)

        Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)

        cubeBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle, 3, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX * BYTES_PER_FLOAT, cubeBuffer
        )
        cubeBuffer.position(3)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(
            colorHandle, 3, GLES20.GL_FLOAT, false, FLOATS_PER_VERTEX * BYTES_PER_FLOAT, cubeBuffer
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CUBE_VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        cubeBuffer.position(0)
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
            error("OpenGL ES link error: " + log)
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
            error("OpenGL ES shader error: " + log)
        }
        return shader
    }

    private fun createCubeBuffer(): FloatBuffer {
        val faces = listOf(
            Face(
                arrayOf(
                    point(-0.5f, -0.5f, 0.5f), point(0.5f, -0.5f, 0.5f),
                    point(0.5f, 0.5f, 0.5f), point(-0.5f, 0.5f, 0.5f)
                ), color(0.22f, 0.75f, 0.95f)
            ),
            Face(
                arrayOf(
                    point(0.5f, -0.5f, -0.5f), point(-0.5f, -0.5f, -0.5f),
                    point(-0.5f, 0.5f, -0.5f), point(0.5f, 0.5f, -0.5f)
                ), color(0.18f, 0.54f, 0.82f)
            ),
            Face(
                arrayOf(
                    point(-0.5f, 0.5f, 0.5f), point(0.5f, 0.5f, 0.5f),
                    point(0.5f, 0.5f, -0.5f), point(-0.5f, 0.5f, -0.5f)
                ), color(0.36f, 0.82f, 0.58f)
            ),
            Face(
                arrayOf(
                    point(-0.5f, -0.5f, -0.5f), point(0.5f, -0.5f, -0.5f),
                    point(0.5f, -0.5f, 0.5f), point(-0.5f, -0.5f, 0.5f)
                ), color(0.15f, 0.48f, 0.37f)
            ),
            Face(
                arrayOf(
                    point(-0.5f, -0.5f, -0.5f), point(-0.5f, -0.5f, 0.5f),
                    point(-0.5f, 0.5f, 0.5f), point(-0.5f, 0.5f, -0.5f)
                ), color(0.82f, 0.58f, 0.24f)
            ),
            Face(
                arrayOf(
                    point(0.5f, -0.5f, 0.5f), point(0.5f, -0.5f, -0.5f),
                    point(0.5f, 0.5f, -0.5f), point(0.5f, 0.5f, 0.5f)
                ), color(0.85f, 0.37f, 0.34f)
            )
        )
        val indices = intArrayOf(0, 1, 2, 0, 2, 3)
        val values = ArrayList<Float>(CUBE_VERTEX_COUNT * FLOATS_PER_VERTEX)
        faces.forEach { face ->
            indices.forEach { index ->
                val vertex = face.points[index]
                values.add(vertex[0])
                values.add(vertex[1])
                values.add(vertex[2])
                values.add(face.color[0])
                values.add(face.color[1])
                values.add(face.color[2])
            }
        }
        return ByteBuffer.allocateDirect(values.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                values.forEach { put(it) }
                position(0)
            }
    }

    private fun createLensQuadBuffer(): FloatBuffer {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )
        return ByteBuffer.allocateDirect(vertices.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }
    }

    private data class Face(val points: Array<FloatArray>, val color: FloatArray)

    private fun point(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private fun color(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    private fun identity3() = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    companion object {
        private const val BYTES_PER_FLOAT = 4
        private const val FLOATS_PER_VERTEX = 6
        private const val CUBE_VERTEX_COUNT = 36
        private const val EYE_COUNT = 2
        private const val LENS_FLOATS_PER_VERTEX = 4
        private const val LENS_VERTEX_COUNT = 4

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

        private const val LENS_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val LENS_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uDistortion;
            varying vec2 vTexCoord;
            void main() {
                vec2 centered = (vTexCoord - vec2(0.5)) * 2.0;
                float radiusSquared = dot(centered, centered);
                vec2 warped = centered * (1.0 + uDistortion * radiusSquared);
                vec2 uv = warped * 0.5 + vec2(0.5);
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexture, uv);
                }
            }
        """
    }
}
