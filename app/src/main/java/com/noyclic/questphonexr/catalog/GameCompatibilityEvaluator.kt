package com.noyclic.questphonexr.catalog

object GameCompatibilityEvaluator {
    fun evaluate(
        currentDevice: DeviceFingerprint,
        reports: List<GameTestReport>
    ): CompatibilityAssessment {
        val matchingDeviceReports = reports
            .filter { it.device.isSamePhysicalDevice(currentDevice) }
            .sortedByDescending { it.testedAtEpochMs }
        val latest = matchingDeviceReports.firstOrNull()

        val state = when (latest?.outcome) {
            TestOutcome.LAUNCHED -> CompatibilityState.LAUNCHES
            TestOutcome.PLAYABLE_WITH_ISSUES -> CompatibilityState.LIMITED
            TestOutcome.FAILED_TO_LAUNCH, TestOutcome.CRASHED -> CompatibilityState.FAILED
            null -> CompatibilityState.NOT_TESTED
        }

        return CompatibilityAssessment(
            state = state,
            latestReport = latest,
            reportsForThisDevice = matchingDeviceReports.size,
            reportsForOtherDevices = reports.size - matchingDeviceReports.size,
            androidVersionMatches = latest?.device?.androidVersion?.equals(
                currentDevice.androidVersion,
                ignoreCase = true
            )
        )
    }
}
