package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackingRecoveryPolicyTest {
    @Test
    fun retryDelayIncreasesAndStopsAfterThreeRapidFailures() {
        val policy = TrackingRecoveryPolicy()

        val first = policy.nextRetry(1_000L)
        val second = policy.nextRetry(2_000L)
        val third = policy.nextRetry(3_000L)

        assertEquals(1, first?.attempt)
        assertEquals(300L, first?.delayMs)
        assertEquals(2, second?.attempt)
        assertEquals(900L, second?.delayMs)
        assertEquals(3, third?.attempt)
        assertEquals(1_800L, third?.delayMs)
        assertNull("rapid failures must not create an endless restart loop", policy.nextRetry(4_000L))
    }

    @Test
    fun stableHealthyPeriodResetsTheRetryBudget() {
        val policy = TrackingRecoveryPolicy()
        policy.nextRetry(1_000L)
        policy.nextRetry(2_000L)

        policy.observeHealthy(17_000L)

        val nextFailure = policy.nextRetry(17_100L)
        assertNotNull(nextFailure)
        assertEquals(1, nextFailure?.attempt)
        assertEquals(300L, nextFailure?.delayMs)
    }

    @Test
    fun failuresDoNotResetJustBecauseTimePassedWithoutHealthyFrames() {
        val policy = TrackingRecoveryPolicy()
        policy.nextRetry(1_000L)
        policy.nextRetry(2_000L)
        policy.nextRetry(3_000L)

        assertNull(
            "a long dead pipeline is not a healthy interval",
            policy.nextRetry(30_000L)
        )
    }

    @Test
    fun recoveryPolicyResetsWhenClockMovesBackwards() {
        val policy = TrackingRecoveryPolicy()
        policy.nextRetry(5_000L)
        policy.nextRetry(5_100L)

        val afterClockReset = policy.nextRetry(100L)

        assertEquals(1, afterClockReset?.attempt)
    }

    @Test
    fun customRetryBudgetUsesLastConfiguredDelayForAdditionalAttempts() {
        val policy = TrackingRecoveryPolicy(
            maxAttempts = 4,
            retryDelaysMs = longArrayOf(10L, 20L),
            healthyResetAfterMs = 1_000L
        )

        assertEquals(10L, policy.nextRetry(1L)?.delayMs)
        assertEquals(20L, policy.nextRetry(2L)?.delayMs)
        assertEquals(20L, policy.nextRetry(3L)?.delayMs)
        assertEquals(20L, policy.nextRetry(4L)?.delayMs)
        assertNull(policy.nextRetry(5L))
    }
}
