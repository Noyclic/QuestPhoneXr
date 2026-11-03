package com.noyclic.questphonexr.performance

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Process

/** One-shot Android capability probe. It does not run a stress test or change device settings. */
object AndroidDeviceCompatibilityProbe {
    private const val MEGABYTE = 1024L * 1024L

    fun inspect(context: Context): DeviceCompatibilityReport {
        val appContext = context.applicationContext
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val packageManager = appContext.packageManager

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val encodedGlVersion = activityManager.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val vulkanVersionRaw = findFeatureVersion(
            packageManager,
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
        )
        val vulkanLevelRaw = findFeatureVersion(
            packageManager,
            PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
        )

        val vulkanVersion = vulkanVersionRaw?.takeIf { it > 0 }
        val vulkanMajor = vulkanVersion?.ushr(22)
        val vulkanMinor = vulkanVersion?.ushr(12)?.and(0x3FF)
        val vulkanPatch = vulkanVersion?.and(0xFFF)

        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android device" }

        return DeviceCompatibilityEvaluator.evaluate(
            DeviceCompatibilityInput(
                deviceName = model,
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                sdkVersion = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                is64BitProcess = Process.is64Bit(),
                processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                totalRamMb = (memoryInfo.totalMem / MEGABYTE).toInt().coerceAtLeast(0),
                availableRamMb = (memoryInfo.availMem / MEGABYTE).toInt().coerceAtLeast(0),
                appMemoryClassMb = activityManager.memoryClass.coerceAtLeast(0),
                isLowRamDevice = activityManager.isLowRamDevice,
                openGlEsMajor = (encodedGlVersion ushr 16) and 0xFFFF,
                openGlEsMinor = encodedGlVersion and 0xFFFF,
                vulkanApiMajor = vulkanMajor,
                vulkanApiMinor = vulkanMinor,
                vulkanApiPatch = vulkanPatch,
                vulkanHardwareLevel = vulkanLevelRaw,
                hasGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
                hasRotationVector = rotationSensor != null,
                hasAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
                rotationVectorSensorName = rotationSensor?.name
            )
        )
    }

    private fun findFeatureVersion(packageManager: PackageManager, featureName: String): Int? =
        packageManager.systemAvailableFeatures
            ?.firstOrNull { it.name == featureName }
            ?.version
}
