package com.cortinadev.dogmatix.testing

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.ManufacturerEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus

/** Helpers shared by the instrumented tests. The context is always the sandboxed application. */
object TestData {

    val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A fresh database in memory (foreign keys on, like the app's). Close it in @After. */
    fun inMemoryDatabase(): DogmatixDatabase =
        Room.inMemoryDatabaseBuilder(context, DogmatixDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /** A database file of its own in the sandbox, opened with every migration; delete it with [deleteDatabase]. */
    fun fileDatabase(name: String): DogmatixDatabase =
        Room.databaseBuilder(context, DogmatixDatabase::class.java, name)
            .addMigrations(*DogmatixDatabase.ALL_MIGRATIONS)
            .build()

    fun deleteDatabase(name: String) {
        context.deleteDatabase(name)
    }

    fun manufacturer(id: String = "nintendo", name: String = "Nintendo") = ManufacturerEntity(id, name)

    fun console(id: String, name: String, manufacturerId: String = "nintendo", shortName: String = "") =
        ConsoleEntity(id = id, name = name, manufacturerId = manufacturerId, urls = "[]", shortName = shortName)

    /** A web row: [name] is the indexed name (no tags), [fileName] the file on the server. */
    fun webFile(consoleId: String, name: String, fileName: String, size: Long = 1_000_000L, folder: String = "https://roms.example.org/$consoleId") =
        DownloadableFileEntity(
            name = name, fileName = fileName, consoleId = consoleId,
            downloadUrl = "$folder/${fileName.replace(" ", "%20")}",
            fileSize = size, fileExtension = fileName.substringAfterLast('.', "")
        )

    fun torrentFile(consoleId: String, name: String, fileName: String, magnet: String, index: Int, size: Long = 1_000_000L) =
        DownloadableFileEntity(
            name = name, fileName = fileName, consoleId = consoleId, downloadUrl = magnet,
            fileSize = size, fileExtension = fileName.substringAfterLast('.', ""),
            torrentFileIndex = index, torrentMagnet = magnet
        )

    fun history(
        fileName: String,
        status: DownloadStatus,
        order: Long = 0,
        held: Boolean = false,
        consoleId: String = "nintendo_snes",
        torrent: Boolean = false,
        startedAt: Long = 1_000L
    ) = DownloadHistoryEntity(
        fileName = fileName,
        name = fileName.substringBeforeLast('.'),
        consoleId = consoleId,
        downloadUrl = if (torrent) MAGNET else "https://roms.example.org/$consoleId/$fileName",
        fileSize = 4_000_000L,
        fileExtension = fileName.substringAfterLast('.', ""),
        torrentFileIndex = if (torrent) 0 else null,
        torrentMagnet = if (torrent) MAGNET else null,
        status = status.name,
        startedAt = startedAt,
        finishedAt = if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.STOPPED) startedAt + 500 else null,
        queueOrder = order,
        queueHeld = held
    )

    const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Test%20Set"

    /** Every row of [sql] as column → value (strings, longs, doubles, null). */
    fun SupportSQLiteDatabase.rows(sql: String, vararg args: Any?): List<Map<String, Any?>> =
        query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toMap()) } }

    fun SupportSQLiteDatabase.single(sql: String, vararg args: Any?): Any? =
        query(sql, args).use { c -> if (c.moveToFirst()) c.value(0) else null }

    private fun Cursor.toMap(): Map<String, Any?> = (0 until columnCount).associate { getColumnName(it) to value(it) }

    private fun Cursor.value(i: Int): Any? = when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> getBlob(i)
        else -> getString(i)
    }

    /** Waits (up to [timeoutMs]) until [condition] holds; fails with [message] otherwise. */
    fun eventually(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(25)
        }
        if (!condition()) throw AssertionError("Timed out after $timeoutMs ms: $message")
    }
}
