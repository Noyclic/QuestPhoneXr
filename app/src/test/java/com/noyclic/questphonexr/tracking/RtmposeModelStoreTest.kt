package com.noyclic.questphonexr.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RtmposeModelStoreTest {

    @Test
    fun verifiesFileSizeAndSha256Together() {
        val file = File.createTempFile("endxr-rtmpose", ".bin")
        try {
            file.writeText("EndXR pinned model test")
            val expectedHash = RtmposeModelStore.sha256(file)
            assertTrue(RtmposeModelStore.verify(file, file.length(), expectedHash))
            assertFalse(RtmposeModelStore.verify(file, file.length() + 1L, expectedHash))
            assertFalse(RtmposeModelStore.verify(file, file.length(), "00".repeat(32)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun publishedModelDescriptorHasPinnedUrlSizeAndSha256() {
        assertTrue(RtmposeModelStore.MODEL_URL.startsWith("https://"))
        assertEquals(27_827_248L, RtmposeModelStore.EXPECTED_SIZE_BYTES)
        assertEquals(64, RtmposeModelStore.EXPECTED_SHA256.length)
        assertTrue(RtmposeModelStore.EXPECTED_SHA256.all {
            it in '0'..'9' || it in 'a'..'f'
        })
    }
}
