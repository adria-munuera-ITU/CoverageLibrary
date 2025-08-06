package com.example.giga_coverage

import android.content.Context
import android.content.SharedPreferences
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

@RunWith(RobolectricTestRunner::class)
class GatherAndSendDataTest {

    private lateinit var mockContext: Context
    private lateinit var mockSharedPreferences: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var gatherAndSendData: GatherAndSendData
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockSharedPreferences = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        mockWebServer = MockWebServer()

        mockkObject(GigaCoverageConfig)
        mockkObject(CoverageMeasurements)

        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            "test-android-id"
        )

        every { mockContext.getSharedPreferences("api_prefs", Context.MODE_PRIVATE) } returns mockSharedPreferences
        every { mockContext.contentResolver } returns RuntimeEnvironment.getApplication().contentResolver
        every { mockSharedPreferences.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just runs
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
        every { CoverageMeasurements.getCoverageMeasurements(mockContext) } returns testMeasurements

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
        every { CoverageMeasurements.getCoverageMeasurements(mockContext) } returns testMeasurements

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

        every { mockSharedPreferences.getString("api_key", null) } returns null
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            androidId
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error": "Bad request"}""")
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        val getKeyRequest = mockWebServer.takeRequest()
        assertEquals("/api/get-key", getKeyRequest.path)

        verify(exactly = 0) { CoverageMeasurements.getCoverageMeasurements(mockContext) }
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun testProcessAndSendData_SendDataFails() = runTest {
        val apiKey = "cached-api-key"
        val testMeasurements = mapOf("test_key" to "test_value")

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext) } returns testMeasurements

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
        every { CoverageMeasurements.getCoverageMeasurements(mockContext) } returns emptyMeasurements

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun testProcessAndSendData_AndroidIdNull() = runTest {
        every { mockSharedPreferences.getString("api_key", null) } returns null
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            null
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        assertEquals(0, mockWebServer.requestCount)
        verify(exactly = 0) { CoverageMeasurements.getCoverageMeasurements(mockContext) }
    }

    @Test
    fun testProcessAndSendData_NetworkException() = runTest {
        val apiKey = "cached-api-key"
        val testMeasurements = mapOf("test_key" to "test_value")

        every { mockSharedPreferences.getString("api_key", null) } returns apiKey
        every { CoverageMeasurements.getCoverageMeasurements(mockContext) } returns testMeasurements

        mockWebServer.shutdown()

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()
    }

    @Test
    fun testProcessAndSendData_InvalidApiKeyResponse() = runTest {
        val androidId = "test-android-id"

        every { mockSharedPreferences.getString("api_key", null) } returns null
        Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Secure.ANDROID_ID, 
            androidId
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status": "success"}""")
        )

        gatherAndSendData = GatherAndSendData(mockContext)
        gatherAndSendData.processAndSendData()

        verify(exactly = 0) { mockEditor.putString(any(), any()) }
        verify(exactly = 0) { CoverageMeasurements.getCoverageMeasurements(mockContext) }
    }
}