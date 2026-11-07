package com.noyclic.questphonexr.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxHandMeshGeometryTest {
    @Test
    fun fiveDigitChainsCoverAllMediaPipeFingerBonesWithoutInvalidIndices() {
        val chains = SandboxHandMeshGeometry.FINGER_CHAINS

        assertEquals(5, chains.size)
        assertEquals(15, chains.sumOf { it.size - 1 })
        chains.forEach { chain ->
            assertEquals(4, chain.size)
            assertTrue(chain.all { it in 0..20 })
            assertTrue(chain.toList().zipWithNext().all { (first, second) -> first != second })
        }
    }

    @Test
    fun palmLinksAndThumbWebFormAContinuousPalmBase() {
        val links = SandboxHandMeshGeometry.PALM_LINKS

        assertEquals(listOf(2, 5), SandboxHandMeshGeometry.THUMB_WEB.toList())
        assertEquals(5, links.size)
        assertEquals(listOf(0, 5), links.first().toList())
        assertEquals(listOf(17, 0), links.last().toList())
        assertEquals(SandboxHandMeshGeometry.MAX_PRIMITIVES_PER_HAND, 1 + links.size + 1 + 15 + 2)
        assertEquals(SandboxHandMeshGeometry.BASE_PRIMITIVES_PER_HAND, 1 + links.size + 1 + 15)
    }

    @Test
    fun fingerRadiusReflectsDigitWidthAndTapersTowardFingertips() {
        val palmWidth = 0.28f
        val thumbBase = SandboxHandMeshGeometry.fingerSegmentRadius(palmWidth, 0, 0)
        val thumbTip = SandboxHandMeshGeometry.fingerSegmentRadius(palmWidth, 0, 2)
        val littleBase = SandboxHandMeshGeometry.fingerSegmentRadius(palmWidth, 4, 0)

        assertTrue("thumb should be broader than the little finger", thumbBase > littleBase)
        assertTrue("distal segments should taper toward the fingertip", thumbTip < thumbBase)
        assertTrue(thumbBase in 0.012f..0.029f)
        assertTrue(littleBase in 0.012f..0.029f)
    }

    @Test
    fun radiusCalculationStaysFiniteAndBoundedForInvalidOrExtremePalmWidth() {
        val invalidWidth = SandboxHandMeshGeometry.fingerSegmentRadius(Float.NaN, 2, 1)
        val tinyWidth = SandboxHandMeshGeometry.fingerSegmentRadius(-10f, 2, 0)
        val hugeWidth = SandboxHandMeshGeometry.fingerSegmentRadius(100f, 2, 0)
        val palmLink = SandboxHandMeshGeometry.palmLinkRadius(100f)

        assertTrue(invalidWidth.isFinite() && invalidWidth in 0.012f..0.029f)
        assertTrue(tinyWidth.isFinite() && tinyWidth in 0.012f..0.029f)
        assertTrue(hugeWidth.isFinite() && hugeWidth in 0.012f..0.029f)
        assertTrue(palmLink.isFinite() && palmLink in 0.014f..0.030f)
    }
}
