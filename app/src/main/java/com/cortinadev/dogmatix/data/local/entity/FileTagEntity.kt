package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "downloadable_file_tags",
    primaryKeys = ["fileId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadableFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("fileId"), Index("tag")]
)
data class FileTagEntity(
    val fileId: Long,
    val tag: String
)
