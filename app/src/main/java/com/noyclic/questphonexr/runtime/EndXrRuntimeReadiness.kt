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
            "The phone rotation-vector sensor publishes a normalized quaternion through the protected Android IPC provider. Only the same-signed companion probe can currently read it; arbitrary third-party games are not authorized. It is orientation-only, not positional 6DoF."),
        RuntimeCapability("position-tracking", "6DoF position tracking",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "The current OpenXR system properties explicitly advertise positionTracking=false."),
        RuntimeCapability("openxr-actions", "OpenXR Action / Input API",
            RuntimeCapabilityState.PARTIAL,
            "Action sets, action lifecycle, paths, binding suggestions, action spaces and xrSyncActions are implemented. Each sync captures one immutable left/right controller snapshot; Boolean/Float/Vector2 values and changedSinceLastSync are resolved from that snapshot. The protected read-only IPC provider currently authorizes only the same-signed companion probe, not arbitrary game APKs."),
        RuntimeCapability("controller-input", "Controller input and cross-process transport",
            RuntimeCapabilityState.PARTIAL,
            "A user-started camera foreground service publishes MediaPipe pinch/grip and approximate left/right palm poses through the protected read-only IPC provider. The current signature permission allows the paired diagnostic client to read it, but blocks unrelated third-party game APKs until a per-package authorization flow is implemented. OpenXR POSE_INPUT / xrLocateSpace expose these estimates with POSITION_VALID and ORIENTATION_VALID but never TRACKED flags. Android gamepad key/motion events are not globally captured when another app owns focus; true controller tracking and haptic transport are unavailable."),
        RuntimeCapability("openxr-hand-tracking", "OpenXR hand-tracking extension",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "MediaPipe tracking is app-internal; XR_EXT_hand_tracking is not advertised by the runtime."),
        RuntimeCapability("runtime-broker", "Runtime discovery from a separate diagnostic client",
            RuntimeCapabilityState.NOT_VERIFIED,
            "The separately signed diagnostic APK verifies service visibility, signature-protected input IPC and official Khronos loader initialization in CI. Active RuntimeBroker selection and successful xrCreateInstance on the Redmi remain unverified.")
    )

    val librarySummary: String =
        "Внутри Sandbox проверены loader negotiation, Android/GLES session и stereo projection layer. " +
            "OpenXR Actions API частично реализован; camera foreground service передаёт pinch/grip, приближённые palm grip poses и ориентацию головы через защищённый IPC. Сейчас снимки может читать только companion probe с той же подписью — сторонние игровые APK ещё не авторизованы. Пози ладони доступны через POSE_INPUT/xrLocateSpace без TRACKED-флагов; Android gamepad events, настоящее 6DoF и haptics пока отсутствуют. " +
            "Также не хватает 6DoF position и XR hand tracking; выбор runtime через RuntimeBroker ещё не подтверждён на физическом телефоне."

    const val OPEN_XR_GAME_WARNING =
        "Перед запуском внешней игры важно учитывать ограничения: camera foreground service передаёт MediaPipe pinch/grip, приближённую позу ладони и ориентацию головы через IPC, но доступ к этому IPC пока разрешён только companion probe с подписью приложения. Сторонние игровые APK пока не могут получить эти данные; сначала нужен пользовательский доступ для конкретного пакета. Это не настоящие tracked controller poses и не 6DoF; Android gamepad capture и haptics пока отсутствуют, поэтому полноценная игра ещё не подтверждена."

    const val QUEST_GAME_WARNING =
        "Quest APK может зависеть от Meta/Oculus Runtime и системных сервисов гарнитуры. На обычном Android " +
            "их нет; нужен отдельный телефонный порт либо совместимая реализация этих API."

    fun warningsFor(xrTechnologies: Collection<String>): List<String> = buildList {
        if (xrTechnologies.any { it.equals("OpenXR", ignoreCase = true) }) add(OPEN_XR_GAME_WARNING)
        if (xrTechnologies.any { it.contains("Meta/Oculus", ignoreCase = true) }) add(QUEST_GAME_WARNING)
    }
}
