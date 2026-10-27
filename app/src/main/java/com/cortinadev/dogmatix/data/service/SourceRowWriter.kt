package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.local.dao.SourceScanDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.util.SourceScanCheck
import javax.inject.Inject
import javax.inject.Singleton

/** What indexing one source wrote (or kept): [files] rows, [newFiles] of them not listed before, adding [newGames]. */
data class SourceWrite(
    val files: Int,
    val tags: Int,
    val newFiles: Int = 0,
    val newGames: Int = 0,
    /** The rows were left exactly as they were (same listing as last time). */
    val unchanged: Boolean = false,
    val contentHash: String? = null
)

/**
 * The one place a scan writes a source's rows. Same files as last time, and all of them still in
 * the library: nothing is touched (no delete + insert, the row ids, FTS and observers stay quiet).
 * Otherwise the source's rows are replaced in one transaction ([SourceScanDao.replaceSource]), so
 * a failure or a cancellation leaves the old rows whole.
 */
@Singleton
class SourceRowWriter @Inject constructor(private val dao: SourceScanDao) {

    suspend fun write(
        consoleId: String,
        entry: UrlEntry,
        previous: SourceScanCheck.Previous?,
        force: Boolean,
        files: List<DownloadableFileEntity>,
        tags: List<List<String>>
    ): SourceWrite {
        val hash = SourceScanCheck.contentHash(files, tags)
        if (SourceScanCheck.sameContent(entry, previous, force, hash, files.size)) {
            return SourceWrite(files.size, 0, unchanged = true, contentHash = hash)
        }
        val written = dao.replaceSource(consoleId, entry.url, files, tags)
        return SourceWrite(files.size, written.tags, written.newFiles, written.newGames, contentHash = hash)
    }
}
