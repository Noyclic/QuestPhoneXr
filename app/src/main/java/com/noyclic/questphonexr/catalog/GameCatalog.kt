package com.noyclic.questphonexr.catalog

object GameCatalog {
    // User's first real-game targets are kept at the top in the agreed test order.
    val entries: List<GameCatalogEntry> = listOf(
        GameCatalogEntry(
            id = "open-saber",
            title = "Open Saber",
            summary = "Первая цель тестирования. Есть отдельный Quest-проект и отдельный Android/SBS-порт.",
            portingPath = GamePortingPath.ANDROID_BUILD_LISTED,
            expectedLoad = ExpectedLoad.MEDIUM,
            projectUrl = "https://github.com/mstritt/OpenSaberVR-Quest",
            secondaryUrl = "https://sasukidasprime.itch.io/open-saber-port",
            secondaryUrlLabel = "Android-порт",
            compatibilityNotes = "Сначала проверяем Android/SBS-сборку на Redmi Note 13 4G. Quest APK — отдельная сборка и не считается совместимой автоматически."
        ),
        GameCatalogEntry(
            id = "granny-vr",
            title = "Granny: Quest / Granny VR",
            summary = "Вторая цель. Неофициальный VR-проект Granny с заявленными Android- и Quest-вариантами.",
            portingPath = GamePortingPath.ANDROID_BUILD_LISTED,
            expectedLoad = ExpectedLoad.UNKNOWN,
            projectUrl = "https://gogozooom.itch.io/granny-vr",
            compatibilityNotes = "Нужно отдельно определить точную сборку и проверить её манифест, ABI, запуск, стереорендеринг и ввод. Страница проекта сама по себе не подтверждает совместимость с Redmi."
        ),
        GameCatalogEntry(
            id = "baldis-basics-classic-vr",
            title = "Baldi's Basics Quest / Classic VR",
            summary = "Третья цель. Отдельный VR-порт классической Baldi's Basics с Quest-сборкой.",
            portingPath = GamePortingPath.QUEST_BUILD_NEEDS_ADAPTATION,
            expectedLoad = ExpectedLoad.UNKNOWN,
            projectUrl = "https://github.com/hanabira-mitsu/bbvr",
            compatibilityNotes = "Quest APK может зависеть от Meta XR Runtime и сервисов гарнитуры. Сначала проверяем APK-метаданные; для телефона может потребоваться отдельный порт."
        ),
        GameCatalogEntry(
            id = "questcraft",
            title = "QuestCraft",
            summary = "Standalone VR-порт Minecraft Java Edition для совместимых VR-гарнитур.",
            portingPath = GamePortingPath.SOURCE_PORT_REQUIRED,
            expectedLoad = ExpectedLoad.HIGH,
            projectUrl = "https://github.com/QuestCraftPlusPlus/QuestCraft",
            compatibilityNotes = "Проект рассчитан на совместимые VR-гарнитуры. Для телефона понадобится отдельная адаптация Java runtime, графики и VR-ввода; нагрузка предварительно оценивается как высокая."
        )
    )

    fun findById(id: String): GameCatalogEntry? = entries.firstOrNull { it.id == id }
}
