package com.noyclic.questphonexr.tracking

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * On-demand, integrity-pinned storage for the RTMPose-Hand LiteRT model.
 *
 * Download is deliberately explicit and synchronous: call [download] from a worker thread
 * after the user or app flow requests installation. The model is never fetched during build,
 * app startup, or ordinary MediaPipe tracking.
 */
object RtmposeModelStore {
    const val MODEL_FILE_NAME = "rtmpose-hand-fp16-v1.tflite"
    const val EXPECTED_SIZE_BYTES = 27_827_248L
    const val EXPECTED_SHA256 =
        "8b48a941de7b6f5ac61b43bb68b18f253bc651e0bcbc89a6d11bb5a5f9288160"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/RTMPose-Hand-LiteRT/resolve/main/rtmhand_fp16.tflite"

    fun installedFile(context: Context): File =
        File(File(context.noBackupFilesDir, MODEL_DIRECTORY), MODEL_FILE_NAME)

    fun isInstalled(context: Context): Boolean = verify(installedFile(context))

    /**
     * Downloads to a temporary file, verifies both byte length and SHA-256, then atomically
     * replaces the installed model. Existing verified models remain usable if a download fails.
     */
    fun download(
        context: Context,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        val destination = installedFile(context)
        if (verify(destination)) return destination

        val parent = destination.parentFile
            ?: throw IllegalStateException("RTMPose model directory is unavailable.")
        check(parent.exists() || parent.mkdirs()) {
            "Could not create the private RTMPose model directory."
        }

        val temporary = File(parent, "$MODEL_FILE_NAME.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "QuestPhoneXR-EndXR/0.1")
        }

        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "RTMPose download failed with HTTP ${connection.responseCode}."
            }
            val advertisedLength = connection.contentLengthLong
            check(advertisedLength == -1L || advertisedLength == EXPECTED_SIZE_BYTES) {
                "RTMPose download has an unexpected Content-Length."
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary, false).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("RTMPose model download was interrupted.")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(downloaded <= EXPECTED_SIZE_BYTES) {
                            "RTMPose download exceeds its pinned file size."
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(downloaded, EXPECTED_SIZE_BYTES)
                    }
                    output.fd.sync()
                }
            }

            check(downloaded == EXPECTED_SIZE_BYTES) {
                "RTMPose download is incomplete: $downloaded bytes."
            }
            check(digest.digest().toHex() == EXPECTED_SHA256) {
                "RTMPose SHA-256 verification failed; downloaded file was discarded."
            }

            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            check(verify(destination)) { "RTMPose model failed its post-install verification." }
            return destination
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    fun verify(file: File): Boolean =
        verify(file, EXPECTED_SIZE_BYTES, EXPECTED_SHA256)

    internal fun verify(file: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex().equals(expectedSha256, ignoreCase = true)
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private const val MODEL_DIRECTORY = "endxr-models"
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 180_000
}
