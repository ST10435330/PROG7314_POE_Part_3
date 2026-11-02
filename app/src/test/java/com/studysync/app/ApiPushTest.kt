package com.studysync.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.Proxy
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ApiPushTest {
    private val server = MockWebServer()
    private val token = "test-device-token-1234567890"
    private lateinit var api: ApiStudyRepository

    @Before
    fun setup() {
        server.start()

        val service = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .client(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StudyApi::class.java)

        api = ApiStudyRepository(
            "user-a",
            service,
            currentUserId = { "user-a" },
            tokenProvider = { force -> if (force) "fresh" else "cached" }
        )
    }

    @After
    fun close() {
        server.shutdown()
    }

    private fun request() =
        checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    @Test
    fun notificationRoutes() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"registered\":true}"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(
            MockResponse().setResponseCode(202).setBody("{\"accepted\":true}")
        )

        api.registerDevice(token)
        api.unregisterDevice(token)
        api.testNotification(token)

        for (path in listOf("register", "unregister", "test")) {
            val sent = request()

            assertEquals("POST", sent.method)
            assertEquals("/api/notifications/$path", sent.path)
            assertEquals("Bearer cached", sent.getHeader("Authorization"))

            val body = JSONObject(sent.body.readUtf8())
            assertEquals(1, body.length())
            assertEquals(token, body.getString("token"))
        }
    }

    @Test
    fun refreshPreservesToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("{\"registered\":true}"))

        api.registerDevice(token)

        val first = request()
        val second = request()

        assertEquals(first.body.readUtf8(), second.body.readUtf8())
        assertEquals("Bearer fresh", second.getHeader("Authorization"))
    }

    @Test
    fun expiredDevice() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(410).setBody("{}"))

        try {
            api.testNotification(token)
            fail("Expected expired-device response")
        } catch (error: ApiException) {
            assertEquals(410, error.statusCode)
            assertFalse(error.retryable)
            assertTrue(error.message!!.contains("registration expired"))
        }
    }
}