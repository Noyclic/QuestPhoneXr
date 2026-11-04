package com.noyclic.xrclientprobe

import android.content.ContentValues
import android.content.Intent
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
    fun separatePackageRunsOfficialLoaderAndReportsRuntimeCreationResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, ProbeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as ProbeActivity

        try {
            val serviceReport = activity.serviceReportText
            val loaderReport = activity.loaderReportText

            val externalReportFile = File(context.cacheDir, "external-client-probe.txt")
            externalReportFile.writeText(serviceReport)
            publishReport(context, externalReportFile, "external-client-probe.txt")

            val loaderReportFile = File(context.cacheDir, "openxr-loader-client-probe.txt")
            loaderReportFile.writeText(loaderReport)
            publishReport(context, loaderReportFile, "openxr-loader-client-probe.txt")

            assertEquals("com.noyclic.xrclientprobe", context.packageName)
            assertTrue(
                "External package could not discover EndXR's exported runtime service:\n$serviceReport",
                serviceReport.contains("EndXR service visible: YES")
            )
            assertTrue(
                "EndXR runtime library was not visible to the external package:\n$serviceReport",
                serviceReport.contains("Runtime library exists: YES")
            )
            assertTrue(
                "Khronos loader's xrInitializeLoaderKHR entry point was not available:\n$loaderReport",
                loaderReport.contains("xrInitializeLoaderKHR lookup=XR_SUCCESS")
            )
            assertTrue(
                "Khronos loader initialization did not succeed:\n$loaderReport",
                loaderReport.contains("xrInitializeLoaderKHR call=XR_SUCCESS")
            )
            assertTrue(
                "The official loader did not attempt xrCreateInstance:\n$loaderReport",
                loaderReport.contains("xrCreateInstance=")
            )
            assertTrue(
                "The diagnostic did not record whether the runtime was loaded:\n$loaderReport",
                loaderReport.contains("runtime_loaded=")
            )
        } finally {
            activity.finish()
        }
    }

    private fun publishReport(
        context: android.content.Context,
        source: File,
        displayName: String
    ) {
        check(source.isFile && source.length() > 0L)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
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
        ) { "Could not create the OpenXR loader report in Downloads." }
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
