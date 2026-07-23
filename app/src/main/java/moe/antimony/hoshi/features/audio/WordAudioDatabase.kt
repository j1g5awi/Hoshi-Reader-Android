package moe.antimony.hoshi.features.audio

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class WordAudioDatabase(private val contentResolver: ContentResolver) {

    private var handle: Long = 0L
    private var legacyDb: SQLiteDatabase? = null
    private var fallbackUsed: Boolean = false

    var lastError: String? = null

    fun open(uri: Uri): Boolean {
        close()
        lastError = null
        fallbackUsed = false

        // Attempt: ContentResolver fd → native deserialize
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return fail("Cannot open file")
            val size = pfd.statSize
            if (size <= 0) { pfd.close(); return fail("File is empty") }
            val fd = pfd.detachFd()
            val h = nativeOpen(fd, size)
            if (h != 0L && nativeTestConnection(h)) {
                handle = h
                return true
            }
            if (h != 0L) nativeClose(h)
        } catch (e: Exception) {
            Log.w(TAG, "Native open failed", e)
        }

        // Fallback: copy to private temp and use SQLiteDatabase
        return try {
            val tempFile = java.io.File.createTempFile("word_audio_", ".db")
            tempFile.deleteOnExit()
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return fail("Cannot read file")
            legacyDb = SQLiteDatabase.openDatabase(
                tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            if (legacyDb != null) {
                fallbackUsed = true
                return true
            }
            tempFile.delete()
            fail("File is not a valid audio database")
        } catch (e: Exception) {
            fail(e.message ?: "Fallback failed")
        }
    }

    fun open(path: File): Boolean {
        close()
        lastError = null
        fallbackUsed = false
        val pfd = try {
            ParcelFileDescriptor.open(path, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            return fail("Cannot open file: ${e.message}")
        }
        val size = pfd.statSize
        if (size <= 0) { pfd.close(); return fail("File is empty") }
        val fd = pfd.detachFd()
        val h = nativeOpen(fd, size)
        if (h != 0L && nativeTestConnection(h)) {
            handle = h
            return true
        }
        if (h != 0L) nativeClose(h)
        return fail("File is not a valid audio database")
    }

    fun testConnection(): Boolean {
        if (fallbackUsed) {
            val db = legacyDb ?: return false
            return try {
                db.rawQuery("SELECT count(*) FROM entries LIMIT 1", null).use { it.moveToFirst() }
            } catch (e: Exception) {
                false
            }
        }
        return handle != 0L && nativeTestConnection(handle)
    }

    fun findEntries(term: String, reading: String): List<LocalEntry> {
        if (fallbackUsed) return queryLegacy(term, reading)
        if (handle == 0L) return emptyList()
        val rows = nativeFindEntries(handle, term, reading, DEFAULT_SOURCES.joinToString(","))
        return rows?.toList() ?: emptyList()
    }

    fun getAudioData(file: String, source: String): ByteArray? {
        if (fallbackUsed) return getLegacyAudioData(file, source)
        if (handle == 0L) return null
        return nativeGetAudioData(handle, file, source)
    }

    fun getSources(): List<String> {
        if (fallbackUsed) return getLegacySources()
        if (handle == 0L) return emptyList()
        return nativeGetSources(handle)?.toList() ?: emptyList()
    }

    fun close() {
        if (fallbackUsed) {
            legacyDb?.close()
            legacyDb = null
            fallbackUsed = false
        } else if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
        lastError = null
    }

    private fun fail(msg: String): Boolean {
        lastError = msg
        Log.w(TAG, msg)
        return false
    }

    private fun queryLegacy(term: String, reading: String): List<LocalEntry> {
        val db = legacyDb ?: return emptyList()
        val results = mutableListOf<LocalEntry>()
        val normalizedReading = katakanaToHiragana(reading)
        val sourceClause = DEFAULT_SOURCES.mapIndexed { i, s -> "WHEN '$s' THEN $i" }
            .joinToString(" ")
        val order = "CASE source $sourceClause ELSE 999 END"
        val query = if (normalizedReading.isEmpty()) {
            "SELECT file,source,speaker,display,reading,expression FROM entries WHERE expression=? ORDER BY $order LIMIT 1"
        } else {
            "SELECT file,source,speaker,display,reading,expression FROM entries WHERE (expression=? OR reading=?) ORDER BY CASE WHEN reading=? THEN 0 ELSE 1 END, $order LIMIT 1"
        }
        val args = if (normalizedReading.isEmpty()) arrayOf(term) else arrayOf(term, normalizedReading, normalizedReading)
        try {
            db.rawQuery(query, args).use { cursor ->
                while (cursor.moveToNext()) {
                    results += LocalEntry(
                        file = cursor.getString(0) ?: "",
                        source = cursor.getString(1) ?: "",
                        speaker = cursor.getString(2),
                        display = cursor.getString(3),
                        reading = cursor.getString(4) ?: "",
                        expression = cursor.getString(5) ?: "",
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy query failed", e)
        }
        return results
    }

    private fun getLegacySources(): List<String> {
        val db = legacyDb ?: return emptyList()
        return try {
            db.rawQuery("SELECT DISTINCT source FROM entries WHERE lower(file) LIKE '%.mp3' OR lower(file) LIKE '%.opus' OR lower(file) LIKE '%.ogg'", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) ?: "")
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun getLegacyAudioData(file: String, source: String): ByteArray? {
        val db = legacyDb ?: return null
        return try {
            db.rawQuery("SELECT data FROM android WHERE file=? AND source=? LIMIT 1", arrayOf(file, source)).use {
                if (it.moveToFirst()) it.getBlob(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    data class LocalEntry(
        val file: String,
        val source: String,
        val speaker: String?,
        val display: String?,
        val reading: String,
        val expression: String,
    )

    companion object {
        private const val TAG = "WordAudioDatabase"
        private val DEFAULT_SOURCES = listOf(
            "nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
            "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2",
        )
    }

    private external fun nativeOpen(fd: Int, size: Long): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeTestConnection(handle: Long): Boolean
    private external fun nativeFindEntries(
        handle: Long, term: String, reading: String, sourceOrder: String,
    ): Array<LocalEntry>?
    private external fun nativeGetAudioData(handle: Long, file: String, source: String): ByteArray?
    private external fun nativeGetSources(handle: Long): Array<String>?

    init {
        System.loadLibrary("word_audio_jni")
    }
}

private fun katakanaToHiragana(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
        val code = c.code
        sb.append(if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else c)
    }
    return sb.toString()
}
