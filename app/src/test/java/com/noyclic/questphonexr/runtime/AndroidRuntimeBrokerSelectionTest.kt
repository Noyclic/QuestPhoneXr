package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidRuntimeBrokerSelectionTest {
    private fun candidate(
        packageName: String,
        libraryExists: Boolean = true,
        hasFunctions: Boolean? = false,
        mappingsAvailable: Boolean = false
    ) = BrokerRuntimeCandidate(
        packageName = packageName,
        libraryPath = "/data/app/$packageName/lib/arm64/libendxr_openxr.so",
        soFilename = "libendxr_openxr.so",
        libraryExists = libraryExists,
        hasFunctions = hasFunctions,
        functionMappingsAvailable = mappingsAvailable
    )

    private fun snapshot(vararg candidates: BrokerRuntimeCandidate) =
        BrokerQuerySnapshot(
            providerFound = true,
            queryCompleted = true,
            hasRows = candidates.isNotEmpty(),
            candidates = candidates.toList(),
            status = "test"
        )

    @Test
    fun installableBrokerHasPriorityWhenItReturnsRows() {
        val selection = selectLoaderBrokerRuntime(
            installable = snapshot(candidate("com.example.other")),
            system = snapshot(candidate("com.noyclic.questphonexr"))
        )

        assertEquals("org.khronos.openxr.runtime_broker", selection.authority)
        assertEquals("com.example.other", selection.candidate?.packageName)
    }

    @Test
    fun systemBrokerIsUsedWhenInstallableBrokerHasNoActiveRows() {
        val selection = selectLoaderBrokerRuntime(
            installable = snapshot(),
            system = snapshot(candidate("com.noyclic.questphonexr"))
        )

        assertEquals("org.khronos.openxr.system_runtime_broker", selection.authority)
        assertEquals("com.noyclic.questphonexr", selection.candidate?.packageName)
    }

    @Test
    fun systemBrokerIsNotUsedWhenInstallableBrokerHasRowsButNoLoadableCandidate() {
        val selection = selectLoaderBrokerRuntime(
            installable = snapshot(candidate("com.example.broken", libraryExists = false)),
            system = snapshot(candidate("com.noyclic.questphonexr"))
        )

        assertEquals("org.khronos.openxr.runtime_broker", selection.authority)
        assertNull(selection.candidate)
    }

    @Test
    fun loaderSkipsMissingLibraryRowsBeforeSelectingTheNextCandidate() {
        val selection = selectLoaderBrokerRuntime(
            installable = snapshot(
                candidate("com.example.unavailable", libraryExists = false),
                candidate("com.noyclic.questphonexr")
            ),
            system = snapshot()
        )

        assertEquals("com.noyclic.questphonexr", selection.candidate?.packageName)
    }

    @Test
    fun loaderRequiresFunctionMappingsWhenBrokerMarksThemRequired() {
        val missingMappings = selectLoaderBrokerRuntime(
            installable = snapshot(
                candidate(
                    "com.example.remapped",
                    hasFunctions = true,
                    mappingsAvailable = false
                )
            ),
            system = snapshot(candidate("com.noyclic.questphonexr"))
        )
        val validMappings = selectLoaderBrokerRuntime(
            installable = snapshot(
                candidate(
                    "com.example.remapped",
                    hasFunctions = true,
                    mappingsAvailable = true
                )
            ),
            system = snapshot(candidate("com.noyclic.questphonexr"))
        )

        assertNull(missingMappings.candidate)
        assertEquals("com.example.remapped", validMappings.candidate?.packageName)
    }
}
