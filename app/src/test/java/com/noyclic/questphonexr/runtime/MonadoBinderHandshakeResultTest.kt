package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonadoBinderHandshakeResultTest {
    @Test
    fun successfulBindRequiresTheExpectedIMonadoDescriptor() {
        val result = MonadoBinderHandshakeResult(
            status = MonadoBinderHandshakeStatus.BOUND,
            detail = "Binder connection established",
            interfaceDescriptor = "org.freedesktop.monado.ipc.IMonado"
        )

        assertTrue(result.isSuccessful)
    }

    @Test
    fun aBoundBinderWithTheWrongDescriptorIsNotSuccessful() {
        val result = MonadoBinderHandshakeResult(
            status = MonadoBinderHandshakeStatus.DESCRIPTOR_MISMATCH,
            detail = "Unexpected Binder interface",
            interfaceDescriptor = "wrong.interface"
        )

        assertFalse(result.isSuccessful)
    }
}
