package com.noyclic.endvr.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MjpegPartReaderTest {
    @Test
    fun readsMultipleLengthDelimitedJpegParts() {
        val body = ByteArrayOutputStream().apply {
            writePart(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xD9.toByte()))
            writePart(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 2, 0xD9.toByte()))
            write("--endvrframe--\r\n".toByteArray(Charsets.US_ASCII))
        }
        val reader = MjpegPartReader(ByteArrayInputStream(body.toByteArray()), "endvrframe")

        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xD9.toByte()), reader.readFrame()!!)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 2, 0xD9.toByte()), reader.readFrame()!!)
        assertNull(reader.readFrame()!!)
    }

    @Test
    fun rejectsPartWithoutContentLength() {
        val body = "--frame\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val reader = MjpegPartReader(ByteArrayInputStream(body), "frame")
        assertThrows(IllegalStateException::class.java) { reader.readFrame() }
    }

    @Test
    fun rejectsOversizedFrameBeforeAllocatingIt() {
        val body = "--frame\r\nContent-Length: 9000000\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val reader = MjpegPartReader(ByteArrayInputStream(body), "frame")
        assertThrows(IllegalStateException::class.java) { reader.readFrame() }
    }

    @Test
    fun rejectsUnexpectedBoundary() {
        val body = "--other\r\n".toByteArray(Charsets.US_ASCII)
        val reader = MjpegPartReader(ByteArrayInputStream(body), "frame")
        assertThrows(IllegalStateException::class.java) { reader.readFrame() }
    }

    @Test
    fun extractsQuotedMultipartBoundary() {
        assert("endvrframe" == MjpegPartReader.boundaryFromContentType(
            "multipart/x-mixed-replace; boundary=\"endvrframe\""
        ))
    }

    private fun ByteArrayOutputStream.writePart(bytes: ByteArray) {
        write("--endvrframe\r\nContent-Type: image/jpeg\r\n".toByteArray(Charsets.US_ASCII))
        write(("Content-Length: " + bytes.size + "\r\n\r\n").toByteArray(Charsets.US_ASCII))
        write(bytes)
        write("\r\n".toByteArray(Charsets.US_ASCII))
    }
}
