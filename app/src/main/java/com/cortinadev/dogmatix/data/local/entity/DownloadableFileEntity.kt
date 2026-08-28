package com.cortinadev.dogmatix.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cortinadev.dogmatix.util.SearchNormalizer

@Entity(
    tableName = "downloadable_files",
    foreignKeys = [
        ForeignKey(
            entity = ConsoleEntity::class,
            parentColumns = ["id"],
            childColumns = ["consoleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("consoleId")]
)
data class DownloadableFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fileName: String,
    val consoleId: String,
    val downloadUrl: String,
    val fileSize: Long = 0L,
    val fileExtension: String = "",
    val torrentFileIndex: Int? = null,
    val torrentMagnet: String? = null,
    /** Lenient form of [name] used for searching; see [SearchNormalizer]. */
    @ColumnInfo(defaultValue = "")
    val searchKey: String = SearchNormalizer.key(name),
) {
    val isTorrent: Boolean get() = torrentFileIndex != null && torrentMagnet != null
}
