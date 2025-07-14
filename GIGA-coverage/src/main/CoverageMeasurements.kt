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

class CoverageMeasurements { // No primary constructor needed for the class itself now

    companion object {
        @SuppressLint("HardwareIds", "MissingPermission")
        fun getCoverageMeasurements(context: Context): Map<String, Any?> { // Pass Context as a parameter
            val data = kotlin.collections.mutableMapOf<String, Any?>()

            // Location data
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                val location: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                location?.let {
                    data["latitude"] = it.latitude
                    data["longitude"] = it.longitude
                    data["is_position_from_GPS"] = it.provider == LocationManager.GPS_PROVIDER
                    if (it.provider == LocationManager.GPS_PROVIDER) {
                        data["gps_accuracy"] = it.accuracy // Accuracy in meters
                    }
                }
            }

            // Telephony data
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                // Phone signal strength
                val allCellInfo = telephonyManager.allCellInfo
                if (allCellInfo != null && allCellInfo.isNotEmpty()) {
                    val cellInfo = allCellInfo[0] // Primary cell
                    when (cellInfo) {
                        is CellInfoLte -> {
                            val cellSignalStrengthLte: CellSignalStrengthLte = cellInfo.cellSignalStrength
                            data["signal_strength_dbm"] = cellSignalStrengthLte.dbm
                            data["signal_strength_asu"] = cellSignalStrengthLte.asuLevel
                        }
                        is CellInfoGsm -> {
                            val cellSignalStrengthGsm: CellSignalStrengthGsm = cellInfo.cellSignalStrength
                            data["signal_strength_dbm"] = cellSignalStrengthGsm.dbm
                            data["signal_strength_asu"] = cellSignalStrengthGsm.asuLevel
                        }
                        is CellInfoWcdma -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                val cellSignalStrengthWcdma: CellSignalStrengthWcdma = cellInfo.cellSignalStrength
                                data["signal_strength_dbm"] = cellSignalStrengthWcdma.dbm
                                data["signal_strength_asu"] = cellSignalStrengthWcdma.asuLevel
                            }
                        }
                    }

                    // Network Code, Mobile Country Code, and Cell ID
                    when (cellInfo) {
                        is CellInfoGsm -> {
                            data["network_code"] = cellInfo.cellIdentity.mncString ?: cellInfo.cellIdentity.mnc
                            data["mobile_country_code"] = cellInfo.cellIdentity.mccString ?: cellInfo.cellIdentity.mcc
                            data["cell_id"] = cellInfo.cellIdentity.cid
                        }
                        is CellInfoLte -> {
                            data["network_code"] = cellInfo.cellIdentity.mncString ?: cellInfo.cellIdentity.mnc
                            data["mobile_country_code"] = cellInfo.cellIdentity.mccString ?: cellInfo.cellIdentity.mcc
                            data["cell_id"] = cellInfo.cellIdentity.ci
                        }
                        is CellInfoWcdma -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                                data["network_code"] = cellInfo.cellIdentity.mncString ?: cellInfo.cellIdentity.mnc
                                data["mobile_country_code"] = cellInfo.cellIdentity.mccString ?: cellInfo.cellIdentity.mcc
                                data["cell_id"] = cellInfo.cellIdentity.cid
                            }
                        }
                    }
                }
            }

            // Timestamp
            data["timestamp"] = Date().time

            // ANDROID_ID
            data["android_id"] = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

            // App info
            try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                data["app_name"] = context.applicationInfo.loadLabel(context.packageManager).toString()
                data["app_version"] = pInfo.versionName
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }

            // Library version
            data["library_version"] = "v0.1"

            // Network Type
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                data["network_type"] = getNetworkTypeString(telephonyManager.networkType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    data["data_network_type"] = getNetworkTypeString(telephonyManager.dataNetworkType)
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