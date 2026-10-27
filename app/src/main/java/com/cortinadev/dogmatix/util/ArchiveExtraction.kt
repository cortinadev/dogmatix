package com.cortinadev.dogmatix.util

/**
 * An archive that could not be unpacked, with the reason the Downloads row shows ([failure]):
 * [DownloadFailure.EXTRACTION] (damaged, unsupported, unsafe paths, nothing in it),
 * [DownloadFailure.EXTRACTION_NO_SPACE], or the folder's own trouble ([DownloadFailure.STORAGE],
 * [DownloadFailure.PERMISSION_LOST], [DownloadFailure.STORAGE_GONE]). Whatever the attempt wrote
 * has been removed again and the archive is left as it was.
 */
class ArchiveExtractionException(val failure: DownloadFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The decisions of unpacking a download (see ArchiveExtractorService). Pure JVM. */
object ArchiveExtraction {
    private const val MB = 1024L * 1024

    /** Room left free besides what is unpacked, in the app's cache and in the folder. */
    const val RESERVE_BYTES = 64L * MB

    /** True/false when the free space is known; unknown space refuses nothing. */
    fun fits(needed: Long, free: Long?): Boolean = free == null || free - RESERVE_BYTES >= needed

    /**
     * What a failure while writing the unpacked files into the folder is, from what the error
     * says or, failing that, what is wrong with the folder now ([treeProblem]).
     */
    fun writeFailure(error: StorageProblem?, treeProblem: StorageProblem?): DownloadFailure =
        when (error ?: treeProblem) {
            StorageProblem.NO_SPACE -> DownloadFailure.EXTRACTION_NO_SPACE
            StorageProblem.PERMISSION_LOST -> DownloadFailure.PERMISSION_LOST
            StorageProblem.VOLUME_GONE -> DownloadFailure.STORAGE_GONE
            null -> DownloadFailure.STORAGE
        }

    /** What a file of the destination with an entry's name is to that entry. */
    enum class Existing {
        /** Nothing of that name there: the entry is written under it. */
        NONE,
        /** The same file (size and sampled bytes): it stands for the entry, nothing is written. */
        SAME,
        /** Something else: it stays, and the entry goes in under another name (`name (2).ext`). */
        OTHER
    }

    fun existing(entrySize: Long, existingSize: Long?, isDirectory: Boolean, sameBytes: Boolean): Existing = when {
        existingSize == null && !isDirectory -> Existing.NONE
        !isDirectory && existingSize == entrySize && sameBytes -> Existing.SAME
        else -> Existing.OTHER
    }
}
