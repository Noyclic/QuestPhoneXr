package com.noyclic.questphonexr.performance

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLES30

data class OpenGlesEglProbeReport(
    val initialized: Boolean,
    val contextCreated: Boolean,
    val eglVersion: String,
    val glesVersion: String,
    val vendor: String,
    val renderer: String,
    val clientVersion: Int,
    val relevantExtensions: List<String>,
    val message: String
) {
    fun toDisplayText(): String = buildString {
        append("EGL: ").append(if (initialized) "инициализирован" else "недоступен")
        if (eglVersion.isNotBlank()) append(" · ").append(eglVersion)
        append("\nGLES context: ").append(if (contextCreated) "создан" else "не создан")
        if (clientVersion > 0) append(" · OpenGL ES ").append(clientVersion)
        if (glesVersion.isNotBlank()) append("\n").append(glesVersion)
        if (vendor.isNotBlank()) append("\nVendor: ").append(vendor)
        if (renderer.isNotBlank()) append("\nRenderer: ").append(renderer)
        append("\n").append(message)
        append("\nВажные GL extensions:")
        if (relevantExtensions.isEmpty()) {
            append("\n• memory-object / semaphore extensions не объявлены")
        } else {
            relevantExtensions.forEach { append("\n• ").append(it) }
        }
        append("\nЭто лёгкая проверка 1×1 pbuffer-контекста, не XR compositor и не тест производительности.")
    }
}

object OpenGlesEglProbe {
    private val relevantNames = listOf(
        "GL_EXT_memory_object", "GL_EXT_memory_object_fd",
        "GL_EXT_semaphore", "GL_EXT_semaphore_fd",
        "GL_EXT_external_buffer", "GL_ANDROID_extension_pack_es31a"
    )

    /** Call from a worker thread. Does not initialize OpenXR or connect to Monado. */
    fun inspect(): OpenGlesEglProbeReport {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return failed("eglGetDisplay вернул EGL_NO_DISPLAY.")

        var initialized = false
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var eglVersion = ""
        var glesVersion = ""
        var vendor = ""
        var renderer = ""
        var clientVersion = 0
        var extensions = emptyList<String>()

        try {
            val major = IntArray(1)
            val minor = IntArray(1)
            if (!EGL14.eglInitialize(display, major, 0, minor, 0)) {
                return failed("eglInitialize: " + eglError())
            }
            initialized = true
            eglVersion = major[0].toString() + "." + minor[0]

            if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                return failed("eglBindAPI: " + eglError(), true, eglVersion)
            }

            var config = chooseConfig(display, 3)
            clientVersion = if (config != null) 3 else 2
            if (config == null) config = chooseConfig(display, 2)
            if (config == null) {
                return failed("Не найден EGL PBUFFER config для GLES 3 или GLES 2: " + eglError(), true, eglVersion)
            }

            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                return failed("eglCreateContext: " + eglError(), true, eglVersion)
            }

            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) {
                return failed("eglCreatePbufferSurface: " + eglError(), true, eglVersion)
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return failed("eglMakeCurrent: " + eglError(), true, eglVersion)
            }

            glesVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
            renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()

            val found = linkedSetOf<String>()
            if (clientVersion >= 3) {
                val count = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
                for (index in 0 until count[0].coerceAtMost(4096)) {
                    val name = GLES30.glGetStringi(GLES20.GL_EXTENSIONS, index).orEmpty()
                    if (relevantNames.contains(name)) found.add(name)
                }
            } else {
                GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
                    .split(' ')
                    .filter { relevantNames.contains(it) }
                    .forEach(found::add)
            }
            extensions = found.toList()

            return OpenGlesEglProbeReport(
                true, true, eglVersion, glesVersion, vendor, renderer,
                clientVersion, extensions,
                "Временный GLES context создан и проверен; он не связан с Surface песочницы."
            )
        } catch (error: Throwable) {
            return OpenGlesEglProbeReport(
                initialized, context != EGL14.EGL_NO_CONTEXT, eglVersion,
                glesVersion, vendor, renderer, clientVersion, extensions,
                "Ошибка EGL/GLES probe: " + (error.message ?: error.javaClass.simpleName)
            )
        } finally {
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (surface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(display, surface) }
            if (context != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(display, context) }
            if (initialized) runCatching { EGL14.eglTerminate(display) }
        }
    }

    private fun chooseConfig(display: EGLDisplay, version: Int): EGLConfig? {
        val renderable = if (version >= 3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, renderable, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    private fun eglError(): String = "EGL error 0x" + EGL14.eglGetError().toString(16)

    private fun failed(message: String, initialized: Boolean = false, eglVersion: String = "") =
        OpenGlesEglProbeReport(
            initialized, false, eglVersion, "", "", "", 0, emptyList(), message
        )
}
