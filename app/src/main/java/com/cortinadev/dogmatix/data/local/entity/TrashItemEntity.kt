package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A file or folder in the trash. A batch ([batchId]) is what one action deleted; it comes back
 * or goes for good as a whole. [originalParentUri] is the folder it was in, [relativePath] its
 * path from the root of its SAF tree ([treeUri]), used when that folder is gone (empty folders
 * are removed after a delete) to recreate it.
 */
@Entity(tableName = "trash_items", indices = [Index("batchId")])
data class TrashItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: String,
    val treeUri: String,
    val originalParentUri: String,
    val relativePath: String,
    val name: String,
    val trashedUri: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val trashedAt: Long,
    /** IN_TRASH, RESTORED or PURGED. */
    val state: String
) {
    companion object {
        const val IN_TRASH = "IN_TRASH"
        const val RESTORED = "RESTORED"
        const val PURGED = "PURGED"
    }
}
