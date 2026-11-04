package com.noyclic.questphonexr.catalog

import android.content.Intent
import android.content.pm.PackageManager

data class OpenSaberInstalledApp(
    val packageName: String,
    val label: String
)

/**
 * Finds the independently installed Android/SBS mobile port by its launcher label
 * or package id. Android package visibility is limited to launchable apps.
 */
object OpenSaberMobileLauncher {
    @Suppress("DEPRECATION")
    fun findInstalledApps(packageManager: PackageManager): List<OpenSaberInstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName ?: return@mapNotNull null
                val label = runCatching {
                    resolveInfo.loadLabel(packageManager).toString()
                }.getOrDefault(packageName)
                val searchable = "$label $packageName".lowercase()
                if (!searchable.contains("open saber") && !searchable.contains("opensaber")) {
                    return@mapNotNull null
                }
                OpenSaberInstalledApp(packageName, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
