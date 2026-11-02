package com.noyclic.questphonexr.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface GameTestReportStore {
    fun getReports(gameId: String): List<GameTestReport>
    fun addReport(gameId: String, report: GameTestReport)
}

/**
 * Stores manually entered test reports on this device only.
 * Nothing is uploaded or published automatically.
 */
class SharedPreferencesGameTestReportStore(context: Context) : GameTestReportStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun getReports(gameId: String): List<GameTestReport> {
        val root = readRoot()
        val array = root.optJSONArray(gameId) ?: return emptyList()
        val reports = mutableListOf<GameTestReport>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            runCatching { item.toReport() }.getOrNull()?.let(reports::add)
        }
        return reports.sortedByDescending { it.testedAtEpochMs }
    }

    @Synchronized
    override fun addReport(gameId: String, report: GameTestReport) {
        require(gameId.isNotBlank()) { "Game id must not be blank." }
        val root = readRoot()
        val array = root.optJSONArray(gameId) ?: JSONArray()
        array.put(report.toJson())
        root.put(gameId, array)
        preferences.edit().putString(KEY_REPORTS, root.toString()).apply()
    }

    private fun readRoot(): JSONObject {
        val raw = preferences.getString(KEY_REPORTS, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun GameTestReport.toJson(): JSONObject {
        val deviceJson = JSONObject()
            .put("manufacturer", device.manufacturer)
            .put("model", device.model)
            .put("androidVersion", device.androidVersion)
            .put("apiLevel", device.apiLevel)

        return JSONObject()
            .put("device", deviceJson)
            .put("outcome", outcome.name)
            .put("testedAtEpochMs", testedAtEpochMs)
            .put("averageFps", averageFps ?: JSONObject.NULL)
            .put("minimumFps", minimumFps ?: JSONObject.NULL)
            .put("appVersion", appVersion)
            .put("notes", notes)
            .put("isManualEntry", isManualEntry)
    }

    private fun JSONObject.toReport(): GameTestReport {
        val deviceJson = getJSONObject("device")
        val average = optNullableInt("averageFps")
        val minimum = optNullableInt("minimumFps")
        return GameTestReport(
            device = DeviceFingerprint(
                manufacturer = deviceJson.optString("manufacturer"),
                model = deviceJson.optString("model"),
                androidVersion = deviceJson.optString("androidVersion"),
                apiLevel = deviceJson.optInt("apiLevel")
            ),
            outcome = TestOutcome.valueOf(getString("outcome")),
            testedAtEpochMs = getLong("testedAtEpochMs"),
            averageFps = average,
            minimumFps = minimum,
            appVersion = optString("appVersion"),
            notes = optString("notes"),
            isManualEntry = optBoolean("isManualEntry", true)
        )
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key).takeIf { it > 0 }
    }

    companion object {
        private const val PREFERENCES_NAME = "questphonexr_game_tests"
        private const val KEY_REPORTS = "reports_v1"
    }
}
