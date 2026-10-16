package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One ROM of the RomM server's catalogue, under a console mapped to its platform (Settings →
 * RomM). Replaced wholesale on every catalogue refresh; see `RommCatalogService`.
 */
@Entity(
    tableName = "romm_games",
    primaryKeys = ["consoleId", "romId"],
    indices = [Index("romId"), Index("consoleId", "groupKey")]
)
data class RommGameEntity(
    val consoleId: String,
    val romId: Int,
    val platformId: Int,
    /** File name, or folder name for a multi-file ROM. */
    val fsName: String,
    val name: String,
    val groupKey: String,
    /** Leaf names of the ROM's files, one per line. */
    val files: String,
    /** Lower-case CRC / MD5 / SHA-1 hashes, space-separated. */
    val hashes: String,
    val coverPath: String,
    val coverLargePath: String,
    /** `RommPlayState` name of the account's play status. */
    val userStatus: String,
    val fetchedAt: Long
)
