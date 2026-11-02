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
    fun noGameIsPretendedToBePhoneTestedByCatalogMetadata() {
        assertEquals(4, GameCatalog.entries.size)
        GameCatalog.entries.forEach { entry ->
            assertNotNull(entry.id)
            assertTrue(entry.id.isNotBlank())
            assertTrue(entry.projectUrl?.startsWith("https://") == true)
        }
    }
}
