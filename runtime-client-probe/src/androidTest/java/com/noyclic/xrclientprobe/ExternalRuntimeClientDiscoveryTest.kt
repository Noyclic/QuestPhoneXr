package com.noyclic.xrclientprobe

import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExternalRuntimeClientDiscoveryTest {
    @Test
    fun inputHealthClassifierDistinguishesLiveHandsFromStalePublisher() {
        assertEquals(
            ExternalInputPublicationState.LIVE_HANDS,
            ExternalInputHealthClassifier.classify(
                controllerStale = false,
                handPoseStale = false,
                headStale = false,
                headActive = true,
                leftControllerActive = false,
                rightControllerActive = false,
                leftHandPoseActive = true,
                rightHandPoseActive = false
            )
        )
        assertEquals(
            ExternalInputPublicationState.STALE,
            ExternalInputHealthClassifier.classify(
                controllerStale = true,
                handPoseStale = true,
                headStale = true,
                headActive = false,
                leftControllerActive = false,
                rightControllerActive = false,
                leftHandPoseActive = false,
                rightHandPoseActive = false
            )
        )
        assertEquals(
            ExternalInputPublicationState.PUBLISHING_NO_HANDS,
            ExternalInputHealthClassifier.classify(
                controllerStale = false,
                handPoseStale = false,
                headStale = false,
                headActive = true,
                leftControllerActive = false,
                rightControllerActive = false,
                leftHandPoseActive = false,
                rightHandPoseActive = false
            )
        )
    }

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
                "External package did not receive the signed EndXR input permission:\n$serviceReport",
                serviceReport.contains("provider_permission=GRANTED")
            )
            assertTrue(
                "External package could not read the EndXR input IPC snapshot:\n$serviceReport",
                serviceReport.contains("provider_query=PASS")
            )
            assertTrue(
                "External probe did not report input producer freshness:\n$serviceReport",
                serviceReport.contains("input_publication_state=") &&
                    serviceReport.contains("controller_age_ms=") &&
                    serviceReport.contains("head_age_ms=") &&
                    serviceReport.contains("hand_pose_age_ms=")
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
                "The external probe must report whether XR_EXT_hand_tracking is advertised:\n$loaderReport",
                loaderReport.contains("runtime_advertises_XR_EXT_hand_tracking=")
            )
            assertTrue(
                "The external probe must report the Khronos header API version:\n$loaderReport",
                loaderReport.contains("loader_header_api_version=1.1.65")
            )
            assertTrue(
                "The external probe must request EndXR's supported OpenXR 1.0.0 API:\n$loaderReport",
                loaderReport.contains("application_requested_api_version=1.0.0")
            )
            assertTrue(
                "The official loader did not attempt xrCreateInstance:\n$loaderReport",
                loaderReport.contains("xrCreateInstance=")
            )
            assertTrue(
                "The diagnostic did not record whether the runtime was loaded:\n$loaderReport",
                loaderReport.contains("runtime_loaded=")
            )

            // CI devices without an active RuntimeBroker stop before instance creation.
            // When a runtime is selected, exercise the extension from this separate APK.
            if (loaderReport.contains("runtime_instance_created=YES") &&
                loaderReport.contains("runtime_advertises_XR_EXT_hand_tracking=YES")
            ) {
                for (functionName in listOf(
                    "xrCreateHandTrackerEXT",
                    "xrDestroyHandTrackerEXT",
                    "xrLocateHandJointsEXT"
                )) {
                    assertTrue(
                        "External hand-tracking function lookup failed ($functionName):\n$loaderReport",
                        loaderReport.contains("external_" + functionName + "_lookup=XR_SUCCESS")
                    )
                }

                if (loaderReport.contains("external_xrCreateSession=XR_SUCCESS")) {
                    assertTrue(
                        "External left hand tracker creation failed:\n$loaderReport",
                        loaderReport.contains("external_xrCreateHandTrackerEXT_left=XR_SUCCESS")
                    )
                    assertTrue(
                        "External right hand tracker creation failed:\n$loaderReport",
                        loaderReport.contains("external_xrCreateHandTrackerEXT_right=XR_SUCCESS")
                    )
                    if (loaderReport.contains("external_xrWaitFrame=XR_SUCCESS") &&
                        loaderReport.contains("external_xrCreateReferenceSpace(LOCAL)=XR_SUCCESS")
                    ) {
                        assertHandLocateResult(loaderReport, "left")
                        assertHandLocateResult(loaderReport, "right")
                    }
                    assertTrue(
                        "External left hand tracker was not destroyed cleanly:\n$loaderReport",
                        loaderReport.contains("external_xrDestroyHandTrackerEXT_left=XR_SUCCESS")
                    )
                    assertTrue(
                        "External right hand tracker was not destroyed cleanly:\n$loaderReport",
                        loaderReport.contains("external_xrDestroyHandTrackerEXT_right=XR_SUCCESS")
                    )
                }
            }
        } finally {
            activity.finish()
        }
    }

    private fun assertHandLocateResult(report: String, hand: String) {
        val pattern = Regex(
            "external_xrLocateHandJointsEXT_" + hand +
                "=XR_SUCCESS active=(YES|NO) position_valid=(\\d+) " +
                "orientation_valid=(\\d+) tracked=(\\d+)"
        )
        val match = pattern.find(report)
        assertNotNull("External " + hand + " hand-joint locate failed or was not reported:\n" + report, match)
        val values = requireNotNull(match)
        val active = values.groupValues[1] == "YES"
        val positionValid = values.groupValues[2].toInt()
        val orientationValid = values.groupValues[3].toInt()
        val tracked = values.groupValues[4].toInt()
        val expectedValidCount = if (active) 26 else 0

        assertEquals("Unexpected " + hand + " position-valid joint count", expectedValidCount, positionValid)
        assertEquals("Unexpected " + hand + " orientation-valid joint count", expectedValidCount, orientationValid)
        assertEquals("Camera-estimated " + hand + " joints must not claim TRACKED flags", 0, tracked)
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
