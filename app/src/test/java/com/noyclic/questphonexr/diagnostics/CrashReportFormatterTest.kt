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
    fun manualIssueWithoutCrashReportGetsReproductionTemplate() {
        val body = CrashReportFormatter.issueBody(null)

        assertTrue(body.contains("Steps to reproduce"))
        assertTrue(body.contains("No automatic crash report was attached."))
    }
}
