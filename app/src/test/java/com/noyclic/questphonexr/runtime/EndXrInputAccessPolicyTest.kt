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
    fun legacyConsentDoesNotSilentlyGrantRuntimeTelemetry() {
        val legacy = "com.example.vrgame|$fingerprint"
        val decoded = EndXrInputGrantCodec.decode(setOf(legacy)).single()

        assertEquals("com.example.vrgame", decoded.packageName)
        assertEquals(fingerprint, decoded.signingCertificateSha256)
        assertFalse(decoded.runtimeTelemetryGranted)
    }

    @Test
    fun explicitTelemetryConsentSurvivesEncodingAndDecoding() {
        val consent = EndXrInputGrantRecord(
            packageName = "com.example.vrgame",
            signingCertificateSha256 = fingerprint,
            runtimeTelemetryGranted = true
        )

        val encoded = EndXrInputGrantCodec.encode(listOf(consent))
        val decoded = EndXrInputGrantCodec.decode(encoded).single()

        assertTrue(decoded.runtimeTelemetryGranted)
        assertEquals(consent, decoded)
    }

    @Test
    fun malformedTelemetryConsentFlagIsIgnored() {
        val decoded = EndXrInputGrantCodec.decode(
            setOf("com.example.vrgame|$fingerprint|yes")
        )

        assertTrue(decoded.isEmpty())
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
