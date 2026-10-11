package com.utaloom.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class JsonStoreTest {
    @Test
    fun flushPersistsTheLiveValueWithAtomicReplacement() {
        val directory = Files.createTempDirectory("utaloom-store-test").toFile()
        val file = directory.resolve("settings.json")
        val store = JsonStore(file, ::Settings, Settings.serializer())
        store.update { it.copy(volume = 25) }
        store.flush()
        assertEquals(25, Json.decodeFromString(Settings.serializer(), file.readText()).volume)
        store.update { it.copy(volume = 60) }
        store.flush()
        assertEquals(60, Json.decodeFromString(Settings.serializer(), file.readText()).volume)
        assertFalse(directory.resolve("settings.json.tmp").exists())
        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        }
    }

    @Test
    fun flushReportsDiskFailureInsteadOfPretendingRestoreSucceeded() {
        val directory = Files.createTempDirectory("utaloom-store-failure").toFile()
        val file = directory.resolve("library.json")
        val store = JsonStore(file, ::LibraryState, LibraryState.serializer())
        file.mkdir()
        file.resolve("keep.txt").writeText("Do not replace existing data")
        assertTrue(runCatching { store.flush() }.isFailure)
        assertEquals("Do not replace existing data", file.resolve("keep.txt").readText())
    }

    @Test
    fun corruptDataIsKeptForRecovery() {
        val directory = Files.createTempDirectory("utaloom-store-recovery").toFile()
        val file = directory.resolve("queue.json")
        file.writeText("{truncated")
        val store = JsonStore(file, ::PersistedQueue, PersistedQueue.serializer())
        assertEquals(PersistedQueue(), store.value)
        assertEquals("{truncated", directory.listFiles()!!.single { ".corrupt-" in it.name }.readText())
        assertEquals("{truncated", file.readText())
    }
}
