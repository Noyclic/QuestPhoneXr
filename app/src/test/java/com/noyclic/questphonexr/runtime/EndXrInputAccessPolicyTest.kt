package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndXrInputAccessPolicyTest {
    private val fingerprint = "0123456789abcdef".repeat(4)

    @Test
    fun packageNameAndSignerFingerprintMustBothBeValid() {
        assertTrue(EndXrInputGrantCodec.isValidPackageName("com.example.vrgame"))
        assertTrue(EndXrInputGrantCodec.isValidPackageName("org.example_game.client"))
        assertTrue(EndXrInputGrantCodec.isValidPackageName("com.example"))
        assertFalse(EndXrInputGrantCodec.isValidPackageName("com..example"))
        assertFalse(EndXrInputGrantCodec.isValidPackageName("com.example;other"))
        assertTrue(EndXrInputGrantCodec.isValidFingerprint(fingerprint))
        assertFalse(EndXrInputGrantCodec.isValidFingerprint(fingerprint.dropLast(1)))
        assertFalse(EndXrInputGrantCodec.isValidFingerprint(fingerprint.uppercase()))
    }

    @Test
    fun encodingAndDecodingKeepOneApprovalPerPackage() {
        val first = EndXrInputGrantRecord("com.example.vrgame", fingerprint)
        val second = EndXrInputGrantRecord("org.example.client", "abcdef12".repeat(8))

        val encoded = EndXrInputGrantCodec.encode(listOf(first, first, second))
        val decoded = EndXrInputGrantCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertTrue(decoded.contains(first))
        assertTrue(decoded.contains(second))
    }

    @Test
    fun restoredGrantRequiresExactPackageAndSigningCertificate() {
        val record = EndXrInputGrantRecord("com.example.vrgame", fingerprint)
        val matchingClient = EndXrInputClient(
            packageName = "com.example.vrgame",
            label = "VR Game",
            signingCertificateSha256 = fingerprint
        )
        val differentSigner = matchingClient.copy(
            signingCertificateSha256 = "abcdef12".repeat(8)
        )
        val differentPackage = matchingClient.copy(
            packageName = "com.example.other"
        )

        assertTrue(EndXrInputGrantCodec.matches(record, matchingClient))
        assertFalse(EndXrInputGrantCodec.matches(record, differentSigner))
        assertFalse(EndXrInputGrantCodec.matches(record, differentPackage))
    }

    @Test
    fun reconciliationPreservesConcurrentApprovalsAndRevocations() {
        val previous = EndXrInputGrantRecord("com.example.vrgame", fingerprint)
        val replacement = previous.copy(signingCertificateSha256 = "abcdef12".repeat(8))
        val newApproval = EndXrInputGrantRecord("org.example.newgame", "fedcba98".repeat(8))

        assertEquals(
            listOf(replacement, newApproval),
            EndXrInputGrantCodec.mergeAfterReconcile(
                original = listOf(previous),
                retained = listOf(previous),
                latest = listOf(replacement, newApproval)
            )
        )
        assertTrue(
            EndXrInputGrantCodec.mergeAfterReconcile(
                original = listOf(previous),
                retained = listOf(previous),
                latest = emptyList()
            ).isEmpty()
        )
        assertTrue(
            EndXrInputGrantCodec.mergeAfterReconcile(
                original = listOf(previous),
                retained = emptyList(),
                latest = listOf(previous)
            ).isEmpty()
        )
    }

    @Test
    fun malformedStoredEntriesAreIgnored() {
        val decoded = EndXrInputGrantCodec.decode(
            setOf(
                "com.example.vrgame|$fingerprint",
                "com.example.vrgame|not-a-fingerprint",
                "not-a-package|$fingerprint",
                "missing-separator",
                "|$fingerprint"
            )
        )

        assertEquals(
            listOf(EndXrInputGrantRecord("com.example.vrgame", fingerprint)),
            decoded
        )
    }
}
