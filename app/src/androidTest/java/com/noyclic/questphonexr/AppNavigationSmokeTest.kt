package com.noyclic.questphonexr

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end smoke test for the app entry flow.
 *
 * This verifies that the launcher screen and game library can be opened in a
 * clean Android environment. Hardware-specific XR and camera behavior is
 * deliberately not inferred from an emulator run.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationSmokeTest {

    @Test
    fun launchLibraryAndReturnToModeSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
        assertTrue("Launcher intent must be present", launchIntent != null)

        launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launchIntent)

        assertTrue(
            "Mode selection screen did not appear",
            device.wait(Until.hasObject(By.text("Выбери режим")), 15_000)
        )
        assertTrue(
            "APK / Quest mode card is missing",
            device.wait(Until.hasObject(By.text("APK / Quest")), 5_000)
        )
        saveScreenshot(context.getExternalFilesDir(null), device, "mode-selection.png")

        assertTrue(
            "Open library button did not appear",
            device.wait(Until.hasObject(By.text("Открыть библиотеку")), 5_000)
        )
        device.findObject(By.text("Открыть библиотеку")).click()

        assertTrue(
            "Game library did not open; check the emulator logcat artifact",
            device.wait(Until.hasObject(By.text("VR-игры")), 20_000)
        )
        assertTrue(
            "APK import action is missing from the library",
            device.wait(Until.hasObject(By.text("Мои APK · импорт и анализ")), 5_000)
        )
        saveScreenshot(context.getExternalFilesDir(null), device, "game-library.png")

        device.pressBack()
        assertTrue(
            "Back navigation did not return to mode selection",
            device.wait(Until.hasObject(By.text("Выбери режим")), 10_000)
        )
    }

    @Test
    fun sandboxRunsLoaderFacingOpenXrProbeOnCurrentGlesContext() {
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
        assertTrue(
            "Sandbox did not pass the OpenXR runtime probe. Full diagnostic:\n" + report,
            probePassed
        )
        assertTrue(
            "OpenXR probe did not persist a schema-v2 diagnostic report:\n" + report,
            report.contains("diagnostic_schema=2") &&
                report.contains("gl_renderer=") &&
                report.contains("gles_last_texture_allocation_error=")
        )
    }

    private fun saveScreenshot(baseDirectory: File?, device: UiDevice, fileName: String) {
        val root = requireNotNull(baseDirectory) {
            "External app storage is unavailable in the emulator"
        }
        val directory = File(root, "ci-screenshots")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create screenshot directory: ${directory.absolutePath}"
        }
        assertTrue(
            "Could not save emulator screenshot: $fileName",
            device.takeScreenshot(File(directory, fileName))
        )
    }
}
