package com.noyclic.questphonexr.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatterTest {
    @Test
    fun crashIssueTitleIsClearlyMarked() {
        assertEquals(
            "[Crash] QuestPhoneXR stopped unexpectedly",
            CrashReportFormatter.issueTitle(hasCrash = true)
        )
    }

    @Test
    fun issueBodyContainsSavedStackTrace() {
        val body = CrashReportFormatter.issueBody("IllegalStateException: test crash")

        assertTrue(body.contains("Crash report"))
        assertTrue(body.contains("IllegalStateException: test crash"))
        assertTrue(body.contains("user reviewed this preview"))
    }

    @Test
    fun issueBodyTruncatesLongReportsForMobileBrowserUrls() {
        val report = "x".repeat(CrashReportFormatter.MAX_ISSUE_REPORT_CHARS + 500)
        val body = CrashReportFormatter.issueBody(report)

        assertTrue(body.contains("[Crash report shortened"))
        assertTrue(body.length < 3_500)
    }


    @Test
    fun publicCrashReportsRedactTokensPasswordsAndSharedStoragePaths() {
        val raw = """
            Authorization: Bearer abc.def.ghi
            refresh_token=refresh-secret
            password: "my-private-password"
            Failed to open /storage/emulated/0/DCIM/private-photo.jpg
        """.trimIndent()

        val sanitized = CrashReportFormatter.sanitizeForPublicReport(raw)

        assertTrue(sanitized.contains("Bearer [REDACTED]"))
        assertTrue(sanitized.contains("refresh_token=[REDACTED]"))
        assertTrue(sanitized.contains("password: [REDACTED]"))
        assertTrue(sanitized.contains("[USER_STORAGE_PATH]"))
        assertTrue(!sanitized.contains("abc.def.ghi"))
        assertTrue(!sanitized.contains("refresh-secret"))
        assertTrue(!sanitized.contains("my-private-password"))
        assertTrue(!sanitized.contains("private-photo.jpg"))
    }

    @Test
    fun issueBodySanitizesEvenReportsCreatedByOlderAppVersions() {
        val body = CrashReportFormatter.issueBody(
            "api_key=secret-value\n/storage/emulated/0/Download/private.apk"
        )

        assertTrue(body.contains("api_key=[REDACTED]"))
        assertTrue(body.contains("[USER_STORAGE_PATH]"))
        assertTrue(!body.contains("secret-value"))
        assertTrue(!body.contains("private.apk"))
    }

    @Test
    fun manualIssueWithoutCrashReportGetsReproductionTemplate() {
        val body = CrashReportFormatter.issueBody(null)

        assertTrue(body.contains("Steps to reproduce"))
        assertTrue(body.contains("No automatic crash report was attached."))
    }
}
