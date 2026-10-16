package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadListFilterTest {

    private fun row(name: String, status: DownloadStatus, file: String = name) =
        DownloadItemModel(name = name, fileName = file, downloadSpeed = 0f, progress = 0f, fileSize = 100L, status = status)

    private val running = row("Super Mario World", DownloadStatus.DOWNLOADING)
    private val waiting = row("Pokémon Red", DownloadStatus.DOWNLOADING)
    private val held = row("Yu-Gi-Oh! Duel", DownloadStatus.DOWNLOADING)
    private val debrid = row("Zelda", DownloadStatus.QUEUED)
    private val unzipping = row("Metroid", DownloadStatus.UNZIPPING)
    private val failed = row("Mario Kart", DownloadStatus.FAILED)
    private val done = row("Sonic", DownloadStatus.COMPLETED)
    private val paused = row("Tetris", DownloadStatus.PAUSED)
    private val stopped = row("Pac-Man", DownloadStatus.STOPPED)
    private val rows = listOf(running, waiting, held, debrid, unzipping, failed, done, paused, stopped)
    private val inLine = setOf(waiting.fileName, held.fileName)
    private val holds = setOf(held.fileName)

    @Test
    fun theQueueTellsWaitingFromRunning() {
        assertEquals(DownloadFilter.RUNNING, DownloadListFilter.groupOf(running, inLine, holds))
        assertEquals(DownloadFilter.WAITING, DownloadListFilter.groupOf(waiting, inLine, holds))
        assertEquals(DownloadFilter.HELD, DownloadListFilter.groupOf(held, inLine, holds))
        assertEquals(DownloadFilter.RUNNING, DownloadListFilter.groupOf(debrid, inLine, holds))
        assertEquals(DownloadFilter.RUNNING, DownloadListFilter.groupOf(unzipping, inLine, holds))
        assertEquals(DownloadFilter.FAILED, DownloadListFilter.groupOf(failed, inLine, holds))
        assertEquals(DownloadFilter.COMPLETED, DownloadListFilter.groupOf(done, inLine, holds))
        assertNull(DownloadListFilter.groupOf(paused, inLine, holds))
        assertNull(DownloadListFilter.groupOf(stopped, inLine, holds))
    }

    @Test
    fun chipsKeepTheListOrderAndCountEveryGroup() {
        val result = DownloadListFilter.apply(rows, "", DownloadFilter.RUNNING, inLine, holds)
        assertEquals(listOf(running, debrid, unzipping), result.rows)
        assertEquals(9, result.counts[DownloadFilter.ALL])
        assertEquals(3, result.counts[DownloadFilter.RUNNING])
        assertEquals(1, result.counts[DownloadFilter.WAITING])
        assertEquals(1, result.counts[DownloadFilter.HELD])
        assertEquals(1, result.counts[DownloadFilter.FAILED])
        assertEquals(1, result.counts[DownloadFilter.COMPLETED])
        assertEquals(rows, DownloadListFilter.apply(rows, "", DownloadFilter.ALL, inLine, holds).rows)
    }

    @Test
    fun searchIgnoresCaseAccentsAndPunctuation() {
        assertEquals(listOf(waiting), DownloadListFilter.apply(rows, "pokemon", DownloadFilter.ALL, inLine, holds).rows)
        assertEquals(listOf(held), DownloadListFilter.apply(rows, "yugioh", DownloadFilter.ALL, inLine, holds).rows)
        // Every word must be there, in any order.
        assertEquals(listOf(failed), DownloadListFilter.apply(rows, "kart mario", DownloadFilter.ALL, inLine, holds).rows)
        assertEquals(rows, DownloadListFilter.apply(rows, "   ", DownloadFilter.ALL, inLine, holds).rows)
    }

    @Test
    fun chipCountsFollowTheSearch() {
        val result = DownloadListFilter.apply(rows, "mario", DownloadFilter.FAILED, inLine, holds)
        assertEquals(listOf(failed), result.rows)
        assertEquals(2, result.counts[DownloadFilter.ALL])
        assertEquals(1, result.counts[DownloadFilter.RUNNING])
        assertEquals(0, result.counts[DownloadFilter.WAITING])
    }

    @Test
    fun wholeListButtonsPickTheirRows() {
        // Pause all: transfers and rows waiting in line, never a held one.
        assertEquals(listOf(running.fileName, waiting.fileName), DownloadBulk.pausable(rows, holds))
        assertEquals(listOf(paused.fileName), DownloadBulk.resumable(rows))
        assertEquals(listOf(failed.fileName), DownloadBulk.failed(rows))
        assertEquals(listOf(done.fileName), DownloadBulk.completed(rows))
        // Cancel all: everything still going, held rows included.
        assertEquals(
            listOf(running, waiting, held, debrid, unzipping).map { it.fileName },
            DownloadBulk.stoppable(rows)
        )
    }

    @Test
    fun retryFailedOnlyTakesTheRowsOnScreen() {
        val oldFailure = row("Gran Turismo 4", DownloadStatus.FAILED)
        val history = rows + oldFailure
        val shown = { query: String, chip: DownloadFilter ->
            DownloadBulk.failed(DownloadListFilter.apply(history, query, chip, inLine, holds).rows)
        }
        assertEquals(listOf(failed.fileName, oldFailure.fileName), shown("", DownloadFilter.ALL))
        assertEquals(listOf(failed.fileName), shown("mario", DownloadFilter.ALL))
        assertEquals(listOf(oldFailure.fileName), shown("turismo", DownloadFilter.FAILED))
        assertEquals(emptyList<String>(), shown("", DownloadFilter.COMPLETED))
    }
}
