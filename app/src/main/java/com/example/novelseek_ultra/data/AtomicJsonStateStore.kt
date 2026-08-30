package com.example.novelseek_ultra.data

import java.io.File
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Serializes read-transform-write-publish for the app's single JSON state file. */
internal class AtomicJsonStateStore(
    private val target: File,
    initial: JsonObject,
    private val json: Json,
) {
    private val writeLock = Any()

    @Volatile
    private var committed = initial

    /**
     * The transform is pure and runs once while holding [writeLock]. Disk commit happens before
     * publication, so readers never observe a state that cannot be recovered after process death.
     */
    fun update(transform: (JsonObject) -> JsonObject, publish: (JsonObject) -> Unit) {
        synchronized(writeLock) {
            val next = transform(committed)
            AtomicTextFile.writeText(
                target,
                json.encodeToString(JsonObject.serializer(), next),
            )
            committed = next
            publish(next)
        }
    }

    companion object {
        /**
         * AtomicTextFile first repairs an interrupted replace. If legacy data is still malformed,
         * preserve its exact bytes in a quarantine file before first-run seeding replaces it.
         */
        fun loadOrEmpty(target: File, json: Json): JsonObject {
            if (!AtomicTextFile.exists(target)) return JsonObject(emptyMap())
            val raw = AtomicTextFile.readText(target)
            return try {
                json.parseToJsonElement(raw).jsonObject
            } catch (error: Exception) {
                if (error !is SerializationException && error !is IllegalStateException) throw error
                val quarantine = File(
                    target.parentFile,
                    "${target.name}.corrupt-${System.currentTimeMillis()}-${System.nanoTime()}",
                )
                AtomicTextFile.writeText(quarantine, raw)
                JsonObject(emptyMap())
            }
        }
    }
}
