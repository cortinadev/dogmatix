package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.DownloadPartials.UnderFinalName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPartialsTest {

    @Test
    fun aFileUnderTheFinalNameAgainstItsRecord() {
        // Written under its final name before 1.4 and cut short: carried on where it is.
        assertEquals(UnderFinalName.UNFINISHED, DownloadPartials.underFinalName(400, 1000))
        // Arrived whole and was renamed, but not extracted yet.
        assertEquals(UnderFinalName.COMPLETE, DownloadPartials.underFinalName(1000, 1000))
        // Longer than the transfer announced, or no length recorded: not ours to judge.
        assertNull(DownloadPartials.underFinalName(1200, 1000))
        assertNull(DownloadPartials.underFinalName(400, -1))
        assertNull(DownloadPartials.underFinalName(400, null))
    }

    @Test
    fun aWholeArchiveThatFailedToExtractGoesWithItsRow() {
        // Game.zip came down whole and took its name; extracting it failed (or was stopped), and
        // the row is deleted without "delete the file": it must not stay in the console folder.
        assertTrue(DownloadPartials.deleteWithUnfinishedRow(length = 1000, recordedTotal = 1000, hasResumeRecord = true))
        // A pre-1.4 partial goes too.
        assertTrue(DownloadPartials.deleteWithUnfinishedRow(length = 400, recordedTotal = 1000, hasResumeRecord = true))
    }

    @Test
    fun somebodyElsesFileStays() {
        // No resume record: a complete copy that was already there (fetched again, or the user's own).
        assertFalse(DownloadPartials.deleteWithUnfinishedRow(length = 1000, recordedTotal = null, hasResumeRecord = false))
        assertFalse(DownloadPartials.deleteWithUnfinishedRow(length = 1000, recordedTotal = 1000, hasResumeRecord = false))
        // A record that does not fit it (unknown length, or a longer file): left alone.
        assertFalse(DownloadPartials.deleteWithUnfinishedRow(length = 1000, recordedTotal = -1, hasResumeRecord = true))
        assertFalse(DownloadPartials.deleteWithUnfinishedRow(length = 1500, recordedTotal = 1000, hasResumeRecord = true))
    }
}
