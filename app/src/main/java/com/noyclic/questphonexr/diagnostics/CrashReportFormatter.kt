package com.noyclic.questphonexr.diagnostics

/** Formats crash reports for explicit, user-reviewed publication to GitHub. */
object CrashReportFormatter {
    const val MAX_ISSUE_REPORT_CHARS = 2_200

    private val secretAssignment = Regex(
        """(?i)\\b(authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|client[_-]?secret|password|passwd)\\b(\\s*[:=]\\s*)(?:Bearer\\s+)?(?:"[^"]*"|'[^']*'|[^\\s,;]+)"""
    )
    private val bearerToken = Regex("""(?i)\b(Bearer\s+)[A-Za-z0-9._~+/=-]+""")
    private val sharedStoragePath = Regex("""(?i)(/storage/emulated/0|/sdcard)(?:/[^\s"'<>)]*)?""")

    fun sanitizeForPublicReport(value: String): String =
        value
            .replace(secretAssignment) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
            .replace(bearerToken) { match -> "${match.groupValues[1]}[REDACTED]" }
            .replace(sharedStoragePath, "[USER_STORAGE_PATH]")

    fun issueTitle(hasCrash: Boolean): String =
        if (hasCrash) "[Crash] QuestPhoneXR stopped unexpectedly"
        else "[Bug] QuestPhoneXR problem report"

    fun issueBody(crashReport: String?): String {
        if (crashReport.isNullOrBlank()) {
            return """
                ## What happened?
                Describe what you were doing and what you expected to happen.

                ## Steps to reproduce
                1.
                2.
                3.

                ## Device
                - Model:
                - Android version:
                - QuestPhoneXR version:

                No automatic crash report was attached.
            """.trimIndent()
        }

        val sanitized = sanitizeForPublicReport(crashReport)
        val trimmed = sanitized.take(MAX_ISSUE_REPORT_CHARS)
        val suffix = if (sanitized.length > trimmed.length) {
            "\n\n[Crash report shortened for the GitHub mobile form. The complete local file can be shared separately.]"
        } else {
            ""
        }
        return """
            A crash report was saved locally by QuestPhoneXR. The user reviewed this preview and chose to open GitHub.

            ## Crash report
            ```text
            $trimmed$suffix
            ```

            ## Additional information
            - What were you doing when it crashed?
            - Can you reproduce it?
        """.trimIndent()
    }
}
