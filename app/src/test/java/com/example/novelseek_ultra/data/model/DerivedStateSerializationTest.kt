package com.example.novelseek_ultra.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedStateSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy records without stale field remain active`() {
        val entity = json.decodeFromString<EntityPayload>(
            """{"id":"e","entityType":"event","canonicalName":"Event"}""",
        )
        val growth = json.decodeFromString<CharacterGrowthEntry>(
            """{"id":"g","value":"Growth"}""",
        )
        val container = json.decodeFromString<ContainerEntry>(
            """{"id":"c","value":"Value"}""",
        )
        val evidence = json.decodeFromString<ChapterFactEvidenceBatch>(
            """{"id":"b","chapterId":"chapter","sourceHash":"hash"}""",
        )

        assertFalse(entity.isStale)
        assertFalse(growth.isStale)
        assertFalse(container.isStale)
        assertFalse(evidence.isStale)
        assertEquals("chapter_facts.v1", evidence.extractorContract)
        assertEquals("", evidence.extractionInputHash)
        assertTrue(evidence.facts.isEmpty())
    }

    @Test
    fun `stale markers survive persistence round trip`() {
        assertTrue(roundTrip(EntityPayload("e", "event", "Event", isStale = true)).isStale)
        assertTrue(roundTrip(CharacterGrowthEntry("g", "Growth", isStale = true)).isStale)
        assertTrue(roundTrip(ContainerEntry("c", "Value", isStale = true)).isStale)
        val evidence = ChapterFactEvidenceBatch(
            id = "b",
            chapterId = "chapter",
            sourceHash = "hash",
            facts = listOf(
                FactEvidence(
                    id = "fact",
                    factType = "event",
                    subject = "城门",
                    claim = "城门已关闭",
                    evidenceText = "守卫合上城门",
                ),
            ),
            isStale = true,
        )
        assertEquals(evidence, roundTrip(evidence))
    }

    private inline fun <reified T> roundTrip(value: T): T =
        json.decodeFromString(json.encodeToString(value))
}
