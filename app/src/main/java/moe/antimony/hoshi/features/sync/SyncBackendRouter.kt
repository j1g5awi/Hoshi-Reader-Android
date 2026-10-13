package moe.antimony.hoshi.features.sync

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.epub.ReadingStatistics
import java.io.File

/**
 * Dispatches every [DriveSyncDataSource] call to the backend selected in sync settings.
 * The selected provider is read from [SyncSettingsRepository] on each call so switching
 * providers takes effect immediately without rebuilding the object graph.
 */
@Singleton
class SyncBackendRouter @Inject constructor(
    private val googleDrive: GoogleDriveClient,
    private val webDav: WebDavClient,
    private val settings: Lazy<SyncSettingsRepository>,
) : DriveSyncDataSource {
    private suspend fun current(): DriveSyncDataSource =
        when (settings.get().settings.first().provider) {
            SyncProvider.GoogleDrive -> googleDrive
            SyncProvider.WebDav -> webDav
        }

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
        googleDrive.clearCache()
        webDav.clearCache()
    }
}
