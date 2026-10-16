package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cortinadev.dogmatix.data.local.entity.StorageRedirectEntity

@Dao
interface StorageRedirectDao {

    @Query("SELECT * FROM storage_redirects WHERE fileName = :fileName")
    suspend fun get(fileName: String): StorageRedirectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(redirect: StorageRedirectEntity)

    @Query("DELETE FROM storage_redirects WHERE fileName = :fileName")
    suspend fun delete(fileName: String)

    /** After a console's folder moved, its files are where the normal resolution looks. */
    @Query("DELETE FROM storage_redirects WHERE consoleId IN (:consoleIds)")
    suspend fun deleteForConsoles(consoleIds: List<String>)

    /** The library root moved: redirects into it follow. */
    @Query("UPDATE storage_redirects SET rootUri = :newRoot WHERE rootUri = :oldRoot")
    suspend fun rebaseRoot(oldRoot: String, newRoot: String)

    @Query("SELECT * FROM storage_redirects")
    suspend fun getAll(): List<StorageRedirectEntity>
}
