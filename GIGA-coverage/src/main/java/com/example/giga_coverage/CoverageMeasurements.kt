package com.example.giga_coverage

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthWcdma
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.util.Date
import kotlin.collections.isNotEmpty
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.math.roundToInt
import com.example.giga_coverage.GigaCoverageConfig

class CoverageMeasurements {

    companion object {
        // Define constants for your keys for better maintainability and to avoid typos
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_IS_POSITION_FROM_GPS = "is_position_from_GPS"
        const val KEY_GPS_ACCURACY = "gps_accuracy"
        const val DOWNLOAD_SPEED = "download_speed"
        const val UPLOAD_SPEED = "upload_speed"
        const val KEY_SIGNAL_STRENGTH_DBM = "signal_strength_dbm"
        const val KEY_SIGNAL_STRENGTH_ASU = "signal_strength_asu"
        const val KEY_NETWORK_CODE = "network_code"
        const val KEY_MOBILE_COUNTRY_CODE = "mobile_country_code"
        const val KEY_CELL_ID = "cell_id"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_ANDROID_ID = "android_id"
        const val KEY_APP_NAME = "app_name"
        const val KEY_APP_VERSION = "app_version"
        const val KEY_LIBRARY_VERSION = "library_version"
        const val KEY_NETWORK_TYPE = "network_type"
        const val KEY_DATA_NETWORK_TYPE = "data_network_type"


        private fun measureDownloadSpeed(imageUrl: String): Double? {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(imageUrl)
                .build()
            
            return try {
                val startTime = System.currentTimeMillis()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return null
                    }
                    
                    val responseBody = response.body
                    if (responseBody != null) {
                        val contentLength = responseBody.contentLength()
                        responseBody.bytes()
                        val endTime = System.currentTimeMillis()
                        
                        val durationSeconds = (endTime - startTime) / 1000.0
                        val speedBytesPerSecond = contentLength / durationSeconds
                        val speedKbps = (speedBytesPerSecond * 8 / 1000).roundToInt().toDouble()
                        
                        speedKbps
                    } else {
                        null
                    }
                }
            } catch (e: IOException) {
                null
            } catch (e: Exception) {
                null
            }
        }

        private fun measureUploadSpeed(baseUrl: String, apiKey: String?, testDataSizeBytes: Int = 100000): Double? {
            if (apiKey == null) {
                return null
            }
            val client = OkHttpClient()
            
            try {
                val testData = ByteArray(testDataSizeBytes) { (it % 256).toByte() }
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("api_key", apiKey)
                    .addFormDataPart(
                        "file", "test-upload.bin", 
                        testData.toRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                
                val request = Request.Builder()
                    .url("$baseUrl/api/test-data-upload")
                    .post(requestBody)
                    .build()
                
                val startTime = System.currentTimeMillis()
                client.newCall(request).execute().use { response ->
                    val endTime = System.currentTimeMillis()
                    
                    if (!response.isSuccessful) {
                        return null
                    }
                    
                    val durationSeconds = (endTime - startTime) / 1000.0
                    val speedBytesPerSecond = testDataSizeBytes / durationSeconds
                    val speedKbps = (speedBytesPerSecond * 8 / 1000).roundToInt().toDouble()
                    
                    return speedKbps
                }
            } catch (e: IOException) {
                return null
            } catch (e: Exception) {
                return null
            }
        }

        @SuppressLint("HardwareIds", "MissingPermission")
        fun getCoverageMeasurements(context: Context, apiKey: String? = null, isNetworkAvailable: Boolean = true): Map<String, Any?> {
            // Initialize all expected keys with null so we know what properties to expect
            val data = kotlin.collections.mutableMapOf<String, Any?>(
                KEY_LATITUDE to null,
                KEY_LONGITUDE to null,
                KEY_IS_POSITION_FROM_GPS to null,
                KEY_GPS_ACCURACY to null,
                DOWNLOAD_SPEED to null,
                UPLOAD_SPEED to null,
                KEY_SIGNAL_STRENGTH_DBM to null,
                KEY_SIGNAL_STRENGTH_ASU to null,
                KEY_NETWORK_CODE to null,
                KEY_MOBILE_COUNTRY_CODE to null,
                KEY_CELL_ID to null,
                KEY_TIMESTAMP to null, // Will be overridden
                KEY_ANDROID_ID to null,
                KEY_APP_NAME to null,
                KEY_APP_VERSION to null,
                KEY_LIBRARY_VERSION to null, // Will be overridden
                KEY_NETWORK_TYPE to null,
                KEY_DATA_NETWORK_TYPE to null
            )

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                val location: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                location?.let {
                    data[KEY_LATITUDE] = it.latitude
                    data[KEY_LONGITUDE] = it.longitude
                    data[KEY_IS_POSITION_FROM_GPS] = it.provider == LocationManager.GPS_PROVIDER
                    if (it.provider == LocationManager.GPS_PROVIDER) {
                        data[KEY_GPS_ACCURACY] = it.accuracy
                    }
                }
            }

            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val allCellInfo = telephonyManager.allCellInfo
                if (allCellInfo != null && allCellInfo.isNotEmpty()) {
                    val cellInfo = allCellInfo[0] // Primary cell
                    when (cellInfo) {
                        is CellInfoLte -> {
                            val cellSignalStrengthLte: CellSignalStrengthLte = cellInfo.cellSignalStrength
                            data[KEY_SIGNAL_STRENGTH_DBM] = cellSignalStrengthLte.dbm
                            data[KEY_SIGNAL_STRENGTH_ASU] = cellSignalStrengthLte.asuLevel
                        }
                        is CellInfoGsm -> {
                            val cellSignalStrengthGsm: CellSignalStrengthGsm = cellInfo.cellSignalStrength
                            data[KEY_SIGNAL_STRENGTH_DBM] = cellSignalStrengthGsm.dbm
                            data[KEY_SIGNAL_STRENGTH_ASU] = cellSignalStrengthGsm.asuLevel
                        }
                        is CellInfoWcdma -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                val cellSignalStrengthWcdma: CellSignalStrengthWcdma = cellInfo.cellSignalStrength
                                data[KEY_SIGNAL_STRENGTH_DBM] = cellSignalStrengthWcdma.dbm
                                data[KEY_SIGNAL_STRENGTH_ASU] = cellSignalStrengthWcdma.asuLevel
                            }
                        }
                    }

                    when (cellInfo) {
                        is CellInfoGsm -> {
                            data[KEY_NETWORK_CODE] = cellInfo.cellIdentity.mncString ?: cellInfo.cellIdentity.mnc
                            data[KEY_MOBILE_COUNTRY_CODE] = cellInfo.cellIdentity.mccString ?: cellInfo.cellIdentity.mcc
                            data[KEY_CELL_ID] = cellInfo.cellIdentity.cid
                        }
                        is CellInfoLte -> {
                            data[KEY_NETWORK_CODE] = cellInfo.cellIdentity.mncString ?: cellInfo.cellIdentity.mnc
                            data[KEY_MOBILE_COUNTRY_CODE] = cellInfo.cellIdentity.mccString ?: cellInfo.cellIdentity.mcc
                            data[KEY_CELL_ID] = cellInfo.cellIdentity.ci
                        }
                        is CellInfoWcdma -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                data[KEY_NETWORK_CODE] = cellInfo.cellIdentity.mncString ?: cellInfo.cellIdentity.mnc
                                data[KEY_MOBILE_COUNTRY_CODE] = cellInfo.cellIdentity.mccString ?: cellInfo.cellIdentity.mcc
                                data[KEY_CELL_ID] = cellInfo.cellIdentity.cid
                            }
                        }
                    }
                }
            }

            // Only measure internet speeds if network is available
            if (isNetworkAvailable) {
                data[DOWNLOAD_SPEED] = measureDownloadSpeed(GigaCoverageConfig.imageUrl)
                data[UPLOAD_SPEED] = measureUploadSpeed(GigaCoverageConfig.baseUrl, apiKey)
            } else {
                data[DOWNLOAD_SPEED] = null
                data[UPLOAD_SPEED] = null
            }
            data[KEY_TIMESTAMP] = Date().time // Always set
            data[KEY_ANDROID_ID] = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

            try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                data[KEY_APP_NAME] = context.applicationInfo.loadLabel(context.packageManager).toString()
                data[KEY_APP_VERSION] = pInfo.versionName
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }

            data[KEY_LIBRARY_VERSION] = "v0.1" // Always set
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                data[KEY_NETWORK_TYPE] = getNetworkTypeString(telephonyManager.networkType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    data[KEY_DATA_NETWORK_TYPE] = getNetworkTypeString(telephonyManager.dataNetworkType)
                }
            }

            return data
        }

        private fun getNetworkTypeString(networkType: Int): String {
            return when (networkType) {
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
                TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
                TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
                TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
                TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
                TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
                TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
                TelephonyManager.NETWORK_TYPE_NR -> "NR (5G)"
                else -> "UNKNOWN ($networkType)"
            }
        }
    }
}
