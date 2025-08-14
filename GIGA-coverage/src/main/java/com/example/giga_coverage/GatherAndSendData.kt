package com.example.giga_coverage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import com.example.giga_coverage.GigaCoverageConfig
import com.example.giga_coverage.CoverageMeasurements


class GatherAndSendData(private val context: Context) {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("api_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient()
    private val offlineStorage = OfflineMeasurementStorage(context)

    companion object {
        private const val TAG = "GatherAndSendData"
        private const val PREF_API_KEY = "api_key"
        private const val API_GET_KEY_PATH = "/api/get-key"
        private const val API_SEND_DATA_PATH = "/api/send-data"
        private const val KEY_API_KEY_IN_BODY = "api_key"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private fun getCachedApiKey(): String? {
        return sharedPreferences.getString(PREF_API_KEY, null)
    }

    private fun storeApiKey(apiKey: String) {
        sharedPreferences.edit().putString(PREF_API_KEY, apiKey).apply()
    }
    
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo?.isConnected == true
        }
    }

    @SuppressLint("HardwareIds")
    private suspend fun fetchAndStoreApiKey(): String? {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (androidId == null) {
            Log.e(TAG, "ANDROID_ID is null. Cannot fetch API key.")
            return null
        }

        val jsonBody = JSONObject().put("unique_id", androidId).toString()
        val requestBody = jsonBody.toRequestBody(JSON)
        val request = Request.Builder()
            .url(GigaCoverageConfig.baseUrl + API_GET_KEY_PATH)
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) { // Perform network call on IO dispatcher
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to get API key. Code: ${response.code}")
                        Log.e(TAG, "Response: ${response.body?.string()}")
                        return@withContext null
                    }
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val jsonResponse = JSONObject(responseBody)
                        val apiKey = jsonResponse.optString("api_key", null) // Assuming the key is "api_key"
                        if (apiKey != null) {
                            storeApiKey(apiKey)
                            Log.i(TAG, "API Key fetched and stored successfully.")
                        } else {
                            Log.e(TAG, "API key not found in response: $responseBody")
                        }
                        return@withContext apiKey
                    } else {
                        Log.e(TAG, "Empty response body when fetching API key.")
                        return@withContext null
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "IOException during API key fetch: ${e.message}", e)
                return@withContext null
            } catch (e: Exception) {
                Log.e(TAG, "Exception during API key fetch: ${e.message}", e)
                return@withContext null
            }
        }
    }

    private suspend fun sendMeasurements(apiKey: String, measurements: Map<String, Any?>): Boolean {
        val dataToSend = kotlin.collections.mutableMapOf<String, Any?>()
        dataToSend.putAll(measurements)
        dataToSend[KEY_API_KEY_IN_BODY] = apiKey // Add the API key to the body

        val jsonBody = JSONObject(dataToSend).toString()
        val requestBody = jsonBody.toRequestBody(JSON)

        val request = Request.Builder()
            .url(GigaCoverageConfig.baseUrl + API_SEND_DATA_PATH)
            .post(requestBody)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to send data. Code: ${response.code} - ${response.message}")
                        Log.e(TAG, "Response: ${response.body?.string()}")
                        return@withContext false
                    }
                    Log.i(TAG, "Data sent successfully. Response: ${response.body?.string()}")
                    return@withContext true
                }
            } catch (e: IOException) {
                Log.e(TAG, "IOException during sending data: ${e.message}", e)
                return@withContext false
            } catch (e: Exception) {
                Log.e(TAG, "Exception during sending data: ${e.message}", e)
                return@withContext false
            }
        }
    }
    
    private suspend fun sendStoredMeasurementsOneByOne(apiKey: String): Boolean {
        val storedMeasurements = offlineStorage.getAllPendingMeasurements()
        
        if (storedMeasurements.isEmpty()) {
            return true // No stored measurements, consider success
        }
        
        Log.i(TAG, "Found ${storedMeasurements.size} stored measurements to send")
        
        for (storedMeasurement in storedMeasurements) {
            // Extract the offline metadata
            val offlineId = storedMeasurement["_offline_id"] as? String
            
            // Remove offline metadata before sending
            val cleanMeasurement = storedMeasurement.toMutableMap()
            cleanMeasurement.remove("_offline_id")
            cleanMeasurement.remove("_offline_timestamp")
            
            // Attempt to send this measurement
            val success = sendMeasurements(apiKey, cleanMeasurement)
            
            if (success) {
                // Delete the successfully sent measurement
                if (offlineId != null) {
                    offlineStorage.deleteMeasurement(offlineId)
                    Log.i(TAG, "Successfully sent and deleted stored measurement: $offlineId")
                }
            } else {
                // Stop processing if any measurement fails to send
                Log.w(TAG, "Failed to send stored measurement: $offlineId. Stopping batch send.")
                return false
            }
        }
        
        Log.i(TAG, "Successfully sent all ${storedMeasurements.size} stored measurements")
        return true
    }

    /**
     * Gathers coverage measurements and sends them to the server.
     * This function handles API key retrieval and caching.
     * It's a suspending function and should be called from a coroutine.
     */
    suspend fun processAndSendData() { // Changed to suspend function
        val networkAvailable = isNetworkAvailable()
        Log.i(TAG, "Network available: $networkAvailable")
        
        if (!networkAvailable) {
            // Network is not available, collect measurements offline and store them
            val offlineMeasurements = CoverageMeasurements.getCoverageMeasurements(
                context, 
                apiKey = null, 
                isNetworkAvailable = false
            )
            
            if (offlineMeasurements.isNotEmpty()) {
                offlineStorage.saveMeasurement(offlineMeasurements)
                val storedCount = offlineStorage.getStoredMeasurementsCount()
                Log.i(TAG, "Stored offline measurement. Total stored: $storedCount")
            } else {
                Log.w(TAG, "No offline measurements collected.")
            }
            return
        }
        
        // Network is available, proceed with online logic
        var apiKey = getCachedApiKey()

        if (apiKey == null) {
            Log.i(TAG, "API Key not found in cache. Fetching from server...")
            apiKey = fetchAndStoreApiKey()
        } else {
            Log.i(TAG, "API Key found in cache.")
        }

        if (apiKey == null) {
            Log.e(TAG, "Failed to obtain API Key. Storing measurement offline.")
            // If we can't get API key but have network, still store offline
            val offlineMeasurements = CoverageMeasurements.getCoverageMeasurements(
                context, 
                apiKey = null, 
                isNetworkAvailable = false
            )
            if (offlineMeasurements.isNotEmpty()) {
                offlineStorage.saveMeasurement(offlineMeasurements)
            }
            return
        }

        // Get current measurements with internet speeds
        val measurements = CoverageMeasurements.getCoverageMeasurements(
            context, 
            apiKey, 
            isNetworkAvailable = true
        )
        Log.d(TAG, "Collected measurements: $measurements")

        if (measurements.isEmpty()) {
            Log.w(TAG, "No measurements collected. Nothing to send.")
            return
        }

        // First, try to send stored measurements
        val storedSentSuccessfully = sendStoredMeasurementsOneByOne(apiKey)
        
        // Then send the current measurement
        val currentSentSuccessfully = sendMeasurements(apiKey, measurements)
        
        if (currentSentSuccessfully) {
            Log.i(TAG, "Successfully sent current measurement.")
        } else {
            Log.e(TAG, "Failed to send current measurement. Storing offline.")
            offlineStorage.saveMeasurement(measurements)
        }
        
        if (storedSentSuccessfully && currentSentSuccessfully) {
            Log.i(TAG, "Successfully processed and sent all data.")
        } else if (storedSentSuccessfully) {
            Log.i(TAG, "Sent stored measurements but current measurement failed.")
        } else {
            Log.w(TAG, "Some operations failed. Check individual logs above.")
        }
    }
}
