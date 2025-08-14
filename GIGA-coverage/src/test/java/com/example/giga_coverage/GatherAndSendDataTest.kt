package com.example.giga_coverage

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.os.Build

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.M]) // API 23 - ensures getActiveNetwork() is available
class GatherAndSendDataTest {

    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockConnectivityManager: ConnectivityManager
    private lateinit var mockNetwork: android.net.Network
    private lateinit var mockNetworkCapabilities: NetworkCapabilities
    private lateinit var gatherAndSendData: GatherAndSendData
    private lateinit var mockWebServer: MockWebServer
    private lateinit var mockOfflineStorage: OfflineMeasurementStorage

    @Before
    fun setUp() {
        mockContext = spyk(RuntimeEnvironment.getApplication())
        mockSharedPreferences = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        mockConnectivityManager = mockk(relaxed = true)
        mockNetwork = mockk(relaxed = true)
        mockNetworkCapabilities = mockk(relaxed = true)
        mockWebServer = MockWebServer()
        mockOfflineStorage = mockk(relaxed = true)

        mockkObject(GigaCoverageConfig)
        mockkObject(CoverageMeasurements)
        mockkConstructor(OfflineMeasurementStorage::class)

        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            "test-android-id"
        )

        every { mockContext.getSharedPreferences("api_prefs", Context.MODE_PRIVATE) } returns mockSharedPreferences
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just runs
        
        // Mock network connectivity (default to online)
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockNetworkCapabilities
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
        
        // Mock offline storage
        coEvery { anyConstructed<OfflineMeasurementStorage>().getAllPendingMeasurements() } returns emptyList()
        coEvery { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(any()) } just runs
        coEvery { anyConstructed<OfflineMeasurementStorage>().getStoredMeasurementsCount() } returns 0
        
        mockWebServer.start()
        every { GigaCoverageConfig.baseUrl } returns mockWebServer.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkAll()
    }

    @Test
    fun testProcessAndSendData_WithCachedApiKey_Success() = runTest {
        val apiKey = "cached-api-key"
        val testMeasurements = mapOf(
            "test_key" to "test_value",
            "timestamp" to System.currentTimeMillis()
        )

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns testMeasurements

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status": "success"}""")
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        val request = mockWebServer.takeRequest()
        assertEquals("/api/send-data", request.path)
        assertEquals("POST", request.method)

        val requestBody = JSONObject(request.body.readUtf8())
        assertEquals(apiKey, requestBody.getString("api_key"))
        assertEquals("test_value", requestBody.getString("test_key"))
    }

    @Test
    fun testProcessAndSendData_WithoutCachedApiKey_FetchAndSuccess() = runTest {
        val androidId = "test-android-id"
        val newApiKey = "new-api-key"
        val testMeasurements = mapOf("test_key" to "test_value")

        every { mockSharedPreferences.getString("api_key", null) } returns null
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            androidId
        )
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, newApiKey, true) } returns testMeasurements

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"api_key": "$newApiKey"}""")
        )
        
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status": "success"}""")
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        val getKeyRequest = mockWebServer.takeRequest()
        assertEquals("/api/get-key", getKeyRequest.path)
        assertEquals("POST", getKeyRequest.method)

        val getKeyBody = JSONObject(getKeyRequest.body.readUtf8())
        assertEquals(androidId, getKeyBody.getString("unique_id"))

        verify { mockEditor.putString("api_key", newApiKey) }
        verify { mockEditor.apply() }

        val sendDataRequest = mockWebServer.takeRequest()
        assertEquals("/api/send-data", sendDataRequest.path)
        assertEquals("POST", sendDataRequest.method)

        val sendDataBody = JSONObject(sendDataRequest.body.readUtf8())
        assertEquals(newApiKey, sendDataBody.getString("api_key"))
    }

    @Test
    fun testProcessAndSendData_ApiKeyFetchFails() = runTest {
        val androidId = "test-android-id"
        val offlineMeasurements = mapOf("test_key" to "offline_value")

        every { mockSharedPreferences.getString("api_key", null) } returns null
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            androidId
        )
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) } returns offlineMeasurements

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error": "Bad request"}""")
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        val getKeyRequest = mockWebServer.takeRequest()
        assertEquals("/api/get-key", getKeyRequest.path)

        // Verify offline measurements were collected and stored
        verify(exactly = 1) { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) }
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(offlineMeasurements) }
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun testProcessAndSendData_SendDataFails() = runTest {
        val apiKey = "cached-api-key"
        val testMeasurements = mapOf("test_key" to "test_value")

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns testMeasurements

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error": "Internal server error"}""")
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        val request = mockWebServer.takeRequest()
        assertEquals("/api/send-data", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun testProcessAndSendData_EmptyMeasurements() = runTest {
        val apiKey = "cached-api-key"
        val emptyMeasurements = emptyMap<String, Any?>()

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns emptyMeasurements

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun testProcessAndSendData_AndroidIdNull() = runTest {
        val offlineMeasurements = mapOf("test_key" to "offline_value")
        
        every { mockSharedPreferences.getString("api_key", null) } returns null
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            null
        )
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) } returns offlineMeasurements

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // No network requests should be made
        assertEquals(0, mockWebServer.requestCount)
        
        // But offline measurements should be collected and stored
        verify(exactly = 1) { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) }
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(offlineMeasurements) }
    }

    @Test
    fun testProcessAndSendData_NetworkException() = runTest {
        val apiKey = "cached-api-key"
        val testMeasurements = mapOf("test_key" to "test_value")

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns testMeasurements

        mockWebServer.shutdown()

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()
    }

    @Test
    fun testProcessAndSendData_InvalidApiKeyResponse() = runTest {
        val androidId = "test-android-id"
        val offlineMeasurements = mapOf("test_key" to "offline_value")

        every { mockSharedPreferences.getString("api_key", null) } returns null
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            androidId
        )
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) } returns offlineMeasurements

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status": "success"}""")
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // API key should not be stored since response didn't contain api_key
        verify(exactly = 0) { mockEditor.putString(any(), any()) }
        
        // But offline measurements should be collected and stored
        verify(exactly = 1) { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) }
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(offlineMeasurements) }
    }

    @Test
    fun testProcessAndSendData_OfflineMode_StoresDataLocally() = runTest {
        val offlineMeasurements = mapOf(
            "latitude" to 40.7128,
            "longitude" to -74.0060,
            "signal_strength_dbm" to -85,
            "timestamp" to System.currentTimeMillis()
        )

        // Mock network as unavailable
        every { mockConnectivityManager.activeNetwork } returns null
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) } returns offlineMeasurements

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // No network requests should be made
        assertEquals(0, mockWebServer.requestCount)
        
        // Offline measurements should be collected and stored
        verify(exactly = 1) { CoverageMeasurements.getCoverageMeasurements(mockContext, null, false) }
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(offlineMeasurements) }
    }

    @Test
    fun testProcessAndSendData_OnlineWithStoredData_SendsBothStoredAndCurrent() = runTest {
        val apiKey = "test-api-key"
        val storedMeasurements = listOf(
            mapOf(
                "_offline_id" to "stored-1",
                "_offline_timestamp" to System.currentTimeMillis() - 3600000,
                "latitude" to 37.7749,
                "signal_strength_dbm" to -90
            ),
            mapOf(
                "_offline_id" to "stored-2", 
                "_offline_timestamp" to System.currentTimeMillis() - 1800000,
                "latitude" to 34.0522,
                "signal_strength_dbm" to -88
            )
        )
        val currentMeasurements = mapOf(
            "latitude" to 40.7128,
            "signal_strength_dbm" to -85
        )

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns currentMeasurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().getAllPendingMeasurements() } returns storedMeasurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement(any()) } just runs

        // Mock successful responses for all requests
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))  // stored-1
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))  // stored-2
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))  // current

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // Should make 3 requests total
        assertEquals(3, mockWebServer.requestCount)
        
        // Verify stored measurements were sent and deleted
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement("stored-1") }
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement("stored-2") }
        
        // Verify the request order and content
        val request1 = mockWebServer.takeRequest()
        val request2 = mockWebServer.takeRequest()
        val request3 = mockWebServer.takeRequest()
        
        assertEquals("/api/send-data", request1.path)
        assertEquals("/api/send-data", request2.path)
        assertEquals("/api/send-data", request3.path)
    }

    @Test
    fun testProcessAndSendData_PartialStoredDataSuccess_OnlySuccessfulDeleted() = runTest {
        val apiKey = "test-api-key"
        val storedMeasurements = listOf(
            mapOf(
                "_offline_id" to "stored-1",
                "latitude" to 37.7749
            ),
            mapOf(
                "_offline_id" to "stored-2", 
                "latitude" to 34.0522
            )
        )
        val currentMeasurements = mapOf("latitude" to 40.7128)

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns currentMeasurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().getAllPendingMeasurements() } returns storedMeasurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement(any()) } just runs
        coEvery { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(any()) } just runs

        // First stored measurement succeeds, second fails, but current measurement succeeds
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))  // stored-1
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "server error"}"""))  // stored-2 fails
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))  // current succeeds

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // Should make 3 requests total (stored-1, stored-2, current)
        assertEquals(3, mockWebServer.requestCount)
        
        // Only first stored measurement should be deleted (second failed to send)
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement("stored-1") }
        coVerify(exactly = 0) { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement("stored-2") }
        
        // Current measurement should NOT be stored offline since it sent successfully
        coVerify(exactly = 0) { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(currentMeasurements) }
    }

    @Test
    fun testProcessAndSendData_StoredDataSuccessButCurrentFails() = runTest {
        val apiKey = "test-api-key"
        val storedMeasurements = listOf(
            mapOf(
                "_offline_id" to "stored-1",
                "latitude" to 37.7749
            )
        )
        val currentMeasurements = mapOf("latitude" to 40.7128)

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns currentMeasurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().getAllPendingMeasurements() } returns storedMeasurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement(any()) } just runs
        coEvery { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(any()) } just runs

        // Stored measurement succeeds, but current measurement fails
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))  // stored-1 succeeds
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("""{"error": "server error"}"""))  // current fails

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // Should make 2 requests total
        assertEquals(2, mockWebServer.requestCount)
        
        // Stored measurement should be deleted since it was sent successfully
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().deleteMeasurement("stored-1") }
        
        // Current measurement should be stored offline since it failed to send
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(currentMeasurements) }
    }

    @Test
    fun testProcessAndSendData_NetworkAvailableButServerUnreachable_StoresOffline() = runTest {
        val apiKey = "cached-api-key"
        val measurements = mapOf("test_key" to "test_value")

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns measurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().getAllPendingMeasurements() } returns emptyList()
        coEvery { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(any()) } just runs

        // Simulate network available but server unreachable by shutting down mock server
        mockWebServer.shutdown()

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // Measurement should be stored offline when network fails
        coVerify(exactly = 1) { anyConstructed<OfflineMeasurementStorage>().saveMeasurement(measurements) }
    }

    @Test
    fun testProcessAndSendData_MixedNetworkTypes_WiFi() = runTest {
        val apiKey = "test-api-key"
        val measurements = mapOf("connection_type" to "wifi")

        // Mock WiFi connection
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockNetworkCapabilities
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns false
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
        
        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns measurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().getAllPendingMeasurements() } returns emptyList()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // Should behave as online
        assertEquals(1, mockWebServer.requestCount)
        verify(exactly = 1) { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) }
    }

    @Test
    fun testProcessAndSendData_MixedNetworkTypes_Cellular() = runTest {
        val apiKey = "test-api-key"
        val measurements = mapOf("connection_type" to "cellular")

        // Mock cellular connection  
        every { mockConnectivityManager.activeNetwork } returns mockNetwork
        every { mockConnectivityManager.getNetworkCapabilities(mockNetwork) } returns mockNetworkCapabilities
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns false
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } returns true
        every { mockNetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } returns false
        
        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) } returns measurements
        coEvery { anyConstructed<OfflineMeasurementStorage>().getAllPendingMeasurements() } returns emptyList()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status": "success"}"""))

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        // Should behave as online
        assertEquals(1, mockWebServer.requestCount)
        verify(exactly = 1) { CoverageMeasurements.getCoverageMeasurements(mockContext, apiKey, true) }
    }
}