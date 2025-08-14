package com.example.giga_coverage

import android.content.Context
import com.example.giga_coverage.data.AppDatabase
import com.example.giga_coverage.data.OfflineMeasurement
import org.json.JSONObject

class OfflineMeasurementStorage(context: Context) {
    
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.offlineMeasurementDao()
    
    companion object {
        const val MAX_STORED_MEASUREMENTS = 100
    }
    
    suspend fun saveMeasurement(measurements: Map<String, Any?>) {
        val measurementJson = JSONObject(measurements).toString()
        val offlineMeasurement = OfflineMeasurement(
            measurementJson = measurementJson
        )
        
        // Check if we're at the limit and remove oldest if necessary
        val currentCount = dao.getCount()
        if (currentCount >= MAX_STORED_MEASUREMENTS) {
            val excessCount = currentCount - MAX_STORED_MEASUREMENTS + 1
            dao.deleteOldestMeasurements(excessCount)
        }
        dao.insertMeasurement(offlineMeasurement)
    }
    
    suspend fun getAllPendingMeasurements(): List<Map<String, Any?>> {
        val storedMeasurements = dao.getAllMeasurements()
        return storedMeasurements.map { offlineMeasurement ->
            val measurementMap = mutableMapOf<String, Any?>()
            val jsonObject = JSONObject(offlineMeasurement.measurementJson)
            
            // Convert JSONObject back to Map
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                measurementMap[key] = if (jsonObject.isNull(key)) {
                    null
                } else {
                    jsonObject.get(key)
                }
            }
            
            // Add storage metadata for potential debugging/tracking
            measurementMap["_offline_id"] = offlineMeasurement.id
            measurementMap["_offline_timestamp"] = offlineMeasurement.timestamp
            
            measurementMap
        }
    }
    
    suspend fun deleteMeasurement(measurementId: String) {
        dao.deleteMeasurement(measurementId)
    }
    
    suspend fun deleteAllMeasurements() {
        dao.deleteAllMeasurements()
    }
    
    suspend fun getStoredMeasurementsCount(): Int {
        return dao.getCount()
    }
}