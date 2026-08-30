package com.example.novelseek_ultra.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedSourceVersionTest {
    @Test
    fun `chapter version changes for every prompt-relevant source field`() {
        val baseline = DerivedSourceFingerprint.chapter("ch-1", "标题", 1, "正文 v1")

        assertNotEquals(baseline, DerivedSourceFingerprint.chapter("ch-1", "新标题", 1, "正文 v1"))
        assertNotEquals(baseline, DerivedSourceFingerprint.chapter("ch-1", "标题", 2, "正文 v1"))
        assertNotEquals(baseline, DerivedSourceFingerprint.chapter("ch-1", "标题", 1, "正文 v2"))
        assertEquals(64, baseline.textHash.length)
    }

    @Test
    fun `project signature changes when any chapter source changes or is added`() {
        val first = DerivedSourceFingerprint.chapter("ch-1", "第一章", 1, "内容一")
        val second = DerivedSourceFingerprint.chapter("ch-2", "第二章", 2, "内容二")
        val baseline = DerivedSourceFingerprint.project("p", "书名", "简介", listOf(first))

        assertEquals("书名", baseline.projectTitle)
        assertEquals("简介", baseline.projectDescription)
        assertNotEquals(baseline, DerivedSourceFingerprint.project("p", "新书名", "简介", listOf(first)))
        assertNotEquals(baseline, DerivedSourceFingerprint.project("p", "书名", "简介", listOf(first, second)))
        assertNotEquals(
            baseline,
            DerivedSourceFingerprint.project(
                "p", "书名", "简介",
                listOf(DerivedSourceFingerprint.chapter("ch-1", "第一章", 1, "内容一 v2")),
            ),
        )
    }

    @Test
    fun `collection fingerprint is length delimited and deterministic`() {
        val first = DerivedSourceFingerprint.collection(listOf("ab", "c"))
        val repeated = DerivedSourceFingerprint.collection(listOf("ab", "c"))
        val ambiguousWithoutLengths = DerivedSourceFingerprint.collection(listOf("a", "bc"))

        assertEquals(first, repeated)
        assertNotEquals(first, ambiguousWithoutLengths)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `character identity signature binds ids names additions and deletions`() {
        val baseline = DerivedSourceFingerprint.characterIdentities(
            listOf("c1" to "林舟", "c2" to "苏晚"),
        )

        assertNotEquals(
            baseline,
            DerivedSourceFingerprint.characterIdentities(listOf("c1" to "林舟", "c2" to "苏宁")),
        )
        assertNotEquals(
            baseline,
            DerivedSourceFingerprint.characterIdentities(listOf("c1" to "林舟")),
        )
        assertNotEquals(
            baseline,
            DerivedSourceFingerprint.characterIdentities(
                listOf("c1" to "林舟", "c2" to "苏晚", "c3" to "周屿"),
            ),
        )
    }
}
