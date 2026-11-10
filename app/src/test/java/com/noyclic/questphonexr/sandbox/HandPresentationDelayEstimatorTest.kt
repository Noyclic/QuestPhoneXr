package com.noyclic.questphonexr.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandPresentationDelayEstimatorTest {
    @Test
    fun stableFastCadenceUsesMinimumUsefulPresentationDelay() {
        val estimator = HandPresentationDelayEstimator()
        estimator.observe(33L)
        estimator.observe(33L)
        estimator.observe(33L)

        assertEquals(18L, estimator.delayMs(33L))
    }

    @Test
    fun stableSlowCadenceUsesShorterDelayThanThePreviousFixedRatio() {
        val estimator = HandPresentationDelayEstimator()
        estimator.observe(125L)
        estimator.observe(125L)
        estimator.observe(125L)

        assertEquals(37L, estimator.delayMs(125L))
    }

    @Test
    fun irregularInferenceIntervalsIncreaseTheBufferTemporarily() {
        val stable = HandPresentationDelayEstimator()
        stable.observe(100L)
        stable.observe(100L)
        val stableDelay = stable.delayMs(100L)

        val jittery = HandPresentationDelayEstimator()
        jittery.observe(100L)
        jittery.observe(180L)
        val jitterDelay = jittery.delayMs(180L)

        assertTrue("jitter should increase the presentation buffer", jitterDelay > stableDelay)
        assertTrue(jitterDelay <= 64L)
    }

    @Test
    fun presentationDelayRemainsBoundedForExtremeCadenceAndJitter() {
        val estimator = HandPresentationDelayEstimator()
        estimator.observe(220L)
        repeat(12) { index -> estimator.observe(if (index % 2 == 0) 16L else 220L) }

        assertEquals(64L, estimator.delayMs(220L))
    }

    @Test
    fun resetClearsLearnedJitterAndUsesTheNextSampleAsFreshBaseline() {
        val estimator = HandPresentationDelayEstimator()
        estimator.observe(100L)
        estimator.observe(180L)
        estimator.reset()
        estimator.observe(100L)

        assertEquals(30L, estimator.delayMs(100L))
    }
}
