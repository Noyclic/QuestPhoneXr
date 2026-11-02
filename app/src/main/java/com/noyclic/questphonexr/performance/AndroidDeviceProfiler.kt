package com.noyclic.questphonexr.performance

import android.app.ActivityManager
import android.content.Context

/** Reads a small, one-shot hardware snapshot. It deliberately performs no benchmark. */
object AndroidDeviceProfiler {
    private const val MEGABYTE = 1024L * 1024L

    fun read(context: Context): DeviceCapabilities {
        val appContext = context.applicationContext
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val encodedGlVersion =
            activityManager.deviceConfigurationInfo?.reqGlEsVersion ?: 0

        return DeviceCapabilities(
            totalRamMb = (memoryInfo.totalMem / MEGABYTE).toInt().coerceAtLeast(0),
            appMemoryClassMb = activityManager.memoryClass.coerceAtLeast(0),
            processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            openGlEsMajor = (encodedGlVersion ushr 16) and 0xFFFF,
            openGlEsMinor = encodedGlVersion and 0xFFFF,
            isLowRamDevice = activityManager.isLowRamDevice
        )
    }
}
