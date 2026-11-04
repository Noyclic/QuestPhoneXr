package com.noyclic.xrclientprobe

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExternalRuntimeClientDiscoveryTest {
    @Test
    fun separatePackageCanDiscoverEndXrRuntimeServiceAndNativeLibrary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = ExternalRuntimeClientProbe.inspect(context)
        val text = report.toDisplayText()
        val cachedReport = File(context.cacheDir, "external-client-probe.txt")
        cachedReport.writeText(text)
        publishReport(context, cachedReport)

        assertEquals("com.noyclic.xrclientprobe", report.clientPackage)
        assertTrue(
            "External app process could not discover the EndXR OpenXRRuntimeService:\n$text",
            report.endXrServiceFound
        )
        assertTrue(
            "EndXR runtime service is not exported to a separate package:\n$text",
            report.endXrServiceExported
        )
        assertEquals(1, report.openXrMajorVersion)
        assertEquals("libendxr_openxr.so", report.soFilename)
        assertTrue(
            "External package could not see the runtime library payload:\n$text",
            report.nativeLibraryExists
        )
    }

    private fun publishReport(context: android.content.Context, source: File) {
        check(source.isFile && source.length() > 0L)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "external-client-probe.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/QuestPhoneXR-CI/"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Could not create the external client report in Downloads." }
        try {
            val output = requireNotNull(resolver.openOutputStream(uri, "w"))
            source.inputStream().use { input -> output.use { input.copyTo(it) } }
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            check(resolver.update(uri, ready, null, null) == 1)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
