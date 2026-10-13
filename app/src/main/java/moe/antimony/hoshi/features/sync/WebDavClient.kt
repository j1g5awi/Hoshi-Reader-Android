package moe.antimony.hoshi.features.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import dagger.Lazy
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.epub.ReadingStatistics

/**
 * WebDAV backend for [DriveSyncDataSource]. Folders and files are addressed by their absolute
 * URL, which doubles as the opaque id used by the shared sync pipeline.
 */
@Singleton
class WebDavClient @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: Lazy<SyncSettingsRepository>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DriveSyncDataSource {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun findRootFolder(): String = config().rootUrl

    override suspend fun listBooks(rootFolderId: String): List<DriveFile> =
        listResources(rootFolderId)
            .filter { it.isCollection }
            .map { it.toDriveFile(parent = rootFolderId) }

    override suspend fun ensureBookFolder(
        bookTitle: String,
        rootFolderId: String,
        coverImageDataProvider: (suspend () -> ByteArray?)?,
    ): String {
        val folderUrl = joinUrl(rootFolderId, TtuSyncRules.sanitizeTtuFilename(bookTitle))
        if (!resourceExists(folderUrl)) {
            mkcol(folderUrl)
            val coverData = coverImageDataProvider?.invoke()
            if (coverData != null) {
                runCatching {
                    val metadata = TtuSyncRules.coverMetadata(coverData)
                    putBytes(joinUrl(folderUrl, "cover_1_6.${metadata.extension}"), coverData, metadata.mimeType)
                }
            }
        }
        return folderUrl
    }

    override suspend fun listSyncFiles(folderId: String): DriveSyncFiles =
        listResources(folderId)
            .filter { !it.isCollection }
            .map { it.toDriveFile(parent = folderId) }
            .toDriveSyncFiles()

    override suspend fun downloadFile(
        fileId: String,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ByteArray = getBytes(fileId).also { progress(it.size.toLong(), it.size.toLong()) }

    override suspend fun downloadFileTo(
        fileId: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) {
        val data = getBytes(fileId)
        destination.parentFile?.mkdirs()
        destination.writeBytes(data)
        progress(data.size.toLong(), data.size.toLong())
    }

    override suspend fun getProgressFile(fileId: String): TtuProgress =
        json.decodeFromString(TtuProgress.serializer(), getBytes(fileId).decodeToString())

    override suspend fun getStatsFile(fileId: String): List<ReadingStatistics> =
        json.decodeFromString(ListSerializer(ReadingStatistics.serializer()), getBytes(fileId).decodeToString())

    override suspend fun getAudioBookFile(fileId: String): TtuAudioBook =
        json.decodeFromString(TtuAudioBook.serializer(), getBytes(fileId).decodeToString())

    override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) {
        replaceFile(folderId, fileId, TtuSyncRules.progressFileName(progress), json.encodeToString(progress).toByteArray())
    }

    override suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>) {
        replaceFile(
            folderId,
            fileId,
            TtuSyncRules.statisticsFileName(stats),
            json.encodeToString(ListSerializer(ReadingStatistics.serializer()), stats).toByteArray(),
        )
    }

    override suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook) {
        replaceFile(
            folderId,
            fileId,
            TtuSyncRules.audioBookFileName(audioBook),
            json.encodeToString(audioBook).toByteArray(),
        )
    }

    override suspend fun uploadBookData(folderId: String, file: File) {
        putBytes(joinUrl(folderId, file.name), file.readBytes(), "application/zip")
    }

    override suspend fun trashFile(fileId: String) {
        withContext(ioDispatcher) {
            val config = config()
            val connection = openConnection(config, fileId, "DELETE", depth = "infinity")
            try {
                val status = connection.responseCode
                if (status >= 400 && status != HttpURLConnection.HTTP_NOT_FOUND) {
                    throw WebDavException("WebDAV delete failed with status $status")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun clearCache() = Unit

    private suspend fun replaceFile(folderId: String, fileId: String?, name: String, content: ByteArray) {
        if (fileId != null && fileId != joinUrl(folderId, name)) {
            runCatching { trashFile(fileId) }
        }
        putBytes(joinUrl(folderId, name), content, "application/json")
    }

    private suspend fun resourceExists(url: String): Boolean = withContext(ioDispatcher) {
        val config = config()
        val connection = openConnection(config, url, "PROPFIND", depth = "0")
        connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        connection.doOutput = true
        connection.outputStream.use { it.write(PropfindBody) }
        try {
            val status = connection.responseCode
            status in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun listResources(url: String): List<WebDavResource> = withContext(ioDispatcher) {
        val config = config()
        val connection = openConnection(config, url, "PROPFIND", depth = "1")
        connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        connection.doOutput = true
        connection.outputStream.use { it.write(PropfindBody) }
        try {
            val status = connection.responseCode
            val bytes = if (status >= 400) {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            } else {
                connection.inputStream.use { it.readBytes() }
            }
            if (status >= 400) throw WebDavException("WebDAV list failed with status $status")
            parseMultistatus(bytes, config.rootUrl)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun mkcol(url: String) = withContext(ioDispatcher) {
        val config = config()
        val connection = openConnection(config, url, "MKCOL")
        try {
            val status = connection.responseCode
            if (status >= 400 && status != HttpURLConnection.HTTP_BAD_METHOD) {
                throw WebDavException("WebDAV MKCOL failed with status $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun putBytes(url: String, content: ByteArray, contentType: String) = withContext(ioDispatcher) {
        val config = config()
        val connection = openConnection(config, url, "PUT")
        connection.setRequestProperty("Content-Type", contentType)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(content.size)
        connection.outputStream.use { it.write(content) }
        try {
            val status = connection.responseCode
            if (status >= 400) throw WebDavException("WebDAV upload failed with status $status")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun getBytes(url: String): ByteArray = withContext(ioDispatcher) {
        val config = config()
        val connection = openConnection(config, url, "GET")
        try {
            val status = connection.responseCode
            if (status >= 400) throw WebDavException("WebDAV download failed with status $status")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        config: WebDavConfig,
        url: String,
        method: String,
        depth: String? = null,
    ): HttpURLConnection {
        checkInternet()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HttpConnectTimeoutMillis
            readTimeout = HttpReadTimeoutMillis
            instanceFollowRedirects = true
            if (config.username.isNotBlank() || config.password.isNotBlank()) {
                val token = Base64.encodeToString(
                    "${config.username}:${config.password}".toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP,
                )
                setRequestProperty("Authorization", "Basic $token")
            }
            depth?.let { setRequestProperty("Depth", it) }
        }
        connection.setRequestMethodCompat(method)
        return connection
    }

    private fun checkInternet() {
        val network = connectivityManager?.activeNetwork
            ?: throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        if (!shouldAttemptDriveRequest(
                hasActiveNetwork = true,
                hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                hasValidatedCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        ) {
            throw GoogleDriveApiException(GoogleDriveApiException.NoInternetConnectionMessage)
        }
    }

    private suspend fun config(): WebDavConfig {
        val settings = settings.get().settings.first()
        val url = settings.webDavUrl.trim()
        require(url.isNotBlank()) { "WebDAV server URL is not configured." }
        return WebDavConfig(
            rootUrl = normalizeRoot(url),
            username = settings.webDavUsername,
            password = settings.webDavPassword,
        )
    }

    private fun parseMultistatus(bytes: ByteArray, rootUrl: String): List<WebDavResource> {
        if (bytes.isEmpty()) return emptyList()
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val responses = document.getElementsByTagNameNS("DAV:", "response")
        val resources = mutableListOf<WebDavResource>()
        for (index in 0 until responses.length) {
            val response = responses.item(index) as? org.w3c.dom.Element ?: continue
            val hrefs = response.getElementsByTagNameNS("DAV:", "href")
            if (hrefs.length == 0) continue
            val href = hrefs.item(0).textContent?.trim().orEmpty()
            if (href.isEmpty()) continue
            val resourceUrl = resolveUrl(rootUrl, href)
            val isCollection = response.getElementsByTagNameNS("DAV:", "collection").length > 0
            resources += WebDavResource(url = resourceUrl, isCollection = isCollection)
        }
        return resources.filterNot { it.url.trimEnd('/') == rootUrl.trimEnd('/') }
    }

    private fun WebDavResource.toDriveFile(parent: String): DriveFile {
        val name = decodePathSegment(url.trimEnd('/').substringAfterLast('/'))
        return DriveFile(id = url, name = name, parents = listOf(parent), thumbnailLink = null)
    }

    private fun normalizeRoot(url: String): String = url.trim().trimEnd('/') + "/"

    private fun joinUrl(base: String, segment: String): String = base.trimEnd('/') + "/" + encodePathSegment(segment)

    private fun resolveUrl(rootUrl: String, href: String): String =
        if (href.startsWith("http://") || href.startsWith("https://")) href else URL(URL(rootUrl), href).toString()

    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decodePathSegment(segment: String): String =
        runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) }.getOrDefault(segment)

    private fun HttpURLConnection.setRequestMethodCompat(method: String) {
        if (method == "GET" || method == "POST" || method == "HEAD" ||
            method == "OPTIONS" || method == "PUT" || method == "DELETE" || method == "TRACE"
        ) {
            requestMethod = method
            return
        }
        // HttpURLConnection rejects WebDAV verbs; set the protected method field directly.
        val field = HttpURLConnection::class.java.getDeclaredField("method")
        field.isAccessible = true
        field.set(this, method)
    }

    private data class WebDavResource(val url: String, val isCollection: Boolean)

    private data class WebDavConfig(val rootUrl: String, val username: String, val password: String)

    companion object {
        private const val HttpConnectTimeoutMillis = 15_000
        private const val HttpReadTimeoutMillis = 30_000
        private val PropfindBody = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
                "</d:prop></d:propfind>"
            ).toByteArray(StandardCharsets.UTF_8)
    }
}

class WebDavException(message: String) : Exception(message)
