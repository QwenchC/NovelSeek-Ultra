package com.example.novelseek_ultra.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationContextFingerprintTest {
    @Test
    fun `capture is deterministic across material order and remains length delimited`() {
        val materials = listOf(
            GenerationContextMaterial("world", listOf("山门", "灵脉")),
            GenerationContextMaterial("characters", listOf("林川", "沈月")),
        )

        val captured = GenerationContextFingerprint.capture("chapter_prompt", materials)
        val reordered = GenerationContextFingerprint.capture("chapter_prompt", materials.reversed())
        val splitOne = GenerationContextFingerprint.capture(
            "chapter_prompt",
            listOf(GenerationContextMaterial("world", listOf("ab", "c"))),
        )
        val splitTwo = GenerationContextFingerprint.capture(
            "chapter_prompt",
            listOf(GenerationContextMaterial("world", listOf("a", "bc"))),
        )

        assertEquals(captured, reordered)
        assertEquals(listOf("characters", "world"), captured.entries.map { it.key })
        assertTrue(captured.isSelfConsistent())
        assertNotEquals(splitOne.fingerprint, splitTwo.fingerprint)
    }

    @Test
    fun `manifest consistency detects tampering but ignores persisted entry order`() {
        val manifest = GenerationContextFingerprint.capture(
            "chapter_prompt",
            listOf(
                GenerationContextMaterial("world", listOf("旧世界")),
                GenerationContextMaterial("timeline", listOf("第一日")),
            ),
        )

        assertTrue(manifest.copy(entries = manifest.entries.reversed()).isSelfConsistent())
        assertFalse(manifest.copy(fingerprint = "tampered").isSelfConsistent())
        assertFalse(
            manifest.copy(
                entries = manifest.entries.mapIndexed { index, entry ->
                    if (index == 0) entry.copy(itemCount = entry.itemCount + 1) else entry
                },
            ).isSelfConsistent(),
        )
        assertFalse(
            manifest.copy(entries = manifest.entries + manifest.entries.first()).isSelfConsistent(),
        )
    }

    @Test
    fun `changed keys report additions removals and content drift in stable order`() {
        val expected = GenerationContextFingerprint.capture(
            "chapter_prompt",
            listOf(
                GenerationContextMaterial("characters", listOf("林川")),
                GenerationContextMaterial("world", listOf("旧世界")),
            ),
        )
        val current = GenerationContextFingerprint.capture(
            "chapter_prompt",
            listOf(
                GenerationContextMaterial("characters", listOf("林川", "沈月")),
                GenerationContextMaterial("timeline", listOf("第一日")),
            ),
        )

        assertEquals(
            listOf("characters", "timeline", "world"),
            GenerationContextFingerprint.changedKeys(expected, current),
        )
        assertEquals(
            listOf("characters", "world"),
            GenerationContextFingerprint.changedKeys(expected, null),
        )
        assertTrue(GenerationContextFingerprint.changedKeys(expected, expected).isEmpty())
    }

    @Test
    fun `capture rejects ambiguous manifest structure and v2 source binds context`() {
        assertThrows(IllegalArgumentException::class.java) {
            GenerationContextFingerprint.capture("", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationContextFingerprint.capture(
                "chapter_prompt",
                listOf(
                    GenerationContextMaterial("world", emptyList()),
                    GenerationContextMaterial("world", listOf("重复")),
                ),
            )
        }

        val first = GenerationContextFingerprint.capture(
            "chapter_prompt",
            listOf(GenerationContextMaterial("world", listOf("旧世界"))),
        )
        val second = GenerationContextFingerprint.capture(
            "chapter_prompt",
            listOf(GenerationContextMaterial("world", listOf("新世界"))),
        )
        val legacy = GenerationSourceFingerprint.combine("plan", "body")
        val bound = GenerationSourceFingerprint.combineWithContext("plan", "body", first.fingerprint)

        assertNotEquals(legacy, bound)
        assertNotEquals(
            bound,
            GenerationSourceFingerprint.combineWithContext("plan", "body", second.fingerprint),
        )
        assertEquals(
            bound,
            GenerationSourceFingerprint.combineWithContext("plan", "body", first.fingerprint),
        )
    }
}
