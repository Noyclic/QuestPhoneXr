package com.noyclic.questphonexr.sandbox

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.noyclic.questphonexr.tracking.HandSide
import com.noyclic.questphonexr.vr.PhoneVrMath
import com.noyclic.questphonexr.vr.ViewerProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sin

/** Stereo OpenGL ES renderer for the interactive phone-VR sandbox. */
class SandboxRenderer : GLSurfaceView.Renderer {
    private val rotationLock = Any()
    private var pendingHeadRotation = identity3()
    private var referenceHeadRotation: FloatArray? = null
    @Volatile private var viewerProfile = ViewerProfile()
    @Volatile private var scene = emptyScene()

    private val headView = FloatArray(16)
    private val eyeTranslation = FloatArray(16)
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private lateinit var cubeMesh: Mesh
    private lateinit var ballMesh: Mesh
    private lateinit var leftCursorMesh: Mesh
    private lateinit var rightCursorMesh: Mesh
    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var surfaceWidth = 1
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

    fun updateScene(snapshot: SandboxSceneSnapshot) {
        scene = snapshot
    }

    fun recenterHead() {
        synchronized(rotationLock) {
            referenceHeadRotation = pendingHeadRotation.copyOf()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.018f, 0.025f, 0.04f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        cubeMesh = createCubeMesh()
        ballMesh = createSphereMesh(0.95f, 0.42f, 0.18f)
        leftCursorMesh = createSphereMesh(0.16f, 0.88f, 0.62f)
        rightCursorMesh = createSphereMesh(0.95f, 0.58f, 0.25f)
        startedAtNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(2, width)
        surfaceHeight = max(1, height)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
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

            drawMesh(cubeMesh, 0f, -1.27f, -3.6f, 3.2f, 0.08f, 2.5f)
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
                        0.19f
                    )
                    SandboxObjectType.CUBE -> drawMesh(
                        cubeMesh,
                        item.position.x,
                        item.position.y + bob,
                        item.position.z,
                        0.25f,
                        0.25f,
                        0.25f
                    )
                    SandboxObjectType.TARGET -> drawMesh(
                        cubeMesh,
                        item.position.x,
                        item.position.y + bob,
                        item.position.z,
                        0.28f,
                        0.08f,
                        0.28f
                    )
                }
            }

            val leftCursor = snapshot.leftHand.cursor
            val rightCursor = snapshot.rightHand.cursor
            drawMesh(
                leftCursorMesh,
                leftCursor.x,
                leftCursor.y,
                leftCursor.z,
                if (snapshot.leftHand.grabbing) 0.13f else 0.09f,
                if (snapshot.leftHand.grabbing) 0.13f else 0.09f,
                if (snapshot.leftHand.grabbing) 0.13f else 0.09f
            )
            drawMesh(
                rightCursorMesh,
                rightCursor.x,
                rightCursor.y,
                rightCursor.z,
                if (snapshot.rightHand.grabbing) 0.13f else 0.09f,
                if (snapshot.rightHand.grabbing) 0.13f else 0.09f,
                if (snapshot.rightHand.grabbing) 0.13f else 0.09f
            )
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
        scaleZ: Float
    ) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, z)
        Matrix.scaleM(model, 0, scaleX, scaleY, scaleZ)
        Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
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

    private fun createSphereMesh(r: Float, g: Float, b: Float): Mesh {
        val values = ArrayList<Float>()
        val latitudeSegments = 12
        val longitudeSegments = 18
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
        private const val CUBE_VERTEX_COUNT = 36

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
    }
}
