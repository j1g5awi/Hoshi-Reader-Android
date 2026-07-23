package moe.antimony.hoshi.features.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.di.FilesDir

@Singleton
class LocalAudioRepository @Inject constructor(
    @param:FilesDir private val filesDir: File,
    @param:ApplicationContext private val context: Context,
) {
    private val sourceConfigFile: File
        get() = File(filesDir, AudioSettings.LocalAudioSourceConfigPath)
    private val databaseRefFile: File
        get() = File(filesDir, "Audio/android_db_ref.txt")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val sourceConfigCache: LocalAudioSourceConfigCache =
        synchronized(SourceConfigCaches) {
            SourceConfigCaches.getOrPut(sourceConfigFile.absolutePath) {
                LocalAudioSourceConfigCache {
                    loadSourceConfig(reset = false)
                }
            }
        }
    private var nativeDb: WordAudioDatabase? = null

    init {
        singleton = this
        tryReopenFromRef()
    }

    fun deleteDatabase() {
        nativeDb?.close()
        nativeDb = null
        sourceConfigFile.delete()
        databaseRefFile.delete()
        sourceConfigCache.clear()
    }

    fun databaseSizeBytes(): Long? {
        ensureDbReady()
        return if (nativeDb != null) 1L else null
    }

    fun canOpenDatabase(): Boolean {
        ensureDbReady()
        val ndb = nativeDb
        if (ndb != null) return ndb.testConnection()
        return false
    }

    fun importDatabase(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Long {
        contentResolver.validateImportFile(uri, ImportFileType.LocalAudioDatabase)
        nativeDb?.close()
        nativeDb = null
        val ndb = WordAudioDatabase(contentResolver)
        if (!ndb.open(uri)) error(ndb.lastError ?: "Unable to open audio database")
        nativeDb = ndb
        saveDatabaseRef(uri.toString())
        ensureSourceConfig(reset = true)
        return contentResolver.sizeBytes(uri) ?: 0L
    }

    fun ensureSourceConfig(reset: Boolean = false): LocalAudioSourceConfig {
        ensureDbReady()
        return if (reset) {
            sourceConfigCache.clear()
            loadSourceConfig(reset = true).also(sourceConfigCache::replace)
        } else {
            sourceConfigCache.get()
        }
    }

    private fun loadSourceConfig(reset: Boolean): LocalAudioSourceConfig {
        if (!reset) {
            readSourceConfig()
                ?.takeIf { it.version == LocalAudioSourceConfig.CurrentVersion && it.sourceOrder.isNotEmpty() }
                ?.let { return it }
        }
        val availableSources = audioSourcesFromDatabase().toSet()
        if (availableSources.isEmpty()) {
            sourceConfigFile.delete()
            return LocalAudioSourceConfig()
        }
        val current = if (reset) null else readSourceConfig()
        val repaired = if (current?.version == LocalAudioSourceConfig.CurrentVersion) {
            current.repair(availableSources)
        } else {
            LocalAudioSourceConfig.defaultFor(availableSources)
        }
        if (current != repaired) {
            writeSourceConfig(repaired)
        }
        return repaired
    }

    fun updateSourceOrder(sourceOrder: List<String>): LocalAudioSourceConfig {
        ensureDbReady()
        val availableSources = ensureSourceConfig().sourceOrder.toSet()
        if (availableSources.isEmpty()) {
            sourceConfigFile.delete()
            sourceConfigCache.clear()
            return LocalAudioSourceConfig()
        }
        val next = LocalAudioSourceConfig(sourceOrder = sourceOrder).repair(availableSources)
        writeSourceConfig(next)
        sourceConfigCache.replace(next)
        return next
    }

    fun findAudio(term: String, reading: String): LocalAudioEntry? {
        ensureDbReady()
        val normalizedReading = LocalAudioResolver.katakanaToHiragana(reading)
        val ndb = nativeDb ?: return null
        val sourceOrder = ensureSourceConfig().sourceOrder
        val entries = ndb.findEntries(term, normalizedReading)
        if (entries.isEmpty()) return null
        val rows = entries.map { LocalAudioEntry(it.source, it.expression, it.reading, it.file) }
        return LocalAudioResolver.resolve(term, normalizedReading, rows, sourceOrder)
    }

    fun audioSourcesFromDatabase(): List<String> {
        ensureDbReady()
        val ndb = nativeDb ?: return emptyList()
        return LocalAudioSourceOrder.defaultOrder(ndb.getSources())
    }

    fun loadAudio(file: LocalAudioFile): ByteArray? {
        ensureDbReady()
        val ndb = nativeDb ?: return null
        return ndb.getAudioData(file.file, file.source)
    }

    private fun ensureDbReady() {
        if (nativeDb != null) return
        val ref = readDatabaseRef() ?: return
        reopenFromRef(ref)
    }

    private fun tryReopenFromRef() {
        val ref = readDatabaseRef() ?: return
        reopenFromRef(ref)
    }

    private fun reopenFromRef(ref: String) {
        val uri = runCatching { Uri.parse(ref) }.getOrNull() ?: return
        val ndb = WordAudioDatabase(context.contentResolver)
        if (ndb.open(uri)) {
            nativeDb = ndb
            Log.i("HoshiLocalAudio", "Reopened audio database from saved reference")
        }
    }

    private fun saveDatabaseRef(uri: String) {
        databaseRefFile.parentFile?.mkdirs()
        databaseRefFile.writeText(uri)
    }

    private fun readDatabaseRef(): String? {
        if (!databaseRefFile.isFile) return null
        return runCatching { databaseRefFile.readText().trim().ifBlank { null } }.getOrNull()
    }

    private fun readSourceConfig(): LocalAudioSourceConfig? =
        runCatching {
            sourceConfigFile
                .takeIf { it.isFile }
                ?.readText()
                ?.let { json.decodeFromString<LocalAudioSourceConfig>(it) }
        }.onFailure { error ->
            Log.w("HoshiLocalAudio", "Unable to read local audio source config.", error)
        }.getOrNull()

    private fun writeSourceConfig(config: LocalAudioSourceConfig) {
        sourceConfigFile.parentFile?.mkdirs()
        sourceConfigFile.writeText(json.encodeToString(config))
    }

    private fun ContentResolver.sizeBytes(uri: Uri): Long? {
        openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }?.let { return it }
        }
        return query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0 }
        }
    }

    companion object {
        @Volatile
        private var singleton: LocalAudioRepository? = null
        private val SourceConfigCaches = mutableMapOf<String, LocalAudioSourceConfigCache>()

        fun fromContext(context: Context): LocalAudioRepository =
            singleton ?: synchronized(this) {
                singleton ?: LocalAudioRepository(context.filesDir, context).also { singleton = it }
            }
    }
}
