package com.studysync.app

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

// Loopback HTTP with synthetic tokens; no live Firebase/Supabase access.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ApiAttachmentCloudTest {
    private lateinit var server: MockWebServer
    private lateinit var cloud: ApiAttachmentCloud
    private lateinit var file: File
    private var user: String? = "alice"
    private var changed: (() -> Unit)? = null
    private var removed = false
    private val refreshed = mutableListOf<Boolean>()
    private val bytes = "%PDF-1.7\nStudy notes".toByteArray()
    private val entry = TaskAttachment("11111111-1111-4111-8111-111111111111", "task-1",
        "Notes + \u03bc.pdf", "application/pdf", bytes.size.toLong())
    private val path = "/api/tasks/task-1/attachments/${entry.id}"

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        file = File.createTempFile("attachment-http", ".pdf").apply { writeBytes(bytes) }
        cloud = client()
    }

    private fun client(tokens: suspend (Boolean) -> String? = {
        refreshed.add(it); if (it) "refreshed-token" else "initial-token"
    }) = ApiAttachmentCloud("alice", baseUrl = server.url("/api/"), currentUserId = { user },
        tokenProvider = tokens, watchAccount = { callback -> changed = callback; { removed = true } })

    @After fun cleanup() { cloud.close(); file.delete(); server.shutdown() }
    private fun json(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(Gson().toJson(value))
    private fun error(status: Int, code: String) = MockResponse().setResponseCode(status)
        .setBody("{\"error\":{\"code\":\"$code\",\"message\":\"Do not display this provider text\"}}")
    private suspend fun failure(expected: Int, operation: suspend () -> Unit) {
        try { operation(); fail("Expected an attachment failure") }
        catch (error: AttachmentFailure) { assertEquals(expected, error.messageId) }
    }

    @Test fun uploadRefreshesTokenOnce() = runBlocking {
        server.enqueue(error(401, "UNAUTHENTICATED")); server.enqueue(json(entry))
        val progress = mutableListOf<Int>()
        cloud.upload(entry, file, progress::add)
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", first.method); assertEquals(path, first.path)
        assertEquals("Bearer initial-token", first.getHeader("Authorization"))
        assertEquals("Bearer refreshed-token", second.getHeader("Authorization"))
        assertEquals("Notes%20%2B%20%CE%BC.pdf", second.getHeader("X-Attachment-Name"))
        assertArrayEquals(bytes, first.body.readByteArray()); assertArrayEquals(bytes, second.body.readByteArray())
        assertEquals(listOf(false, true), refreshed)
        assertEquals(100, progress.last()); assertTrue(file.exists())
        assertNull(first.getHeader("apikey"))
    }

    @Test fun listRejectsInvalidMetadata() = runBlocking {
        server.enqueue(json(listOf(entry)))
        assertEquals(listOf(entry), cloud.list("task-1"))
        for (value in listOf("null", "{}", "[{\"id\":null}]", Gson().toJson(listOf(entry.copy(taskId = "other"))),
            Gson().toJson(listOf(entry, entry)))) {
            server.enqueue(MockResponse().setBody(value))
            failure(R.string.attachment_list_error) { cloud.list("task-1") }
        }
        server.enqueue(json(emptyList<TaskAttachment>()))
        assertTrue(cloud.list("task-1").isEmpty())
    }

    @Test fun downloadChecksTypeAndLength() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody(okio.Buffer().write(bytes)))
        val progress = mutableListOf<Int>()
        cloud.download(entry, file, progress::add)
        assertArrayEquals(bytes, file.readBytes()); assertEquals(100, progress.last())
        assertEquals(path, server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        for (response in listOf(
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html>error</html>"),
            MockResponse().setHeader("Content-Type", "application/pdf").setChunkedBody("%PDF-short", 3),
            MockResponse().setHeader("Content-Type", "application/pdf").setChunkedBody("x".repeat(100), 3)
        )) {
            server.enqueue(response)
            failure(R.string.attachment_download_error) { cloud.download(entry, file) {} }
            assertFalse(file.exists())
        }
    }

    @Test fun errorsStayDistinct() = runBlocking {
        for ((status, code, message) in listOf(
            Triple(404, "ATTACHMENT_TASK_MISSING", R.string.attachment_sync_first),
            Triple(404, "ATTACHMENT_MISSING", R.string.attachment_missing),
            Triple(404, "NOT_FOUND", R.string.attachment_setup_error),
            Triple(403, "DENIED", R.string.attachment_denied),
            Triple(413, "REQUEST_TOO_LARGE", R.string.attachment_too_large),
            Triple(400, "ATTACHMENT_BAD_TYPE", R.string.attachment_bad_type),
            Triple(409, "ATTACHMENT_CONFLICT", R.string.attachment_api_conflict),
            Triple(503, "ATTACHMENT_STORAGE_UNAVAILABLE", R.string.attachment_api_unavailable)
        )) {
            server.enqueue(error(status, code))
            failure(message) { cloud.list("task-1") }
        }
    }

    @Test fun repeatedUnauthorisedStops() = runBlocking {
        repeat(2) { server.enqueue(error(401, "UNAUTHENTICATED")) }
        failure(R.string.attachment_sign_in) { cloud.list("task-1") }
        assertEquals(2, server.requestCount); assertEquals(listOf(false, true), refreshed)
    }

    @Test fun changedAccountBeforeSend() = runBlocking {
        cloud.close()
        cloud = client { user = "bob"; "old-account-token" }
        failure(R.string.attachment_sign_in) { cloud.list("task-1") }
        assertEquals(0, server.requestCount)
    }

    @Test fun accountSwitchStopsDownload() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf")
            .setBody(okio.Buffer().write(bytes)).throttleBody(1, 1, TimeUnit.SECONDS))
        val job = async(Dispatchers.Default) { cloud.download(entry, file) {} }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        user = "bob"; changed!!.invoke()
        try { withTimeout(3000) { job.await() }; fail("Expected cancellation") }
        catch (error: CancellationException) { if (error is TimeoutCancellationException) throw error }
        assertTrue(job.isCancelled)
        assertFalse(file.exists())
    }

    @Test fun uploadNeedsAcknowledgement() = runBlocking {
        server.enqueue(json(entry.copy(size = 999)))
        val progress = mutableListOf<Int>()
        failure(R.string.attachment_upload_error) { cloud.upload(entry, file, progress::add) }
        assertFalse(progress.contains(100)); assertTrue(file.exists())
    }

    @Test fun deleteIsRepeatable() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)); cloud.delete(entry) }
        repeat(2) { assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS)!!.method) }
    }

    @Test fun redirectsNeverForwardTokens() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid/secret"))
        failure(R.string.attachment_list_error) { cloud.list("task-1") }
        assertEquals(1, server.requestCount)
        cloud.close(); assertTrue(removed)
        try { cloud.list("task-1"); fail("Expected a closed provider") }
        catch (_: CancellationException) { }
        assertEquals(1, server.requestCount)
    }
}
