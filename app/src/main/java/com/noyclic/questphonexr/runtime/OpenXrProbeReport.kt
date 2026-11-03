package com.noyclic.questphonexr.runtime

import org.json.JSONObject

/** Structured result from the isolated native OpenXR GLES probe. */
data class OpenXrProbeReport(
    val status: String,
    val xrResult: Int?,
    val message: String
) {
    val isSuccessful: Boolean
        get() = status == SUCCESS_STATUS

    fun toDisplayText(): String = buildString {
        append(status)
        xrResult?.let { append(" · XrResult ").append(it) }
        append("\n").append(message)
    }

    companion object {
        const val SUCCESS_STATUS = "FIRST_FRAME_SUBMITTED_GLES"

        fun parse(json: String): OpenXrProbeReport = runCatching {
            val root = JSONObject(json)
            OpenXrProbeReport(
                status = root.optString("status", "UNKNOWN").ifBlank { "UNKNOWN" },
                xrResult = if (root.has("xrResult") && !root.isNull("xrResult")) {
                    root.optInt("xrResult")
                } else {
                    null
                },
                message = root.optString("message", "No diagnostic message.")
            )
        }.getOrElse { error ->
            OpenXrProbeReport(
                status = "INVALID_REPORT",
                xrResult = null,
                message = "Native OpenXR probe returned invalid JSON: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }
    }
}