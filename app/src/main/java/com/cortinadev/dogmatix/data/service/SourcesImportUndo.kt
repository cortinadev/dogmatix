package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.SourceManufacturer
import com.cortinadev.dogmatix.util.SourcesJson
import com.cortinadev.dogmatix.util.UndoKinds
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Undo of "Import sources": before an import replaces every source, the old document is saved
 * (with copies of its local `.torrent` files, which the import deletes) and the history entry
 * points at it. Undo imports it back and rescans everything, as an import does.
 */
@Singleton
class SourcesImportUndo @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: Provider<SourcesRepository>,
    private val history: Provider<ActionHistoryService>,
    private val sourceScans: Provider<SourceScanService>,
    private val rescan: RescanStateHolder,
    private val libraryIndex: Provider<LibraryIndexService>
) : UndoHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = File(context.filesDir, "collection_tools/sources")

    /** Saves [doc] for a later undo; returns the folder that holds it (the entry's undo data). */
    fun snapshot(doc: List<SourceManufacturer>): String {
        val folder = File(root, "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}").apply { mkdirs() }
        var n = 0
        val kept = doc.map { m ->
            m.copy(consoles = m.consoles.map { c ->
                c.copy(urls = c.urls.map { u ->
                    val local = File(u.url)
                    if (u.url.startsWith("/") && local.isFile) {
                        val copy = File(folder, "t${n++}.torrent")
                        local.copyTo(copy, overwrite = true)
                        u.copy(url = copy.absolutePath)
                    } else u
                })
            })
        }
        File(folder, DOCUMENT).writeText(SourcesJson.serializeDocument(kept))
        return folder.absolutePath
    }

    /** The history entry of an import of the file [uri] ([count] consoles). */
    suspend fun recordImport(uri: String, count: Int, snapshot: String?) {
        history.get().record(
            ActionTypes.SOURCES_IMPORT, displayName(uri), count,
            undoKind = snapshot?.let { UndoKinds.SOURCES }, undoData = snapshot
        )
    }

    private fun displayName(uri: String): String = runCatching {
        context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.substringAfterLast('/')

    override suspend fun isAvailable(data: String): Boolean = !rescan.isRescanning.value && File(data, DOCUMENT).isFile

    override suspend fun undo(entryId: Long, data: String): UndoOutcome = withContext(Dispatchers.IO) {
        val folder = File(data)
        val doc = SourcesJson.parseDocument(File(folder, DOCUMENT).readText())
        // Local torrents go back where sources keep theirs: the snapshot is deleted below.
        val torrents = File(context.filesDir, "torrents").apply { mkdirs() }
        var n = 0
        val restored = doc.map { m ->
            m.copy(consoles = m.consoles.map { c ->
                c.copy(urls = c.urls.map { u ->
                    val copy = File(u.url)
                    if (copy.parentFile == folder && copy.isFile) {
                        val back = File(torrents, "uploaded_${System.currentTimeMillis()}_${n++}.torrent")
                        copy.copyTo(back, overwrite = true)
                        u.copy(url = back.absolutePath)
                    } else u
                })
            })
        }
        sources.get().importFromText(SourcesJson.serializeDocument(restored), allowEmpty = true)
        folder.deleteRecursively()
        // The rescan outlives the screen that asked for the undo.
        scope.launch { rescanAll() }
        UndoOutcome.DONE
    }

    override suspend fun discard(data: String) {
        File(data).takeIf { it.parentFile == root }?.deleteRecursively()
    }

    /** Same as the rescan after an import (Sources): every source read again in full, then the library re-indexed. */
    private suspend fun rescanAll() {
        if (rescan.isRescanning.value) return
        sourceScans.get().scanAll(force = true).join()
        runCatching { libraryIndex.get().refresh() }
    }

    private companion object {
        const val DOCUMENT = "sources.json"
    }
}
