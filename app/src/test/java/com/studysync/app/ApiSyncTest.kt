package com.studysync.app

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import java.util.concurrent.atomic.AtomicReference

/** Exercises real Retrofit requests against a local test server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ApiSyncTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: ApiStudyRepository

    private val activeUser = AtomicReference<String?>("user-a")
    private val refreshes = mutableListOf<Boolean>()
    private val gson = Gson()

    private val subject = Subject(
        "subject-1",
        "Programming",
        ""
    )

    private val task = StudyTask(
        taskId = "task-1",
        subjectId = "subject-1",
        title = "Revise Kotlin",
        dueDate = "2026-11-30",
        completed = true
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StudyApi::class.java)

        // Inject test authentication while retaining the real HTTP client.
        repository = ApiStudyRepository(
            "user-a",
            api,
            currentUserId = { activeUser.get() },
            tokenProvider = { force ->
                refreshes.add(force)
                if (force) "fresh-token" else "cached-token"
            }
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun subjectChange() = PendingStudyChange(
        "operation-1",
        "subjects",
        subject.subjectId,
        "PUT",
        gson.toJson(subject)
    )

    private fun subjectReply(
        value: Subject = subject
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(gson.toJson(value))

    private fun request(): RecordedRequest =
        checkNotNull(
            server.takeRequest(2, TimeUnit.SECONDS)
        )

    private fun uploadError(
        change: PendingStudyChange = subjectChange()
    ): ApiException {
        try {
            runBlocking {
                repository.upload(change)
            }
            fail("Expected upload failure")
        } catch (exception: ApiException) {
            return exception
        }

        error("Expected an ApiException")
    }

    @Test
    fun subjectPutUsesStableId() {
        server.enqueue(subjectReply())

        runBlocking {
            repository.upload(subjectChange())
        }

        val sent = request()

        assertEquals("PUT", sent.method)
        assertEquals("/api/subjects/subject-1", sent.path)
        assertEquals(
            "operation-1",
            sent.getHeader("X-Operation-Id")
        )
        assertEquals(
            "Bearer cached-token",
            sent.getHeader("Authorization")
        )

        val json = JSONObject(sent.body.readUtf8())

        assertEquals("Programming", json.getString("name"))
        assertFalse(json.has("subjectId"))
    }

    @Test
    fun taskPutUsesRequestFields() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(gson.toJson(task))
        )

        runBlocking {
            repository.upload(
                PendingStudyChange(
                    "task-op",
                    "tasks",
                    task.taskId,
                    "PUT",
                    gson.toJson(task)
                )
            )
        }

        val sent = request()

        assertEquals("PUT", sent.method)
        assertEquals("/api/tasks/task-1", sent.path)
        assertEquals(
            "task-op",
            sent.getHeader("X-Operation-Id")
        )

        val json = JSONObject(sent.body.readUtf8())

        assertEquals("subject-1", json.getString("subjectId"))
        assertTrue(json.getBoolean("completed"))
        assertFalse(json.has("taskId"))
    }

    @Test
    fun deletesCarryOperationIds() {
        for (kind in listOf("subjects", "tasks")) {
            server.enqueue(
                MockResponse().setResponseCode(204)
            )

            runBlocking {
                repository.upload(
                    PendingStudyChange(
                        "delete-$kind",
                        kind,
                        "record-1",
                        "DELETE",
                        null
                    )
                )
            }

            val sent = request()

            assertEquals("DELETE", sent.method)
            assertEquals("/api/$kind/record-1", sent.path)
            assertEquals(
                "delete-$kind",
                sent.getHeader("X-Operation-Id")
            )
            assertEquals(0L, sent.bodySize)
        }
    }

    @Test
    fun tokenRetryKeepsOperation() {
        server.enqueue(
            MockResponse().setResponseCode(401)
        )
        server.enqueue(subjectReply())

        runBlocking {
            repository.upload(subjectChange())
        }

        val first = request()
        val second = request()

        assertEquals(listOf(false, true), refreshes)
        assertEquals(
            "Bearer fresh-token",
            second.getHeader("Authorization")
        )
        assertEquals(
            first.getHeader("X-Operation-Id"),
            second.getHeader("X-Operation-Id")
        )
        assertEquals(
            first.body.readUtf8(),
            second.body.readUtf8()
        )
    }

    @Test
    fun errorsRetainRetryPolicy() {
        for (code in listOf(400, 409, 429, 503)) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setBody(
                        """{"error":{"message":"Validation or conflict message"}}"""
                    )
            )

            val error = uploadError()

            assertEquals(code, error.statusCode)
            assertEquals(
                code in listOf(429, 503),
                error.retryable
            )

            if (code == 400 || code == 409) {
                assertEquals(
                    "Validation or conflict message",
                    error.message
                )
            }

            request()
        }
    }

    @Test
    fun lostReplyCanBeRetried() {
        server.enqueue(
            MockResponse().setSocketPolicy(
                SocketPolicy.DISCONNECT_AFTER_REQUEST
            )
        )

        assertTrue(uploadError().retryable)
        assertEquals(
            "operation-1",
            request().getHeader("X-Operation-Id")
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun changedAccountRejectsReply() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(
                request: RecordedRequest
            ): MockResponse {
                activeUser.set("another-account")
                return subjectReply()
            }
        }

        assertEquals(401, uploadError().statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun signedOutSendsNoRequest() {
        activeUser.set(null)
        server.enqueue(subjectReply())

        assertEquals(401, uploadError().statusCode)
        assertEquals(0, server.requestCount)
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun malformedReplyIsRetryable() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{broken")
        )

        assertTrue(uploadError().retryable)
    }

    @Test
    fun wrongRecordIsNotAccepted() {
        server.enqueue(
            subjectReply(
                subject.copy(subjectId = "different-id")
            )
        )

        assertTrue(uploadError().retryable)
    }
}