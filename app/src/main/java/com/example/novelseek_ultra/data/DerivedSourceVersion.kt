package com.example.novelseek_ultra.data

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Immutable identity of the exact chapter material used to start an asynchronous derived task.
 * The full prose is intentionally not retained: equality is based on a length-delimited digest.
 */
internal data class ChapterSourceVersion(
    val chapterId: String,
    val title: String,
    val orderIndex: Int,
    val textHash: String,
)

/** Identity of all chapter material (plus project title/description) used by a whole-book task. */
internal data class ProjectSourceVersion(
    val projectId: String,
    val projectTitle: String,
    val projectDescription: String,
    val signature: String,
)

/** Pure-JVM fingerprint helpers shared by production guards and unit tests. */
internal object DerivedSourceFingerprint {
    fun chapter(chapterId: String, title: String, orderIndex: Int, effectiveText: String) =
        ChapterSourceVersion(
            chapterId = chapterId,
            title = title,
            orderIndex = orderIndex,
            textHash = digest(listOf(effectiveText)),
        )

    fun project(
        projectId: String,
        projectTitle: String,
        projectDescription: String,
        chapters: List<ChapterSourceVersion>,
    ) = ProjectSourceVersion(
        projectId = projectId,
        projectTitle = projectTitle,
        projectDescription = projectDescription,
        signature = digest(buildList {
            add(projectId)
            add(projectTitle)
            add(projectDescription)
            chapters.forEach { chapter ->
                add(chapter.chapterId)
                add(chapter.title)
                add(chapter.orderIndex.toString())
                add(chapter.textHash)
            }
        }),
    )

    fun collection(parts: List<String>): String = digest(parts)

    fun characterIdentities(characters: List<Pair<String, String>>): String =
        digest(characters.flatMap { (id, name) -> listOf(id, name) })

    private fun digest(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            md.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
