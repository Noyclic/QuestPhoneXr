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
            "Action sets, action lifecycle, paths, binding suggestions, action spaces and xrSyncActions are implemented. Boolean/Float/Vector2 values now resolve from left/right Sandbox input samples inside the same process; third-party process input is not connected."),
        RuntimeCapability("controller-input", "Controller input and cross-process transport",
            RuntimeCapabilityState.PARTIAL,
            "MediaPipe pinch/grip and Android gamepad samples are published to a read-only Android IPC provider. EndXR polls that provider from xrSyncActions, and CI checks access from a separate package. The producer is still the Sandbox Activity and clears input onPause, so continuous input while an external game is foreground is not implemented. Controller poses and haptic transport are also unavailable."),
        RuntimeCapability("openxr-hand-tracking", "OpenXR hand-tracking extension",
            RuntimeCapabilityState.NOT_IMPLEMENTED,
            "MediaPipe tracking is app-internal; XR_EXT_hand_tracking is not advertised by the runtime."),
        RuntimeCapability("runtime-broker", "Discovery from a separate third-party app process",
            RuntimeCapabilityState.NOT_VERIFIED,
            "A separate client APK can discover the exported service and library path in CI. Active RuntimeBroker selection and successful xrCreateInstance on the Redmi remain unverified.")
    )

    val librarySummary: String =
        "Внутри Sandbox проверены loader negotiation, Android/GLES session и stereo projection layer. " +
            "OpenXR Actions API частично реализован: отдельный процесс уже может читать IPC-снимок контроллеров, но источник данных пока работает только в Sandbox и очищается при его паузе; постоянная передача во время внешней игры ещё не готова. Позы контроллеров и haptics также отсутствуют. " +
            "Также не хватает 6DoF position и XR hand tracking; выбор runtime через RuntimeBroker ещё не подтверждён на физическом телефоне."

    const val OPEN_XR_GAME_WARNING =
        "EndXR экспортирует базовый OpenXR Action / Input API, а ввод Sandbox уже отображается в Action states внутри QuestPhoneXR. " +
            "Отдельный игровой процесс может прочитать снимок через IPC, но источник ввода пока останавливается и очищает данные при уходе Sandbox в фон. Для непрерывного управления нужна отдельная foreground-служба ввода. Controller poses и haptics также не поддерживаются, поэтому полноценная игра пока не подтверждена."

    const val QUEST_GAME_WARNING =
        "Quest APK может зависеть от Meta/Oculus Runtime и системных сервисов гарнитуры. На обычном Android " +
            "их нет; нужен отдельный телефонный порт либо совместимая реализация этих API."

    fun warningsFor(xrTechnologies: Collection<String>): List<String> = buildList {
        if (xrTechnologies.any { it.equals("OpenXR", ignoreCase = true) }) add(OPEN_XR_GAME_WARNING)
        if (xrTechnologies.any { it.contains("Meta/Oculus", ignoreCase = true) }) add(QUEST_GAME_WARNING)
    }
}
