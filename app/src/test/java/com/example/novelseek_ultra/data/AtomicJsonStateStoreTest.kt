package com.example.novelseek_ultra.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicJsonStateStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json

    @Test
    fun `concurrent updates are serialized without lost writes or malformed json`() {
        val target = File(temporaryFolder.root, "app_state.json")
        val initial = JsonObject(mapOf("count" to JsonPrimitive(0)))
        val published = AtomicReference(initial)
        val store = AtomicJsonStateStore(target, initial, json)
        val workers = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(100)

        repeat(100) {
            workers.execute {
                try {
                    store.update(
                        transform = { current ->
                            JsonObject(current + ("count" to JsonPrimitive(current.getValue("count").jsonPrimitive.int + 1)))
                        },
                        publish = { published.set(it) },
                    )
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(done.await(20, TimeUnit.SECONDS))
        workers.shutdownNow()
        val disk = json.parseToJsonElement(AtomicTextFile.readText(target)).jsonObject
        assertEquals(100, published.get().getValue("count").jsonPrimitive.int)
        assertEquals(100, disk.getValue("count").jsonPrimitive.int)
    }

    @Test
    fun `malformed legacy state is quarantined before returning empty seed state`() {
        val target = temporaryFolder.newFile("app_state.json")
        target.writeText("{incomplete", Charsets.UTF_8)

        val loaded = AtomicJsonStateStore.loadOrEmpty(target, json)

        assertTrue(loaded.isEmpty())
        val quarantined = temporaryFolder.root.listFiles().orEmpty()
            .single { it.name.startsWith("app_state.json.corrupt-") }
        assertEquals("{incomplete", AtomicTextFile.readText(quarantined))
    }
}
