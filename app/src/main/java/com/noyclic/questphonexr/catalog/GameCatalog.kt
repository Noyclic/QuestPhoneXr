package com.noyclic.questphonexr.catalog

object GameCatalog {
    // The first three entries follow the user's requested test order.
    // Phone-oriented Android ports and Quest-only builds are explicitly separated.
    val entries: List<GameCatalogEntry> = listOf(
        GameCatalogEntry(
            id = "open-saber",
            title = "Open Saber",
            summary = "Первая цель. Есть отдельный Android Mobile Port v1.0.0; автор заявляет поддержку VR/SBS. Quest-сборка — отдельный проект.",
            portingPath = GamePortingPath.ANDROID_BUILD_LISTED,
            expectedLoad = ExpectedLoad.UNKNOWN,
            projectUrl = "https://github.com/mstritt/OpenSaberVR-Quest",
            secondaryUrl = "https://sasukidasprime.itch.io/open-saber-port",
            secondaryUrlLabel = "Android / SBS порт",
            compatibilityNotes = "Страница Mobile Port указывает touch controls и VR/SBS; hand-tracking обозначен как возможная интеграция, а не подтверждённая функция. Это наиболее подходящая первая сборка для проверки на телефоне. Quest README отдельно указывает встроенный Oculus SDK — эту сборку нельзя приравнивать к Android-порту."
        ),
        GameCatalogEntry(
            id = "granny-vr",
            title = "Granny: Quest",
            summary = "Вторая цель. На странице проекта есть отдельная Quest APK-сборка; это не универсальная Android-версия.",
            portingPath = GamePortingPath.QUEST_BUILD_NEEDS_ADAPTATION,
            expectedLoad = ExpectedLoad.UNKNOWN,
            projectUrl = "https://gogozooom.itch.io/granny-vr",
            secondaryUrl = "https://sidequestvr.com/app/3585",
            secondaryUrlLabel = "SideQuest",
            compatibilityNotes = "Заявленное управление Quest-версии: стики, меню, Grip и Trigger. Это Quest-контроллерная схема; EndXR пока не предоставляет OpenXR Action API и controller poses. Наличие APK не означает совместимость с Redmi."
        ),
        GameCatalogEntry(
            id = "baldis-basics-classic-vr",
            title = "Baldi's Basics Quest",
            summary = "Третья цель. Standalone VR-порт Baldi's Basics Classic с 6DoF и отдельной Android-сборкой для Quest.",
            portingPath = GamePortingPath.QUEST_BUILD_NEEDS_ADAPTATION,
            expectedLoad = ExpectedLoad.UNKNOWN,
            projectUrl = "https://github.com/hanabira-mitsu/bbvr",
            secondaryUrl = "https://github.com/hanabira-mitsu/bbvr/releases/latest",
            secondaryUrlLabel = "Quest APK / Releases",
            compatibilityNotes = "Релиз v1.0.0 описывает Android APK для Quest и сообщает, что эта сборка тестировалась на Quest 3. PCVR-сборка заявлена как OpenXR. Совместимость с обычным Android-телефоном не заявлена; потребуется отдельная проверка Meta-зависимостей и ввода."
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
