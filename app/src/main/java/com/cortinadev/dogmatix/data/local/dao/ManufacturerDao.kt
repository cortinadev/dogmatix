package com.cortinadev.dogmatix.data.local.dao

import androidx.room.*
import com.cortinadev.dogmatix.data.local.entity.ManufacturerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ManufacturerDao {
    
    @Query("SELECT * FROM manufacturers ORDER BY name ASC")
    fun getAllManufacturers(): Flow<List<ManufacturerEntity>>
    
    @Query("SELECT * FROM manufacturers WHERE id = :id")
    suspend fun getManufacturerById(id: String): ManufacturerEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManufacturer(manufacturer: ManufacturerEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManufacturers(manufacturers: List<ManufacturerEntity>)
    
    @Update
    suspend fun updateManufacturer(manufacturer: ManufacturerEntity)
    
    @Delete
    suspend fun deleteManufacturer(manufacturer: ManufacturerEntity)
    
    @Query("DELETE FROM manufacturers WHERE id = :id")
    suspend fun deleteManufacturerById(id: String)
    
    @Query("DELETE FROM manufacturers")
    suspend fun clearAll()
}
