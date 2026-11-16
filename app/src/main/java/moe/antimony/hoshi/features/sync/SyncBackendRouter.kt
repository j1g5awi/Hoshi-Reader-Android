package moe.antimony.hoshi.features.sync

import dagger.Lazy
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.epub.ReadingStatistics

/**
 * Routes [DriveSyncDataSource] calls to the selected provider. Google Drive and
 * TTU both use [TtuDriveHandler]; WebDAV uses [WebDavClient]. Both store data in
 * the same TTU file layout, so the rest of the sync pipeline is unchanged.
 */
@Singleton
class SyncBackendRouter @Inject constructor(
    private val ttu: TtuDriveHandler,
    private val webDav: WebDavClient,
    private val settings: Lazy<SyncSettingsRepository>,
) : DriveSyncDataSource {
    private suspend fun current(): DriveSyncDataSource =
        if (settings.get().settings.first().provider == SyncProvider.WebDav) webDav else ttu

    override suspend fun findRootFolder(): String = current().findRootFolder()

    override suspend fun listBooks(rootFolderId: String): List<DriveFile> = current().listBooks(rootFolderId)

    override suspend fun ensureBookFolder(
        bookTitle: String,
        rootFolderId: String,
        coverImageDataProvider: (suspend () -> ByteArray?)?,
    ): String = current().ensureBookFolder(bookTitle, rootFolderId, coverImageDataProvider)

    override suspend fun listSyncFiles(folderId: String): DriveSyncFiles = current().listSyncFiles(folderId)

    override suspend fun listSyncFiles(folderIds: List<String>): Map<String, DriveSyncFiles> =
        current().listSyncFiles(folderIds)

    override suspend fun downloadFile(
        fileId: String,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): ByteArray = current().downloadFile(fileId, progress)

    override suspend fun downloadFileTo(
        fileId: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = current().downloadFileTo(fileId, destination, progress)

    override suspend fun downloadThumbnailTo(
        thumbnailLink: String,
        destination: File,
        progress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ) = current().downloadThumbnailTo(thumbnailLink, destination, progress)

    override suspend fun getProgressFile(fileId: String): TtuProgress = current().getProgressFile(fileId)

    override suspend fun getStatsFile(fileId: String): List<ReadingStatistics> = current().getStatsFile(fileId)

    override suspend fun getAudioBookFile(fileId: String): TtuAudioBook = current().getAudioBookFile(fileId)

    override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) =
        current().updateProgressFile(folderId, fileId, progress)

    override suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>) =
        current().updateStatsFile(folderId, fileId, stats)

    override suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook) =
        current().updateAudioBookFile(folderId, fileId, audioBook)

    override suspend fun uploadBookData(folderId: String, file: File) = current().uploadBookData(folderId, file)

    override suspend fun trashFile(fileId: String) = current().trashFile(fileId)

    override fun clearCache() {
        ttu.clearCache()
        webDav.clearCache()
    }
}
