package com.noyclic.questphonexr.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.noyclic.questphonexr.tracking.HandSide
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
    fun closeAffordanceWakesWhenTrackedHandApproachesPanel() {
        val state = SandboxBrowserInteraction.closeAffordance(
            hand(
                HandSide.LEFT,
                SandboxVector3(
                    SandboxBrowserInteraction.PANEL_CENTER_X,
                    SandboxBrowserInteraction.PANEL_CENTER_Y,
                    SandboxBrowserInteraction.PANEL_CENTER_Z + 0.55f
                )
            ),
            untrackedHand(HandSide.RIGHT)
        )

        assertTrue(state.visible)
        assertFalse(state.hovered)
    }

    @Test
    fun closeAffordanceHighlightsOnlyWhenCursorIsOverTopRightCorner() {
        val closeCorner = SandboxBrowserInteraction.toWorld(
            SandboxBrowserInteraction.PixelPoint(920f, 24f)
        )
        val state = SandboxBrowserInteraction.closeAffordance(
            hand(HandSide.RIGHT, closeCorner),
            untrackedHand(HandSide.LEFT)
        )

        assertTrue(state.visible)
        assertTrue(state.hovered)
    }

    @Test
    fun closeAffordanceDoesNotHighlightTheCornerForOtherPanelPositions() {
        val center = SandboxBrowserInteraction.toWorld(
            SandboxBrowserInteraction.PixelPoint(480f, 320f)
        )
        val state = SandboxBrowserInteraction.closeAffordance(
            hand(HandSide.LEFT, center),
            untrackedHand(HandSide.RIGHT)
        )

        assertTrue(state.visible)
        assertFalse(state.hovered)
    }

    @Test
    fun closeAffordanceIgnoresLostHandsAndHandsTooFarFromPanel() {
        val tooFar = SandboxVector3(
            SandboxBrowserInteraction.PANEL_CENTER_X,
            SandboxBrowserInteraction.PANEL_CENTER_Y,
            SandboxBrowserInteraction.PANEL_CENTER_Z + 1.2f
        )
        val lost = SandboxBrowserInteraction.closeAffordance(
            untrackedHand(HandSide.LEFT),
            untrackedHand(HandSide.RIGHT)
        )
        val distant = SandboxBrowserInteraction.closeAffordance(
            hand(HandSide.LEFT, tooFar),
            untrackedHand(HandSide.RIGHT)
        )

        assertFalse(lost.visible)
        assertFalse(lost.hovered)
        assertFalse(distant.visible)
        assertFalse(distant.hovered)
    }

    private fun hand(side: HandSide, cursor: SandboxVector3) = SandboxHandState(
        hand = side,
        cursor = cursor,
        grabbing = false,
        connected = true,
        cameraTracked = true
    )

    private fun untrackedHand(side: HandSide) = SandboxHandState(
        hand = side,
        cursor = SandboxVector3(0f, 0f, 0f),
        grabbing = false,
        connected = false,
        cameraTracked = false
    )

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
