package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges several console folders that live side by side in the download directory
 * (e.g. `gba` and `Gameboy Advance`) into the one the user picked. Files are moved with
 * [DocumentsContract.moveDocument] when the provider supports it and copied + deleted otherwise;
 * a file that already exists in the target is left where it is. Emptied folders are removed.
 */
@Singleton
class FolderMergeService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    data class Result(val moved: Int, val duplicates: Int, val skipped: Int, val failed: Int, val removedFolders: Int) {
        companion object { val EMPTY = Result(0, 0, 0, 0, 0) }
    }

    suspend fun merge(rootUri: String, targetFolder: String, sourceFolders: List<String>): Result =
        withContext(Dispatchers.IO) {
            val root = StorageHelper.getDocumentFile(context, rootUri) ?: return@withContext Result.EMPTY
            val target = root.findFile(targetFolder)?.takeIf { it.isDirectory } ?: return@withContext Result.EMPTY
            val tally = Tally()
            var removed = 0
            for (name in sourceFolders) {
                if (name == targetFolder) continue
                val source = root.findFile(name)?.takeIf { it.isDirectory } ?: continue
                mergeInto(source, target, tally)
                if (deleteIfEmpty(source)) removed++
            }
            Result(tally.moved, tally.duplicates, tally.skipped, tally.failed, removed)
        }

    private class Tally { var moved = 0; var duplicates = 0; var skipped = 0; var failed = 0 }

    /**
     * Files frontends and desktop OSes drop into every folder. They never block a merge:
     * a clashing copy is discarded and a folder holding nothing else counts as empty.
     */
    private fun isJunk(name: String): Boolean {
        val lower = name.lowercase()
        return lower in setOf("systeminfo.txt", ".ds_store", "thumbs.db", "desktop.ini") || lower.startsWith("._")
    }

    /** Deletes [dir] when only junk is left in it; retries once since SAF can report stale children. */
    private fun deleteIfEmpty(dir: DocumentFile): Boolean {
        repeat(2) { attempt ->
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return false
            if (children.any { it.isDirectory || !isJunk(it.name.orEmpty()) }) return false
            children.forEach { runCatching { it.delete() } }
            if (runCatching { dir.delete() }.getOrDefault(false)) return true
            if (attempt == 0) Thread.sleep(300)
        }
        Log.w(TAG, "Could not delete emptied folder ${dir.name}")
        return false
    }

    private fun mergeInto(source: DocumentFile, target: DocumentFile, tally: Tally) {
        val children = runCatching { source.listFiles() }.getOrNull() ?: return
        val existing = runCatching { target.listFiles() }.getOrNull().orEmpty()
            .associateBy { it.name?.lowercase() }
        for (child in children) {
            val name = child.name ?: continue
            val clash = existing[name.lowercase()]
            when {
                child.isDirectory && clash?.isDirectory == true -> {
                    // Same sub-folder on both sides: merge its contents instead of skipping it.
                    mergeInto(child, clash, tally)
                    deleteIfEmpty(child)
                }
                clash != null && !child.isDirectory && !clash.isDirectory && (isJunk(name) || sameSize(child, clash)) -> {
                    // Identical copy (or frontend metadata) already in the target: drop this one.
                    if (runCatching { child.delete() }.getOrDefault(false)) tally.duplicates++ else tally.skipped++
                }
                clash != null -> tally.skipped++
                move(child, source, target) -> tally.moved++
                else -> tally.failed++
            }
        }
    }

    private fun sameSize(a: DocumentFile, b: DocumentFile): Boolean =
        runCatching { a.length() > 0 && a.length() == b.length() }.getOrDefault(false)

    private fun move(doc: DocumentFile, from: DocumentFile, to: DocumentFile): Boolean {
        val moved = runCatching {
            DocumentsContract.moveDocument(context.contentResolver, doc.uri, from.uri, to.uri)
        }.onFailure { Log.w(TAG, "moveDocument failed for ${doc.name}: ${it.message}") }.getOrNull()
        if (moved != null) return true
        return if (doc.isDirectory) copyTreeAndDelete(doc, to) else copyAndDelete(doc, to)
    }

    private fun copyAndDelete(doc: DocumentFile, to: DocumentFile): Boolean {
        val name = doc.name ?: return false
        val created = runCatching { to.createFile(doc.type ?: "application/octet-stream", name) }.getOrNull()
            ?: return false
        val copied = runCatching {
            context.contentResolver.openInputStream(doc.uri)!!.use { input ->
                context.contentResolver.openOutputStream(created.uri)!!.use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
        if (!copied) { runCatching { created.delete() }; return false }
        return runCatching { doc.delete() }.getOrDefault(false)
    }

    private fun copyTreeAndDelete(dir: DocumentFile, to: DocumentFile): Boolean {
        val name = dir.name ?: return false
        val dest = runCatching { to.findFile(name)?.takeIf { it.isDirectory } ?: to.createDirectory(name) }.getOrNull()
            ?: return false
        val ok = runCatching { dir.listFiles() }.getOrNull().orEmpty().all { child ->
            if (child.isDirectory) copyTreeAndDelete(child, dest) else copyAndDelete(child, dest)
        }
        return ok && runCatching { dir.delete() }.getOrDefault(false)
    }

    private companion object { const val TAG = "FolderMergeService" }
}
