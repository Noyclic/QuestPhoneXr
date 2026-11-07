package com.noyclic.questphonexr.runtime

enum class RuntimeCapabilityState(val label: String) {
    VERIFIED_IN_PROCESS("Проверено внутри QuestPhoneXR"),
    PARTIAL("Частично реализовано"),
    NOT_IMPLEMENTED("Пока не реализовано"),
    NOT_VERIFIED("Ещё не проверено")
}

data class RuntimeCapability(
    val id: String,
    val title: String,
    val state: RuntimeCapabilityState,
    val details: String
)

/**
 * Conservative capability inventory for the bundled EndXR implementation.
 * In-process verification is not a claim of third-party process or headset compatibility.
 */
object EndXrRuntimeReadiness {
    val capabilities: List<RuntimeCapability> = listOf(
        RuntimeCapability("loader-negotiation", "OpenXR loader negotiation",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "Bundled runtime negotiation and its loader-facing entrypoint table are covered by native tests."),
        RuntimeCapability("android-gles-session", "Android + OpenGL ES session",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "OpenXR GLES session setup, stereo view configuration and swapchain lifecycle are exercised in Sandbox."),
        RuntimeCapability("stereo-projection", "Stereo projection layer",
            RuntimeCapabilityState.VERIFIED_IN_PROCESS,
            "The Sandbox probe submits two eye views and verifies compositor output on its own EGL surface."),
        RuntimeCapability("head-orientation", "Head orientation",
            RuntimeCapabilityState.PARTIAL,
            "The phone rotation-vector sensor publishes a normalized quaternion through the protected Android IPC provider. QuestPhoneXR can grant one selected installed launcher app read access to the exact input URI; this is orientation-only, not positional 6DoF."),
        RuntimeCapability("position-tracking", "6DoF position tracking",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "The current OpenXR system properties explicitly advertise positionTracking=false."),
        RuntimeCapability("openxr-actions", "OpenXR Action / Input API",
            RuntimeCapabilityState.PARTIAL,
            "Action sets, action lifecycle, paths, binding suggestions, action spaces and xrSyncActions are implemented. Each sync captures one immutable left/right controller snapshot; Boolean/Float/Vector2 values and changedSinceLastSync are resolved from that snapshot. An unrelated game process needs an explicit package-scoped grant for the controller_state URI."),
        RuntimeCapability("controller-input", "Controller input and cross-process transport",
            RuntimeCapabilityState.PARTIAL,
            "A user-started camera foreground service publishes per-hand pinch trigger, natural power grip, approximate palm poses and 26-joint hand samples through one protected read-only snapshot. QuestPhoneXR can grant the exact controller_state URI to one selected installed launcher app; approval is bound to that package's signing-certificate fingerprint and can be revoked. Camera hands do not fabricate stick axes or physical face buttons, and real-game input remains unverified. POSE_INPUT / xrLocateSpace expose estimates with POSITION_VALID and ORIENTATION_VALID but never TRACKED flags; true 6DoF controllers and haptics remain unavailable."),
        RuntimeCapability("input-package-authorization", "Package-scoped input authorization",
            RuntimeCapabilityState.PARTIAL,
            "The UI lists installed third-party launcher apps and asks for confirmation before granting read-only access to only /controller_state. The stored approval is tied to the package signing-certificate fingerprint; the temporary URI grant is re-issued on the next QuestPhoneXR launch only while the same package and signer remain installed. The user can revoke access. The receiving game still needs to implement the EndXR input contract."),
        RuntimeCapability("openxr-hand-tracking", "OpenXR hand-tracking extension",
            RuntimeCapabilityState.PARTIAL,
            "The native runtime advertises XR_EXT_hand_tracking and implements tracker lifecycle plus xrLocateHandJointsEXT for 26 joints. MediaPipe's 21 landmarks are mapped to the OpenXR layout; several base joints are estimated. The Android provider transports per-hand joint arrays and the native runtime marks positions/orientations valid but never TRACKED. Continuous delivery to an external game and physical-device compatibility have not yet been verified."),
        RuntimeCapability("runtime-broker", "Runtime discovery from a separate diagnostic client",
            RuntimeCapabilityState.NOT_VERIFIED,
            "The separately signed diagnostic APK verifies service visibility, signature-protected input IPC and official Khronos loader initialization in CI. Active RuntimeBroker selection and successful xrCreateInstance on the Redmi remain unverified.")
    )

    val librarySummary: String =
        "Внутри Sandbox проверены loader negotiation, Android/GLES session и stereo projection layer. " +
            "OpenXR Actions API частично реализован; camera foreground service передаёт pinch/power-grip, приближённые palm poses и 26 суставов каждой руки через защищённый IPC. Базовый XR_EXT_hand_tracking реализован в нативном runtime, но внешний игровой процесс ещё не проверен. " +
            "Для отдельной игры можно вручную разрешить чтение только URI controller_state одному установленному приложению; разрешение привязано к подписи пакета и отзывается из QuestPhoneXR. " +
            "Это не означает, что любая игра уже поддерживает контракт ввода. Пози ладони доступны через POSE_INPUT/xrLocateSpace без TRACKED-флагов; Android gamepad events, настоящее 6DoF и haptics пока отсутствуют. " +
            "Выбор runtime через RuntimeBroker ещё не подтверждён на физическом телефоне."

    const val OPEN_XR_GAME_WARNING =
        "Перед запуском внешней игры учитывай ограничения: camera foreground service передаёт MediaPipe pinch/power-grip, приближённую позу ладони и 26 суставов через IPC; базовый XR_EXT_hand_tracking реализован, но внешний игровой процесс ещё не проверен. " +
            "В QuestPhoneXR можно вручную выбрать одно установленное приложение и выдать ему read-only доступ только к URI controller_state; разрешение привязано к подписи пакета и может быть отозвано. " +
            "Игра всё равно должна поддерживать этот IPC-контракт. Это не настоящие tracked controller poses и не 6DoF; Android gamepad capture и haptics пока отсутствуют, поэтому полноценная игра ещё не подтверждена."

    const val QUEST_GAME_WARNING =
        "Quest APK может зависеть от Meta/Oculus Runtime и системных сервисов гарнитуры. На обычном Android " +
            "их нет; нужен отдельный телефонный порт либо совместимая реализация этих API."

    fun warningsFor(xrTechnologies: Collection<String>): List<String> = buildList {
        if (xrTechnologies.any { it.equals("OpenXR", ignoreCase = true) }) add(OPEN_XR_GAME_WARNING)
        if (xrTechnologies.any { it.contains("Meta/Oculus", ignoreCase = true) }) add(QUEST_GAME_WARNING)
    }
}
