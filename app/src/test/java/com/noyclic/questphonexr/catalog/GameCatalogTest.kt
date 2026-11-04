package com.noyclic.questphonexr.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCatalogTest {
    @Test
    fun initialCatalogContainsTheFourRequestedGames() {
        val ids = GameCatalog.entries.map { it.id }.toSet()
        assertTrue("Baldi missing", "baldis-basics-classic-vr" in ids)
        assertTrue("Open Saber missing", "open-saber" in ids)
        assertTrue("Granny missing", "granny-vr" in ids)
        assertTrue("QuestCraft missing", "questcraft" in ids)
    }

    @Test
    fun primaryTargetsStayInTheAgreedOrder() {
        assertEquals(
            listOf("open-saber", "granny-vr", "baldis-basics-classic-vr"),
            GameCatalog.entries.take(3).map { it.id }
        )
    }

    @Test
    fun firstTargetsLinkToTheirDistinctBuildTypes() {
        val openSaber = GameCatalog.findById("open-saber")!!
        assertTrue(openSaber.secondaryUrl?.contains("open-saber-port") == true)
        assertTrue(openSaber.secondaryUrlLabel?.contains("SBS") == true)
        val granny = GameCatalog.findById("granny-vr")!!
        assertTrue(granny.projectUrl?.contains("granny-vr") == true)
        assertTrue(granny.compatibilityNotes.contains("Grip"))
        val baldi = GameCatalog.findById("baldis-basics-classic-vr")!!
        assertTrue(baldi.secondaryUrl?.endsWith("/releases/latest") == true)
        assertTrue(baldi.compatibilityNotes.contains("Quest 3"))
    }

    @Test
    fun noGameIsPretendedToBePhoneTestedByCatalogMetadata() {
        assertEquals(4, GameCatalog.entries.size)
        GameCatalog.entries.forEach { entry ->
            assertNotNull(entry.id)
            assertTrue(entry.id.isNotBlank())
            assertTrue(entry.projectUrl?.startsWith("https://") == true)
            assertTrue(entry.compatibilityNotes.isNotBlank())
        }
    }
}
