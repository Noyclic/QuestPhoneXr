package com.noyclic.questphonexr.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidRuntimeBrokerContractTest {
    @Test
    fun activeRuntimeUriUsesTheKhronosPathWithoutAnExtraRowIndex() {
        assertEquals(
            "content://org.khronos.openxr.runtime_broker/openxr/1/abi/arm64-v8a/runtimes/active",
            AndroidRuntimeBrokerContract.activeRuntimeUri(
                "org.khronos.openxr.runtime_broker",
                1,
                "arm64-v8a"
            )
        )
    }

    @Test
    fun functionMappingUriIncludesTheRuntimePackageAndFunctionsEndpoint() {
        assertEquals(
            "content://org.khronos.openxr.system_runtime_broker/openxr/1/abi/arm64-v8a/runtimes/com.example.endxr/functions",
            AndroidRuntimeBrokerContract.functionMappingsUri(
                "org.khronos.openxr.system_runtime_broker",
                1,
                "arm64-v8a",
                "com.example.endxr"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownBrokerAuthorities() {
        AndroidRuntimeBrokerContract.activeRuntimeUri(
            "com.example.fakebroker",
            1,
            "arm64-v8a"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedPackageNamesBeforeBuildingFunctionUri() {
        AndroidRuntimeBrokerContract.functionMappingsUri(
            "org.khronos.openxr.runtime_broker",
            1,
            "arm64-v8a",
            "../other-app"
        )
    }
}
