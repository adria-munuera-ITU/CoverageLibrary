package com.example.giga_coverage.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OfflineMeasurementDao {
    
    @Insert
    suspend fun insertMeasurement(measurement: OfflineMeasurement)
    
    @Query("SELECT * FROM offline_measurements ORDER BY timestamp ASC")
    suspend fun getAllMeasurements(): List<OfflineMeasurement>
    
    @Query("DELETE FROM offline_measurements WHERE id = :measurementId")
    suspend fun deleteMeasurement(measurementId: String)
    
    @Query("DELETE FROM offline_measurements")
    suspend fun deleteAllMeasurements()
    
    @Query("SELECT COUNT(*) FROM offline_measurements")
    suspend fun getCount(): Int
    
    @Query("DELETE FROM offline_measurements WHERE id IN (SELECT id FROM offline_measurements ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldestMeasurements(count: Int)
}