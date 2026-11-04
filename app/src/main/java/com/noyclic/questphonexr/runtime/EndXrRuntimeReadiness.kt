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
            "Orientation can be supplied to the runtime; this is not positional 6DoF tracking."),
        RuntimeCapability("position-tracking", "6DoF position tracking",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "The current OpenXR system properties explicitly advertise positionTracking=false."),
        RuntimeCapability("openxr-actions", "OpenXR Action / Input API",
            RuntimeCapabilityState.PARTIAL,
            "Action sets, action lifecycle, paths, binding suggestions, action spaces and xrSyncActions are implemented. State queries deliberately stay inactive until a separate-process controller input source is connected."),
        RuntimeCapability("controller-input", "Controller poses, buttons and haptics",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "Phone-side gamepad adapters exist, but their state is not exposed through OpenXR action and controller-pose APIs."),
        RuntimeCapability("openxr-hand-tracking", "OpenXR hand-tracking extension",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "MediaPipe tracking is app-internal; XR_EXT_hand_tracking is not advertised by the runtime."),
        RuntimeCapability("runtime-broker", "Discovery from a separate third-party app process",
            RuntimeCapabilityState.NOT_VERIFIED,
            "The Android runtime service is declared, but a separate client APK has not validated RuntimeBroker discovery and runtime loading.")
    )

    val librarySummary: String =
        "Внутри Sandbox проверены loader negotiation, Android/GLES session и stereo projection layer. " +
            "OpenXR Actions API частично реализован, но реальные состояния кнопок, стиков, позы контроллеров и haptics пока не поступают в сторонние процессы. " +
            "Также не хватает 6DoF position и XR hand tracking; выбор runtime через RuntimeBroker ещё не подтверждён на физическом телефоне."

    const val OPEN_XR_GAME_WARNING =
        "EndXR экспортирует базовый OpenXR Action / Input API, однако реальные состояния кнопок, триггеров, стиков и контроллерных поз пока не подключены к процессу игры. " +
            "Action queries сейчас возвращают neutral/inactive, а haptics не поддерживаются. Игры, требующие физический ввод или позы контроллеров, ещё не готовы к полноценной игре."

    const val QUEST_GAME_WARNING =
        "Quest APK может зависеть от Meta/Oculus Runtime и системных сервисов гарнитуры. На обычном Android " +
            "их нет; нужен отдельный телефонный порт либо совместимая реализация этих API."

    fun warningsFor(xrTechnologies: Collection<String>): List<String> = buildList {
        if (xrTechnologies.any { it.equals("OpenXR", ignoreCase = true) }) add(OPEN_XR_GAME_WARNING)
        if (xrTechnologies.any { it.contains("Meta/Oculus", ignoreCase = true) }) add(QUEST_GAME_WARNING)
    }
}
