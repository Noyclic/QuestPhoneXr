package com.noyclic.questphonexr.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxBrowserInteractionTest {
    @Test
    fun mapsPanelCenterToTextureCenter() {
        val point = SandboxBrowserInteraction.map(
            SandboxVector3(
                SandboxBrowserInteraction.PANEL_CENTER_X,
                SandboxBrowserInteraction.PANEL_CENTER_Y,
                SandboxBrowserInteraction.PANEL_CENTER_Z
            )
        )!!

        assertEquals((SandboxBrowserInteraction.TEXTURE_WIDTH - 1) / 2f, point.x, 1f)
        assertEquals((SandboxBrowserInteraction.TEXTURE_HEIGHT - 1) / 2f, point.y, 1f)
    }

    @Test
    fun mapsPanelCornersToTextureCorners() {
        val left = SandboxBrowserInteraction.PANEL_CENTER_X -
            SandboxBrowserInteraction.PANEL_WIDTH * 0.5f
        val right = SandboxBrowserInteraction.PANEL_CENTER_X +
            SandboxBrowserInteraction.PANEL_WIDTH * 0.5f
        val top = SandboxBrowserInteraction.PANEL_CENTER_Y +
            SandboxBrowserInteraction.PANEL_HEIGHT * 0.5f
        val bottom = SandboxBrowserInteraction.PANEL_CENTER_Y -
            SandboxBrowserInteraction.PANEL_HEIGHT * 0.5f

        val topLeft = SandboxBrowserInteraction.map(
            SandboxVector3(left, top, SandboxBrowserInteraction.PANEL_CENTER_Z)
        )!!
        val bottomRight = SandboxBrowserInteraction.map(
            SandboxVector3(right, bottom, SandboxBrowserInteraction.PANEL_CENTER_Z)
        )!!

        assertEquals(0f, topLeft.x, 0f)
        assertEquals(0f, topLeft.y, 0f)
        assertEquals((SandboxBrowserInteraction.TEXTURE_WIDTH - 1).toFloat(), bottomRight.x, 0f)
        assertEquals((SandboxBrowserInteraction.TEXTURE_HEIGHT - 1).toFloat(), bottomRight.y, 0f)
    }

    @Test
    fun textureCenterMapsBackToTheWorldPanelCenter() {
        val center = SandboxBrowserInteraction.toWorld(
            SandboxBrowserInteraction.PixelPoint(
                (SandboxBrowserInteraction.TEXTURE_WIDTH - 1) * 0.5f,
                (SandboxBrowserInteraction.TEXTURE_HEIGHT - 1) * 0.5f
            )
        )

        assertEquals(SandboxBrowserInteraction.PANEL_CENTER_X, center.x, 0.001f)
        assertEquals(SandboxBrowserInteraction.PANEL_CENTER_Y, center.y, 0.001f)
        assertTrue(center.z > SandboxBrowserInteraction.PANEL_CENTER_Z)
    }

    @Test
    fun rejectsCursorOutsidePanelAndAtUnreachableDepth() {
        val outside = SandboxVector3(2f, 0f, SandboxBrowserInteraction.PANEL_CENTER_Z)
        val tooFar = SandboxVector3(
            SandboxBrowserInteraction.PANEL_CENTER_X,
            SandboxBrowserInteraction.PANEL_CENTER_Y,
            SandboxBrowserInteraction.PANEL_CENTER_Z - 2f
        )

        assertNull(SandboxBrowserInteraction.map(outside))
        assertNull(SandboxBrowserInteraction.map(tooFar))
    }

    @Test
    fun dragMappingClampsToVisiblePanelEdge() {
        val point = SandboxBrowserInteraction.map(
            SandboxVector3(9f, 9f, SandboxBrowserInteraction.PANEL_CENTER_Z),
            clampToPanel = true
        )!!

        assertEquals((SandboxBrowserInteraction.TEXTURE_WIDTH - 1).toFloat(), point.x, 0f)
        assertEquals(0f, point.y, 0f)
        assertTrue(point.x >= 0f && point.y >= 0f)
    }
}
