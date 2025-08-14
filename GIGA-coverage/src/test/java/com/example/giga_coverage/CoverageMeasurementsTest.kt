package com.example.giga_coverage

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.provider.Settings
import android.telephony.CellInfoLte
import android.telephony.CellSignalStrengthLte
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.*

class CoverageMeasurementsTest {

    private lateinit var mockContext: Context
    private lateinit var mockLocationManager: LocationManager
    private lateinit var mockTelephonyManager: TelephonyManager
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockLocation: Location
    private lateinit var mockCellInfoLte: CellInfoLte
    private lateinit var mockCellSignalStrengthLte: CellSignalStrengthLte
    private lateinit var mockContentResolver: android.content.ContentResolver

    @Before
    fun setUp() {
        mockContext = mockk()
        mockLocationManager = mockk()
        mockTelephonyManager = mockk()
        mockPackageManager = mockk()
        mockLocation = mockk()
        mockCellInfoLte = mockk()
        mockCellSignalStrengthLte = mockk()
        mockContentResolver = mockk()

        mockkStatic(ContextCompat::class)
        mockkStatic(Settings.Secure::class)
        mockkStatic("android.text.TextUtils")
        mockkStatic("android.os.Process")
        mockkObject(GigaCoverageConfig)

        every { android.text.TextUtils.equals(any(), any()) } answers { 
            val arg1 = firstArg<String?>()
            val arg2 = secondArg<String?>()
            arg1 == arg2
        }

        every { android.os.Process.myPid() } returns 1234
        every { android.os.Process.myUid() } returns 5678

        every { mockContext.getSystemService(Context.LOCATION_SERVICE) } returns mockLocationManager
        every { mockContext.getSystemService(Context.TELEPHONY_SERVICE) } returns mockTelephonyManager
        every { mockContext.packageManager } returns mockPackageManager
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.packageName } returns "com.test.app"
        every { mockContext.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        every { GigaCoverageConfig.imageUrl } returns "https://test-image.com/test.jpg"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testGetCoverageMeasurements_WithoutPermissions() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }

        val result = CoverageMeasurements.getCoverageMeasurements(mockContext)

        assertNull(result[CoverageMeasurements.KEY_LATITUDE])
        assertNull(result[CoverageMeasurements.KEY_LONGITUDE])
        assertNull(result[CoverageMeasurements.KEY_SIGNAL_STRENGTH_DBM])
        assertEquals("test-android-id", result[CoverageMeasurements.KEY_ANDROID_ID])
        assertEquals("Test App", result[CoverageMeasurements.KEY_APP_NAME])
        assertEquals("1.0.0", result[CoverageMeasurements.KEY_APP_VERSION])
        assertEquals("v0.1", result[CoverageMeasurements.KEY_LIBRARY_VERSION])
        assertNotNull(result[CoverageMeasurements.KEY_TIMESTAMP])
    }

    @Test
    fun testGetCoverageMeasurements_WithLocationPermission() {
        every { ContextCompat.checkSelfPermission(mockContext, android.Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { ContextCompat.checkSelfPermission(mockContext, android.Manifest.permission.READ_PHONE_STATE) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        every { mockLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } returns mockLocation
        every { mockLocation.latitude } returns 40.7128
        every { mockLocation.longitude } returns -74.0060
        every { mockLocation.provider } returns LocationManager.GPS_PROVIDER
        every { mockLocation.accuracy } returns 5.0f

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }

        val result = CoverageMeasurements.getCoverageMeasurements(mockContext)

        assertEquals(40.7128, result[CoverageMeasurements.KEY_LATITUDE])
        assertEquals(-74.0060, result[CoverageMeasurements.KEY_LONGITUDE])
        assertEquals(true, result[CoverageMeasurements.KEY_IS_POSITION_FROM_GPS])
        assertEquals(5.0f, result[CoverageMeasurements.KEY_GPS_ACCURACY])
    }

    @Test
    fun testGetCoverageMeasurements_WithCellularPermission() {
        every { ContextCompat.checkSelfPermission(mockContext, android.Manifest.permission.ACCESS_FINE_LOCATION) } returns PackageManager.PERMISSION_DENIED
        every { ContextCompat.checkSelfPermission(mockContext, android.Manifest.permission.READ_PHONE_STATE) } returns PackageManager.PERMISSION_GRANTED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        every { mockTelephonyManager.allCellInfo } returns listOf(mockCellInfoLte)
        every { mockCellInfoLte.cellSignalStrength } returns mockCellSignalStrengthLte
        every { mockCellSignalStrengthLte.dbm } returns -85
        every { mockCellSignalStrengthLte.asuLevel } returns 20

        every { mockCellInfoLte.cellIdentity } returns mockk {
            every { mncString } returns "01"
            every { mccString } returns "310"
            every { ci } returns 12345
        }

        every { mockTelephonyManager.networkType } returns TelephonyManager.NETWORK_TYPE_LTE

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }

        val result = CoverageMeasurements.getCoverageMeasurements(mockContext)

        assertEquals(-85, result[CoverageMeasurements.KEY_SIGNAL_STRENGTH_DBM])
        assertEquals(20, result[CoverageMeasurements.KEY_SIGNAL_STRENGTH_ASU])
        assertEquals("01", result[CoverageMeasurements.KEY_NETWORK_CODE])
        assertEquals("310", result[CoverageMeasurements.KEY_MOBILE_COUNTRY_CODE])
        assertEquals(12345, result[CoverageMeasurements.KEY_CELL_ID])
        assertEquals("LTE", result[CoverageMeasurements.KEY_NETWORK_TYPE])
    }

    @Test
    fun testGetNetworkTypeString_KnownTypes() {
        every { ContextCompat.checkSelfPermission(mockContext, android.Manifest.permission.READ_PHONE_STATE) } returns PackageManager.PERMISSION_GRANTED
        every { mockTelephonyManager.networkType } returns TelephonyManager.NETWORK_TYPE_LTE
        every { mockTelephonyManager.allCellInfo } returns emptyList()
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }

        val result = CoverageMeasurements.getCoverageMeasurements(mockContext)
        assertEquals("LTE", result[CoverageMeasurements.KEY_NETWORK_TYPE])
    }

    @Test
    fun testGetCoverageMeasurements_AllRequiredFieldsPresent() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }

        val result = CoverageMeasurements.getCoverageMeasurements(mockContext)

        val expectedKeys = setOf(
            CoverageMeasurements.KEY_LATITUDE,
            CoverageMeasurements.KEY_LONGITUDE,
            CoverageMeasurements.KEY_IS_POSITION_FROM_GPS,
            CoverageMeasurements.KEY_GPS_ACCURACY,
            CoverageMeasurements.DOWNLOAD_SPEED,
            CoverageMeasurements.UPLOAD_SPEED,
            CoverageMeasurements.KEY_SIGNAL_STRENGTH_DBM,
            CoverageMeasurements.KEY_SIGNAL_STRENGTH_ASU,
            CoverageMeasurements.KEY_NETWORK_CODE,
            CoverageMeasurements.KEY_MOBILE_COUNTRY_CODE,
            CoverageMeasurements.KEY_CELL_ID,
            CoverageMeasurements.KEY_TIMESTAMP,
            CoverageMeasurements.KEY_ANDROID_ID,
            CoverageMeasurements.KEY_APP_NAME,
            CoverageMeasurements.KEY_APP_VERSION,
            CoverageMeasurements.KEY_LIBRARY_VERSION,
            CoverageMeasurements.KEY_NETWORK_TYPE,
            CoverageMeasurements.KEY_DATA_NETWORK_TYPE
        )

        assertEquals(expectedKeys, result.keys)
        assertNotNull(result[CoverageMeasurements.KEY_TIMESTAMP])
        assertEquals("v0.1", result[CoverageMeasurements.KEY_LIBRARY_VERSION])
    }

    @Test
    fun testGetCoverageMeasurements_PackageManagerException() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()

        val result = CoverageMeasurements.getCoverageMeasurements(mockContext)

        assertNull(result[CoverageMeasurements.KEY_APP_NAME])
        assertNull(result[CoverageMeasurements.KEY_APP_VERSION])
        assertEquals("v0.1", result[CoverageMeasurements.KEY_LIBRARY_VERSION])
    }

    @Test
    fun testGetCoverageMeasurements_WithApiKey_IncludesUploadSpeed() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }

        val apiKey = "test-api-key-12345"
        val result = CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey)

        assertTrue("Result should contain UPLOAD_SPEED key", result.containsKey(CoverageMeasurements.UPLOAD_SPEED))
    }

    @Test
    fun testGetCoverageMeasurements_WithoutApiKey_UploadSpeedIsNull() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }

        val result = CoverageMeasurements.getCoverageMeasurements(mockContext)

        assertNull("UPLOAD_SPEED should be null when no API key is provided", result[CoverageMeasurements.UPLOAD_SPEED])
        assertTrue("Result should still contain UPLOAD_SPEED key", result.containsKey(CoverageMeasurements.UPLOAD_SPEED))
    }
    
    @Test
    fun testGetCoverageMeasurements_NetworkAvailable_IncludesSpeedMeasurements() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }
        
        val apiKey = "test-api-key"
        val result = CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, isNetworkAvailable = true)

        // When network is available, speed measurements should be attempted (even if they fail/return null)
        assertTrue("Result should contain DOWNLOAD_SPEED key", result.containsKey(CoverageMeasurements.DOWNLOAD_SPEED))
        assertTrue("Result should contain UPLOAD_SPEED key", result.containsKey(CoverageMeasurements.UPLOAD_SPEED))
    }
    
    @Test
    fun testGetCoverageMeasurements_NetworkUnavailable_SpeedMeasurementsNull() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }
        
        val apiKey = "test-api-key"
        val result = CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, isNetworkAvailable = false)

        // When network is unavailable, speed measurements should be explicitly null
        assertNull("DOWNLOAD_SPEED should be null when network unavailable", result[CoverageMeasurements.DOWNLOAD_SPEED])
        assertNull("UPLOAD_SPEED should be null when network unavailable", result[CoverageMeasurements.UPLOAD_SPEED])
        assertTrue("Result should still contain DOWNLOAD_SPEED key", result.containsKey(CoverageMeasurements.DOWNLOAD_SPEED))
        assertTrue("Result should still contain UPLOAD_SPEED key", result.containsKey(CoverageMeasurements.UPLOAD_SPEED))
    }
    
    @Test
    fun testGetCoverageMeasurements_NetworkUnavailable_OtherFieldsStillPopulated() {
        every { ContextCompat.checkSelfPermission(mockContext, any()) } returns PackageManager.PERMISSION_DENIED
        every { Settings.Secure.getString(any(), Settings.Secure.ANDROID_ID) } returns "test-android-id"

        val mockPackageInfo = mockk<PackageInfo>()
        mockPackageInfo.versionName = "1.0.0"
        every { mockPackageManager.getPackageInfo(any<String>(), any<Int>()) } returns mockPackageInfo
        every { mockContext.applicationInfo } returns mockk {
            every { loadLabel(mockPackageManager) } returns "Test App"
        }
        
        val result = CoverageMeasurements.getCoverageMeasurements(mockContext, isNetworkAvailable = false)

        // Verify non-speed fields are still populated correctly
        assertEquals("test-android-id", result[CoverageMeasurements.KEY_ANDROID_ID])
        assertEquals("Test App", result[CoverageMeasurements.KEY_APP_NAME])
        assertEquals("1.0.0", result[CoverageMeasurements.KEY_APP_VERSION])
        assertEquals("v0.1", result[CoverageMeasurements.KEY_LIBRARY_VERSION])
        assertNotNull(result[CoverageMeasurements.KEY_TIMESTAMP])
        
        // But speed measurements should be null
        assertNull(result[CoverageMeasurements.DOWNLOAD_SPEED])
        assertNull(result[CoverageMeasurements.UPLOAD_SPEED])
    }
}