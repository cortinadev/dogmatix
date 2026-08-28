package com.cortinadev.dogmatix.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "consoles",
    indices = [Index("manufacturerId")]
)
data class ConsoleEntity(
    @PrimaryKey val id: String, // e.g. "sony_psp"
    val name: String, // e.g. "PlayStation Portable"
    val manufacturerId: String, // e.g. "sony"
    val urls: String, // JSON array of UrlEntry objects as string
    /** Chip label (e.g. "GBA"); empty = built-in default from ConsoleFormatter. */
    @ColumnInfo(defaultValue = "") val shortName: String = "",
    /** Comma-separated folder names that count as this console's download folder; empty = built-in defaults. */
    @ColumnInfo(defaultValue = "") val folderAliases: String = ""
)
