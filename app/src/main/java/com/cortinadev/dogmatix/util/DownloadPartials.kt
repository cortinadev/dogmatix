package com.cortinadev.dogmatix.util

/**
 * What a web download leaves under its *final* name while it has not completed. From 1.4 on a
 * transfer is written as `<name>.dogmatix-part` and renamed once whole; before that it was
 * written under the final name, and a whole file that failed or was stopped while extracting
 * also sits there. The resume record (with the length the transfer announced) says it is ours:
 * a complete file without one, such as an old copy of a file fetched again, is never touched.
 */
object DownloadPartials {

    enum class UnderFinalName {
        /** Shorter than the recorded length: carried on where it is (a transfer from before 1.4). */
        UNFINISHED,
        /** Exactly the recorded length: it arrived whole and was not processed yet (extraction). */
        COMPLETE
    }

    /** A file under the final name against its resume record's [recordedTotal] (null or ≤ 0 = unknown): null = not ours. */
    fun underFinalName(length: Long, recordedTotal: Long?): UnderFinalName? {
        val total = recordedTotal?.takeIf { it > 0 } ?: return null
        return when {
            length < total -> UnderFinalName.UNFINISHED
            length == total -> UnderFinalName.COMPLETE
            else -> null
        }
    }

    /**
     * Whether the file under the final name goes with a row that did not complete (for good, like
     * its partial): it is what this download left there, finished or not. Without a resume record,
     * or with a length that does not fit it, it is somebody else's file and stays.
     */
    fun deleteWithUnfinishedRow(length: Long, recordedTotal: Long?, hasResumeRecord: Boolean): Boolean =
        hasResumeRecord && underFinalName(length, recordedTotal) != null
}
