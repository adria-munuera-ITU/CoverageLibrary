package com.example.giga_coverage.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "offline_measurements")
data class OfflineMeasurement(
    @PrimaryKey 
    val id: String = UUID.randomUUID().toString(),
    val measurementJson: String,
    val timestamp: Long = System.currentTimeMillis()
)