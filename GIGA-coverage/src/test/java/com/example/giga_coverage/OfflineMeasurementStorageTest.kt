package com.example.giga_coverage

import android.content.Context
import com.example.giga_coverage.data.AppDatabase
import com.example.giga_coverage.data.OfflineMeasurement
import com.example.giga_coverage.data.OfflineMeasurementDao
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28]) // API 28 for Room database compatibility
class OfflineMeasurementStorageTest {

    private lateinit var mockContext: Context
    private lateinit var mockDatabase: AppDatabase
    private lateinit var mockDao: OfflineMeasurementDao
    private lateinit var offlineStorage: OfflineMeasurementStorage

    @Before
    fun setUp() {
        mockContext = mockk()
        mockDatabase = mockk()
        mockDao = mockk(relaxed = true)
        
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(mockContext) } returns mockDatabase
        every { mockDatabase.offlineMeasurementDao() } returns mockDao
        
        offlineStorage = OfflineMeasurementStorage(mockContext)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testSaveMeasurement_Success() = runTest {
        val measurements = mapOf(
            "latitude" to 40.7128,
            "longitude" to -74.0060,
            "signal_strength_dbm" to -85,
            "timestamp" to 1234567890L
        )
        
        coEvery { mockDao.getCount() } returns 0
        coEvery { mockDao.insertMeasurement(any()) } just runs

        offlineStorage.saveMeasurement(measurements)

        coVerify(exactly = 1) { mockDao.getCount() }
        coVerify(exactly = 1) { 
            mockDao.insertMeasurement(match<OfflineMeasurement> { measurement ->
                measurement.measurementJson.contains("\"latitude\":40.7128") &&
                measurement.measurementJson.contains("\"longitude\":-74.006") &&
                measurement.measurementJson.contains("\"signal_strength_dbm\":-85")
            })
        }
        coVerify(exactly = 0) { mockDao.deleteOldestMeasurements(any()) }
    }

    @Test
    fun testSaveMeasurement_AtMaxLimit_DeletesOldest() = runTest {
        val measurements = mapOf("test_key" to "test_value")
        
        coEvery { mockDao.getCount() } returns OfflineMeasurementStorage.MAX_STORED_MEASUREMENTS
        coEvery { mockDao.deleteOldestMeasurements(any()) } just runs
        coEvery { mockDao.insertMeasurement(any()) } just runs

        offlineStorage.saveMeasurement(measurements)

        coVerify(exactly = 1) { mockDao.deleteOldestMeasurements(1) }
        coVerify(exactly = 1) { mockDao.insertMeasurement(any()) }
    }

    @Test
    fun testSaveMeasurement_ExceedsMaxLimit_DeletesMultiple() = runTest {
        val measurements = mapOf("test_key" to "test_value")
        
        coEvery { mockDao.getCount() } returns OfflineMeasurementStorage.MAX_STORED_MEASUREMENTS + 5
        coEvery { mockDao.deleteOldestMeasurements(any()) } just runs
        coEvery { mockDao.insertMeasurement(any()) } just runs

        offlineStorage.saveMeasurement(measurements)

        coVerify(exactly = 1) { mockDao.deleteOldestMeasurements(6) }
        coVerify(exactly = 1) { mockDao.insertMeasurement(any()) }
    }

    @Test
    fun testGetAllPendingMeasurements_Empty() = runTest {
        coEvery { mockDao.getAllMeasurements() } returns emptyList()

        val result = offlineStorage.getAllPendingMeasurements()

        assertTrue("Result should be empty", result.isEmpty())
        coVerify(exactly = 1) { mockDao.getAllMeasurements() }
    }

    @Test
    fun testGetAllPendingMeasurements_WithData() = runTest {
        val storedMeasurement1 = OfflineMeasurement(
            id = "measurement-1",
            measurementJson = """{"latitude":40.7128,"longitude":-74.006,"signal_strength_dbm":-85}""",
            timestamp = System.currentTimeMillis() - 1000
        )
        val storedMeasurement2 = OfflineMeasurement(
            id = "measurement-2", 
            measurementJson = """{"latitude":37.7749,"longitude":-122.4194,"signal_strength_dbm":-90}""",
            timestamp = System.currentTimeMillis()
        )
        
        coEvery { mockDao.getAllMeasurements() } returns listOf(storedMeasurement1, storedMeasurement2)

        val result = offlineStorage.getAllPendingMeasurements()

        assertEquals("Should return 2 measurements", 2, result.size)
        
        // Verify first measurement
        val first = result[0]
        assertEquals(40.7128, first["latitude"])
        assertEquals(-74.006, first["longitude"])
        assertEquals(-85, first["signal_strength_dbm"])
        assertEquals("measurement-1", first["_offline_id"])
        assertEquals(storedMeasurement1.timestamp, first["_offline_timestamp"])
        
        // Verify second measurement
        val second = result[1]
        assertEquals(37.7749, second["latitude"])
        assertEquals(-122.4194, second["longitude"])
        assertEquals(-90, second["signal_strength_dbm"])
        assertEquals("measurement-2", second["_offline_id"])
        assertEquals(storedMeasurement2.timestamp, second["_offline_timestamp"])
    }

    @Test
    fun testGetAllPendingMeasurements_HandlesNullValues() = runTest {
        val storedMeasurement = OfflineMeasurement(
            id = "measurement-1",
            measurementJson = """{"latitude":null,"longitude":-74.006,"signal_strength_dbm":-85}""",
            timestamp = System.currentTimeMillis()
        )
        
        coEvery { mockDao.getAllMeasurements() } returns listOf(storedMeasurement)

        val result = offlineStorage.getAllPendingMeasurements()

        assertEquals("Should return 1 measurement", 1, result.size)
        
        val measurement = result[0]
        assertNull("Latitude should be null", measurement["latitude"])
        assertEquals(-74.006, measurement["longitude"])
        assertEquals(-85, measurement["signal_strength_dbm"])
    }

    @Test
    fun testDeleteMeasurement_Success() = runTest {
        val measurementId = "measurement-to-delete"
        
        coEvery { mockDao.deleteMeasurement(measurementId) } just runs

        offlineStorage.deleteMeasurement(measurementId)

        coVerify(exactly = 1) { mockDao.deleteMeasurement(measurementId) }
    }

    @Test
    fun testDeleteAllMeasurements_Success() = runTest {
        coEvery { mockDao.deleteAllMeasurements() } just runs

        offlineStorage.deleteAllMeasurements()

        coVerify(exactly = 1) { mockDao.deleteAllMeasurements() }
    }

    @Test
    fun testGetStoredMeasurementsCount_ReturnsCorrectCount() = runTest {
        val expectedCount = 42
        coEvery { mockDao.getCount() } returns expectedCount

        val result = offlineStorage.getStoredMeasurementsCount()

        assertEquals("Should return correct count", expectedCount, result)
        coVerify(exactly = 1) { mockDao.getCount() }
    }

    @Test
    fun testSaveMeasurement_ComplexMeasurements() = runTest {
        val complexMeasurements = mapOf(
            "latitude" to 40.7128,
            "longitude" to -74.0060,
            "signal_strength_dbm" to -85,
            "signal_strength_asu" to 20,
            "network_code" to "310",
            "mobile_country_code" to "260",
            "cell_id" to 12345,
            "timestamp" to 1234567890L,
            "android_id" to "test-android-id",
            "app_name" to "Test App",
            "app_version" to "1.0.0",
            "library_version" to "v0.1",
            "network_type" to "LTE",
            "download_speed" to null,
            "upload_speed" to null,
            "is_position_from_GPS" to true,
            "gps_accuracy" to 5.0f
        )
        
        coEvery { mockDao.getCount() } returns 0
        coEvery { mockDao.insertMeasurement(any()) } just runs

        offlineStorage.saveMeasurement(complexMeasurements)

        coVerify(exactly = 1) { 
            mockDao.insertMeasurement(match<OfflineMeasurement> { measurement ->
                // Verify the JSON contains all expected fields
                val json = measurement.measurementJson
                json.contains("\"latitude\":40.7128") &&
                json.contains("\"longitude\":-74.006") &&
                json.contains("\"signal_strength_dbm\":-85") &&
                json.contains("\"signal_strength_asu\":20") &&
                json.contains("\"network_code\":\"310\"") &&
                json.contains("\"cell_id\":12345") &&
                json.contains("\"android_id\":\"test-android-id\"") &&
                json.contains("\"app_name\":\"Test App\"") &&
                json.contains("\"network_type\":\"LTE\"") &&
                json.contains("\"download_speed\":null") &&
                json.contains("\"upload_speed\":null") &&
                json.contains("\"is_position_from_GPS\":true")
            })
        }
    }

    @Test
    fun testStorageLimitsEnforced() = runTest {
        assertEquals("MAX_STORED_MEASUREMENTS should be 100", 100, OfflineMeasurementStorage.MAX_STORED_MEASUREMENTS)
    }

    @Test
    fun testSaveMeasurement_EmptyMeasurements() = runTest {
        val emptyMeasurements = emptyMap<String, Any?>()
        
        coEvery { mockDao.getCount() } returns 0
        coEvery { mockDao.insertMeasurement(any()) } just runs

        offlineStorage.saveMeasurement(emptyMeasurements)

        coVerify(exactly = 1) { 
            mockDao.insertMeasurement(match<OfflineMeasurement> { measurement ->
                measurement.measurementJson == "{}"
            })
        }
    }

    @Test
    fun testGetAllPendingMeasurements_PreservesOrder() = runTest {
        val measurement1 = OfflineMeasurement(
            id = "measurement-1",
            measurementJson = """{"order": 1}""",
            timestamp = System.currentTimeMillis() - 2000
        )
        val measurement2 = OfflineMeasurement(
            id = "measurement-2",
            measurementJson = """{"order": 2}""",
            timestamp = System.currentTimeMillis() - 1000
        )
        val measurement3 = OfflineMeasurement(
            id = "measurement-3",
            measurementJson = """{"order": 3}""",
            timestamp = System.currentTimeMillis()
        )
        
        coEvery { mockDao.getAllMeasurements() } returns listOf(measurement1, measurement2, measurement3)

        val result = offlineStorage.getAllPendingMeasurements()

        assertEquals("Should return measurements in order", 3, result.size)
        assertEquals(1, result[0]["order"])
        assertEquals(2, result[1]["order"])
        assertEquals(3, result[2]["order"])
    }
}