package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "manufacturers")
data class ManufacturerEntity(
    @PrimaryKey
    val id: String,
    val name: String
)
