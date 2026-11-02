package com.noyclic.questphonexr.catalog

object GameCatalog {
    val entries: List<GameCatalogEntry> = listOf(
        GameCatalogEntry(
            id = "baldis-basics-classic-vr",
            title = "Baldi's Basics Classic VR",
            summary = "Отдельный VR-порт классической Baldi's Basics с проектом и Quest-сборкой.",
            portingPath = GamePortingPath.QUEST_BUILD_NEEDS_ADAPTATION,
            expectedLoad = ExpectedLoad.UNKNOWN,
            projectUrl = "https://github.com/hanabira-mitsu/bbvr",
            compatibilityNotes = "Наличие Quest APK не означает, что он запустится на обычном Android-телефоне. Нужна адаптация рендеринга, трекинга и ввода."
        ),
        GameCatalogEntry(
            id = "open-saber",
            title = "Open Saber",
            summary = "VR-клон ритм-игры с отдельным Quest-проектом; также найден отдельный Android/SBS-порт.",
            portingPath = GamePortingPath.ANDROID_BUILD_LISTED,
            expectedLoad = ExpectedLoad.MEDIUM,
            projectUrl = "https://github.com/mstritt/OpenSaberVR-Quest",
            secondaryUrl = "https://sasukidasprime.itch.io/open-saber-port",
            secondaryUrlLabel = "Android-порт",
            compatibilityNotes = "Android-порт и Quest-версия — разные сборки. Ни одна из них пока не проверена на Redmi Note 13 4G."
        ),
        GameCatalogEntry(
            id = "granny-vr",
            title = "Granny VR",
            summary = "Неофициальный VR-проект Granny; автор указывает Android- и Quest-версии.",
            portingPath = GamePortingPath.ANDROID_BUILD_LISTED,
            expectedLoad = ExpectedLoad.UNKNOWN,
            projectUrl = "https://gogozooom.itch.io/granny-vr",
            compatibilityNotes = "Страница проекта указывает Android и Quest, но это не подтверждает работу конкретной сборки в VR Shinecon или на Redmi."
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
