package com.noyclic.questphonexr

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.MediaStore
import com.noyclic.questphonexr.runtime.AndroidRuntimeBrokerProbe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.FixMethodOrder
import java.io.File

/**
 * End-to-end smoke test for the app entry flow.
 *
 * Device artifacts are copied to shared Downloads while the target APK is still
 * installed. The emulator runner uninstalls instrumentation packages afterwards,
 * which otherwise removes app-specific external storage before the CI script can
 * collect it.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppNavigationSmokeTest {

    @Test
    fun aLaunchLibraryAndReturnToModeSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        // Google APIs emulators can leave a system ANR sheet from the launcher
        // over the app task. Clear it before launching the application under test.
        device.pressHome()
        dismissSystemNotRespondingDialog(device)

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
        assertTrue("Launcher intent must be present", launchIntent != null)

        launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launchIntent)

        device.waitForIdle()
        // A pending crash-report dialog from an earlier emulator test can appear
        // before the mode selector. Dismiss it, then wait for the real mode action.
        if (device.wait(Until.hasObject(By.text("Предыдущий запуск завершился с ошибкой")), 1_000)) {
            device.findObject(By.text("Закрыть"))?.click()
        }

        assertTrue(
            "Mode selection Activity did not render its title",
            device.wait(Until.hasObject(By.textContains("Выбери режим")), 20_000)
        )
        saveScreenshot(context, device, "mode-selection-initial.png")
        assertTrue(
            "Mode selection screen did not expose its library action",
            device.wait(Until.hasObject(By.textContains("Открыть библиотеку")), 20_000)
        )
        assertTrue(
            "APK / Quest mode card is missing",
            device.wait(Until.hasObject(By.textContains("APK / Quest")), 5_000)
        )
        saveScreenshot(context, device, "mode-selection.png")
        assertTrue(
            "Open library button did not appear",
            device.wait(Until.hasObject(By.textContains("Открыть библиотеку")), 5_000)
        )
        device.findObject(By.textContains("Открыть библиотеку")).click()

        assertTrue(
            "Game library did not open; check the emulator logcat artifact",
            device.wait(Until.hasObject(By.text("VR-игры")), 20_000)
        )
        saveScreenshot(context, device, "game-library.png")

        // The readiness panel is near the top; the APK import button is intentionally
        // lower in the scrollable library. Exercise both locations instead of assuming
        // every action is visible on the first screen.
        val readinessAction = UiScrollable(UiSelector().scrollable(true))
            .scrollIntoView(UiSelector().text("Показать возможности EndXR"))
        assertTrue(
            "EndXR readiness details button is missing from the scrollable game library",
            readinessAction
        )
        device.findObject(By.text("Показать возможности EndXR")).click()

        // The capability list is longer than the dialog viewport on a phone-sized
        // display. Scroll inside the active dialog before asserting the Actions row.
        val actionCapability = UiScrollable(UiSelector().scrollable(true))
            .scrollIntoView(UiSelector().textContains("OpenXR Action / Input API"))
        assertTrue(
            "OpenXR Action API limitation is missing from the readiness report",
            actionCapability
        )
        assertTrue(
            "OpenXR Action / Input API capability did not become visible",
            device.wait(Until.hasObject(By.textContains("OpenXR Action / Input API")), 5_000)
        )
        val inputAccessAction = UiScrollable(UiSelector().scrollable(true))
            .scrollIntoView(UiSelector().text("Управление доступом к вводу"))
        assertTrue(
            "Package-scoped EndXR input authorization control is missing",
            inputAccessAction
        )
        saveScreenshot(context, device, "runtime-readiness.png")
        device.pressBack()

        val importAction = UiScrollable(UiSelector().scrollable(true))
            .scrollIntoView(UiSelector().text("Мои APK · импорт и анализ"))
        assertTrue(
            "APK import action is missing from the scrollable library",
            importAction
        )

        device.pressBack()
        device.waitForIdle()
        assertTrue(
            "Back navigation did not return to the mode-selection library action",
            device.wait(Until.hasObject(By.text("Открыть библиотеку")), 10_000)
        )
    }

    @Test
    fun bRuntimeServiceIsDiscoverableAndItsNativePayloadExists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val runtimeServiceInfo = packageManager.getServiceInfo(
            ComponentName(
                context,
                com.noyclic.questphonexr.runtime.EndXrRuntimeService::class.java
            ),
            PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        )
        assertEquals(
            "OpenXR runtime service must expose its own display name",
            context.getString(R.string.openxr_runtime_name),
            runtimeServiceInfo.loadLabel(packageManager).toString()
        )
        assertEquals(
            "OpenXR runtime service must expose the EndXR icon resource",
            R.drawable.ic_endxr_runtime,
            runtimeServiceInfo.icon
        )

        val report = AndroidRuntimeBrokerProbe.inspect(context)
        assertEquals(
            "Broker diagnostics must report the runtime service display name",
            context.getString(R.string.openxr_runtime_name),
            report.serviceLabel
        )
        assertTrue(
            "Broker diagnostics must detect the runtime service icon",
            report.serviceIconDeclared
        )
        val reportFile = File(context.cacheDir, "runtime-broker-probe.txt")
        reportFile.writeText(report.toDisplayText())
        publishCiArtifact(context, reportFile, "runtime-broker-probe.txt", "text/plain")

        assertTrue(
            "PackageManager did not discover the exported EndXR OpenXR Runtime Service:\n" +
                report.toDisplayText(),
            report.ownRuntimeServiceFound
        )
        assertTrue(
            "EndXR OpenXR Runtime Service is not exported:\n" + report.toDisplayText(),
            report.ownRuntimeServiceExported
        )
        assertTrue(
            "EndXR Runtime Service does not advertise OpenXR major version 1:\n" +
                report.toDisplayText(),
            report.majorVersion == 1
        )
        assertTrue(
            "EndXR Runtime Service metadata does not point to libendxr_openxr.so:\n" +
                report.toDisplayText(),
            report.soFilename == "libendxr_openxr.so"
        )
        assertTrue(
            "EndXR runtime native library is not present at the ServiceInfo nativeLibraryDir:\n" +
                report.toDisplayText(),
            report.nativeLibraryExists
        )
    }

    @Test
    fun zSandboxRunsLoaderFacingOpenXrProbeOnCurrentGlesContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        // Avoid the camera permission prompt obscuring the in-scene diagnostic label.
        device.executeShellCommand(
            "pm grant ${context.packageName} android.permission.CAMERA"
        )

        val sandboxIntent = Intent().setClassName(
            context.packageName,
            "${context.packageName}.sandbox.SandboxActivity"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(sandboxIntent)

        // The status label includes a human-readable summary after the PASS prefix.
        // Match the prefix rather than requiring the whole TextView to equal it.
        val probePassed = device.wait(
            Until.hasObject(By.textContains("OpenXR probe: PASS")),
            45_000
        )
        val diagnosticFile = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "ci-diagnostics/endxr-openxr-latest.log"
        )
        val report = if (diagnosticFile.isFile) diagnosticFile.readText() else
            "Diagnostic report file was not written: " + diagnosticFile.absolutePath

        // Export before assertions and before the test runner uninstalls the app.
        // This preserves failure reports as well as passing reports for later CI collection.
        if (diagnosticFile.isFile) {
            publishCiArtifact(context, diagnosticFile, "endxr-openxr-latest.log", "text/plain")
        }

        assertTrue(
            "Sandbox did not pass the OpenXR runtime probe. Full diagnostic:\n" + report,
            probePassed
        )
        assertTrue(
            "OpenXR probe did not persist a schema-v4 30-frame projection-layer report:\n" + report,
            report.contains("diagnostic_schema=4") &&
                report.contains("projection_layer_composition=PASS") &&
                report.contains("check_7=stereo projection layer composition + GLES backbuffer readback | PASS") &&
                report.contains("projection_layer_frames_submitted=30") &&
                report.contains("projection_layer_swapchain_images_seen=3") &&
                report.contains("check_8=30 consecutive OpenXR frames + triple-buffer rotation | PASS") &&
                report.contains("projection_layer_left_pixel_rgba=") &&
                report.contains("projection_layer_right_pixel_rgba=") &&
                report.contains("gl_renderer=") &&
                report.contains("gles_last_texture_allocation_error=")
        )

        assertTrue(
            "Settings panel toggle did not appear",
            device.wait(Until.hasObject(By.text("Скрыть настройки")), 5_000)
        )
        device.findObject(By.text("Скрыть настройки")).click()
        assertTrue(
            "Settings panel did not hide",
            device.wait(Until.hasObject(By.text("Показать настройки")), 5_000)
        )

        val diagnosticsButton = By.text("Диагностика EndXR")
        assertTrue(
            "Always-visible EndXR diagnostics button is missing while settings are hidden",
            device.wait(Until.hasObject(diagnosticsButton), 5_000)
        )
        saveScreenshot(context, device, "sandbox-diagnostics-button.png")

        val externalProbeButton = By.text("Внешний OpenXR тест")
        assertTrue(
            "External OpenXR probe launcher is missing from Sandbox",
            device.wait(Until.hasObject(externalProbeButton), 5_000)
        )
        device.findObject(externalProbeButton).click()
        assertTrue(
            "Missing external probe APK was not explained to the user",
            device.wait(Until.hasObject(By.text("Внешний OpenXR тест не установлен")), 5_000)
        )
        device.findObject(By.text("Закрыть")).click()

        // Android 15 may suppress or expose toast overlays inconsistently to UiAutomator.
        // Verify the app's own post-setPrimaryClip log instead of reading another process's clipboard.
        device.executeShellCommand("logcat -c")
        device.findObject(diagnosticsButton).click()
        val copyDeadline = android.os.SystemClock.uptimeMillis() + 5_000
        var copyLog = ""
        while (
            android.os.SystemClock.uptimeMillis() < copyDeadline &&
            !copyLog.contains("Clipboard setPrimaryClip completed")
        ) {
            copyLog = device.executeShellCommand("logcat -d -s EndXR-DIAG:I")
            if (!copyLog.contains("Clipboard setPrimaryClip completed")) {
                android.os.SystemClock.sleep(100)
            }
        }
        assertTrue(
            "EndXR diagnostics action did not complete setPrimaryClip. Log:\n" + copyLog,
            copyLog.contains("Clipboard setPrimaryClip completed")
        )
    }

    private fun dismissSystemNotRespondingDialog(device: UiDevice) {
        val anrTitle = By.textContains("isn't responding")
        if (!device.wait(Until.hasObject(anrTitle), 1_000)) return

        // Closing the unresponsive system launcher is safer than waiting on an
        // overlay that can steal accessibility focus from the app under test.
        val closeApp = By.text("Close app")
        if (device.wait(Until.hasObject(closeApp), 2_000)) {
            device.findObject(closeApp)?.click()
            device.waitForIdle()
        }
    }

    private fun saveScreenshot(context: Context, device: UiDevice, fileName: String) {
        val root = requireNotNull(context.getExternalFilesDir(null)) {
            "External app storage is unavailable in the emulator"
        }
        val directory = File(root, "ci-screenshots")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create screenshot directory: ${directory.absolutePath}"
        }
        val screenshot = File(directory, fileName)
        assertTrue(
            "Could not save emulator screenshot: $fileName",
            device.takeScreenshot(screenshot)
        )
        publishCiArtifact(context, screenshot, fileName, "image/png")
    }

    /**
     * Publish a durable CI copy under Downloads/QuestPhoneXR-CI.
     * MediaStore avoids legacy storage permissions and survives package uninstall.
     */
    private fun publishCiArtifact(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String
    ) {
        check(source.isFile && source.length() > 0L) {
            "CI artifact source is missing or empty: ${source.absolutePath}"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/QuestPhoneXR-CI/"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Could not create Downloads entry for $displayName" }

        try {
            val output = requireNotNull(resolver.openOutputStream(uri, "w")) {
                "Could not open Downloads output stream for $displayName"
            }
            source.inputStream().use { input ->
                output.use { input.copyTo(it) }
            }
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            check(resolver.update(uri, ready, null, null) == 1) {
                "Could not finalize Downloads entry for $displayName"
            }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
