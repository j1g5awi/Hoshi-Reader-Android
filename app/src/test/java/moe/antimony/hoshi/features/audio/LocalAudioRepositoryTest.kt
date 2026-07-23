package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalAudioRepositoryTest {
    @Test
    fun hasNoDatabaseInitially() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-test").toFile()
        val repository = LocalAudioRepository(filesDir)

        assertNull(repository.databaseSizeBytes())
        assertFalse(repository.canOpenDatabase())
    }

    @Test
    fun findAudioReturnsNullWhenNoDatabase() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-test").toFile()
        val repository = LocalAudioRepository(filesDir)

        assertNull(repository.findAudio("test", ""))
    }

    @Test
    fun loadAudioReturnsNullWhenNoDatabase() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-test").toFile()
        val repository = LocalAudioRepository(filesDir)

        assertNull(repository.loadAudio(LocalAudioFile(source = "test", file = "test.mp3")))
    }

    @Test
    fun ensureSourceConfigReturnsEmptyWithoutDatabase() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-test").toFile()
        val repository = LocalAudioRepository(filesDir)

        val config = repository.ensureSourceConfig(reset = true)
        assertTrue(config.sourceOrder.isEmpty())
    }

    @Test
    fun deleteDatabaseDoesNotCrash() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-test").toFile()
        val repository = LocalAudioRepository(filesDir)

        repository.deleteDatabase()
        assertNull(repository.databaseSizeBytes())
    }

    @Test
    fun updateSourceOrderWorksWithoutDatabase() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-test").toFile()
        val repository = LocalAudioRepository(filesDir)

        val result = repository.updateSourceOrder(listOf("source1", "source2"))
        assertNotNull(result)
        assertTrue(result.sourceOrder.isEmpty())
    }

    @Test
    fun initSetsSingleton() {
        val filesDir = Files.createTempDirectory("hoshi-local-audio-singleton").toFile()
        val repository = LocalAudioRepository(filesDir)

        val singleton = LocalAudioRepository::class.java.getDeclaredField("singleton").also { it.isAccessible = true }
        val stored = singleton.get(null)
        assertNotNull(stored)
        assertTrue(stored === repository)
    }
}
