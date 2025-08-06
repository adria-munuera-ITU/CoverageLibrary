package com.example.giga_coverage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
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

    /**
     * Gathers coverage measurements and sends them to the server.
     * This function handles API key retrieval and caching.
     * It's a suspending function and should be called from a coroutine.
     */
    suspend fun processAndSendData() { // Changed to suspend function
        var apiKey = getCachedApiKey()

        if (apiKey == null) {
            Log.i(TAG, "API Key not found in cache. Fetching from server...")
            apiKey = fetchAndStoreApiKey()
        } else {
            Log.i(TAG, "API Key found in cache.")
        }

        if (apiKey == null) {
            Log.e(TAG, "Failed to obtain API Key. Cannot send data.")
            return
        }

        // Get coverage measurements
        // This is a synchronous call as per your CoverageMeasurements class design
        val measurements = CoverageMeasurements.getCoverageMeasurements(context)
        Log.d(TAG, "Collected measurements: $measurements")

        if (measurements.isEmpty()) {
            Log.w(TAG, "No measurements collected. Nothing to send.")
            return
        }

        // Send the data
        val success = sendMeasurements(apiKey, measurements)
        if (success) {
            Log.i(TAG, "Successfully processed and sent data.")
        } else {
            Log.e(TAG, "Failed to process and send data.")
        }
    }
}
