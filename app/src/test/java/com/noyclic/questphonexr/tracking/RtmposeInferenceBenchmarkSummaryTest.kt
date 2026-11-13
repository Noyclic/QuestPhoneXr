package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RtmposeInferenceBenchmarkSummaryTest {

    @Test
    fun computesLinearInterpolatedP50AndP95FromUnorderedSamples() {
        val summary = RtmposeInferenceBenchmarkSummary.fromSamplesNanos(
            longArrayOf(4_000_000L, 1_000_000L, 3_000_000L, 2_000_000L)
        )

        assertEquals(4, summary.sampleCount)
        assertEquals(2.5f, summary.p50Milliseconds, 0.0001f)
        assertEquals(3.85f, summary.p95Milliseconds, 0.0001f)
        assertEquals(400f, summary.estimatedSingleCropInferencesPerSecond, 0.01f)
    }

    @Test
    fun preservesEqualSamplesAndReportsSingleSampleThroughput() {
        val summary = RtmposeInferenceBenchmarkSummary.fromSamplesNanos(
            longArrayOf(5_000_000L, 5_000_000L, 5_000_000L)
        )
        assertEquals(5f, summary.p50Milliseconds, 0.0001f)
        assertEquals(5f, summary.p95Milliseconds, 0.0001f)
        assertEquals(200f, summary.estimatedSingleCropInferencesPerSecond, 0.01f)
    }

    @Test
    fun rejectsEmptyOrNonPositiveTimingSamples() {
        assertThrows(IllegalArgumentException::class.java) {
            RtmposeInferenceBenchmarkSummary.fromSamplesNanos(longArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RtmposeInferenceBenchmarkSummary.fromSamplesNanos(longArrayOf(1_000_000L, 0L))
        }
    }
}
