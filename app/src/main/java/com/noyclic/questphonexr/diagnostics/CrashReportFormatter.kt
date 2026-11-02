package com.noyclic.questphonexr.diagnostics

/** Keeps browser-prefilled GitHub reports short enough for mobile URL handlers. */
object CrashReportFormatter {
    const val MAX_ISSUE_REPORT_CHARS = 2_200

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

        val trimmed = crashReport.take(MAX_ISSUE_REPORT_CHARS)
        val suffix = if (crashReport.length > trimmed.length) {
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
