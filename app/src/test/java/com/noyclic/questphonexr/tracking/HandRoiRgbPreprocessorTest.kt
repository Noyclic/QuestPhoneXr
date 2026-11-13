package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandRoiRgbPreprocessorTest {

    @Test
    fun writesArgbPixelsAsInterleavedRgbFloatValues() {
        val source = intArrayOf(
            0xff112233.toInt(),
            0xff445566.toInt(),
            0xff778899.toInt(),
            0xffaabbcc.toInt()
        )
        val output = FloatArray(12)

        val success = HandRoiRgbPreprocessor.writeRgbFloat(
            sourceArgb = source,
            crop = HandRoiCrop(0, 0, 2, 2, 2),
            outputWidthPx = 2,
            outputHeightPx = 2,
            destinationRgb = output
        )

        assertTrue(success)
        assertArrayEquals(
            floatArrayOf(
                17f, 34f, 51f,
                68f, 85f, 102f,
                119f, 136f, 153f,
                170f, 187f, 204f
            ),
            output,
            0.001f
        )
    }

    @Test
    fun bilinearResizeSamplesTheCenterOfTheSelectedRoi() {
        val source = intArrayOf(
            0xff000000.toInt(), 0xff640000.toInt(),
            0xffc80000.toInt(), 0xffff0000.toInt()
        )
        val output = FloatArray(3)

        assertTrue(
            HandRoiRgbPreprocessor.writeRgbFloat(
                source,
                HandRoiCrop(0, 0, 2, 2, 2),
                1,
                1,
                output
            )
        )

        assertEquals(142.75f, output[0], 0.001f)
        assertEquals(0f, output[1], 0.001f)
        assertEquals(0f, output[2], 0.001f)
    }

    @Test
    fun readsOnlyTheRequestedSquareCrop() {
        val source = IntArray(16) { index ->
            val value = (index * 10).coerceAtMost(255)
            (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
        val output = FloatArray(2 * 2 * 3)

        assertTrue(
            HandRoiRgbPreprocessor.writeRgbFloat(
                source,
                HandRoiCrop(1, 1, 2, 4, 4),
                2,
                2,
                output
            )
        )
        assertArrayEquals(
            floatArrayOf(
                50f, 50f, 50f,
                60f, 60f, 60f,
                90f, 90f, 90f,
                100f, 100f, 100f
            ),
            output,
            0.001f
        )
    }

    @Test
    fun rejectsInvalidDimensionsAndShortBuffersWithoutWriting() {
        val crop = HandRoiCrop(0, 0, 2, 2, 2)
        val output = FloatArray(12) { -1f }

        assertFalse(HandRoiRgbPreprocessor.writeRgbFloat(IntArray(3), crop, 2, 2, output))
        assertFalse(HandRoiRgbPreprocessor.writeRgbFloat(IntArray(4), crop, 0, 2, output))
        assertFalse(
            HandRoiRgbPreprocessor.writeRgbFloat(
                IntArray(4), crop, 2, 2, FloatArray(11)
            )
        )
        assertArrayEquals(FloatArray(12) { -1f }, output, 0f)
    }

    @Test
    fun preservesDestinationTailForLargerReusableBuffers() {
        val output = FloatArray(18) { -7f }
        val success = HandRoiRgbPreprocessor.writeRgbFloat(
            sourceArgb = intArrayOf(0xff010203.toInt()),
            crop = HandRoiCrop(0, 0, 1, 1, 1),
            outputWidthPx = 1,
            outputHeightPx = 1,
            destinationRgb = output
        )

        assertTrue(success)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), output.copyOfRange(0, 3), 0.001f)
        assertEquals(-7f, output[3], 0f)
    }
}
