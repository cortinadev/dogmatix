package com.cortinadev.dogmatix.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cortinadev.dogmatix.util.SearchNormalizer
import com.cortinadev.dogmatix.util.VersionGrouping

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
    indices = [Index("consoleId"), Index("fileName"), Index("consoleId", "groupKey"), Index("consoleId", "sourceUrl")]
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
    /** Shared by every version of one game in its console (regions, revisions, discs); see [VersionGrouping]. */
    @ColumnInfo(defaultValue = "")
    val groupKey: String = VersionGrouping.groupKey(name),
    /** The source (its URL as configured) this row came from: a rescan replaces one source's rows at a time. '' = indexed before sources were tracked. */
    @ColumnInfo(defaultValue = "")
    val sourceUrl: String = "",
) {
    val isTorrent: Boolean get() = torrentFileIndex != null && torrentMagnet != null
}
