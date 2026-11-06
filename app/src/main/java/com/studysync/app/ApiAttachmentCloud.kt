package com.studysync.app

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

private object AttachmentHttp {
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
}

private data class AttachmentRecord(
    val id: String?, val taskId: String?, val name: String?,
    val contentType: String?, val size: Long?
)

// Only the existing Firebase ID token goes to our API. Supabase secrets stay on Render.
// References: https://square.github.io/okhttp/3.x/okhttp/okhttp3/Call.html
// https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html
class ApiAttachmentCloud(
    private val userId: String,
    private val baseUrl: HttpUrl = StudyApiClient.BASE_URL.toHttpUrl(),
    private val client: OkHttpClient = AttachmentHttp.client,
    private val currentUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid },
    private val tokenProvider: suspend (Boolean) -> String? = { force ->
        FirebaseAuth.getInstance().currentUser?.takeIf { it.uid == userId }
            ?.getIdToken(force)?.await()?.token
    },
    watchAccount: (() -> Unit) -> (() -> Unit) = { changed ->
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { changed() }
        auth.addAuthStateListener(listener)
        val remove: () -> Unit = { auth.removeAuthStateListener(listener) }
        remove
    }
) : AttachmentCloud {
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val generation = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val gson = Gson()
    private val removeWatcher = watchAccount { if (currentUserId() != userId) cancel() }

    private fun check(version: Int) {
        if (closed.get() || generation.get() != version) throw CancellationException("Attachment operation stopped")
        if (currentUserId() != userId) throw AttachmentFailure(R.string.attachment_sign_in)
    }

    private fun url(taskId: String, id: String? = null): HttpUrl {
        AttachmentValidator.checkTaskId(taskId)
        if (id != null) AttachmentValidator.checkIds(taskId, id)
        return baseUrl.newBuilder().addPathSegment("tasks").addPathSegment(taskId)
            .addPathSegment("attachments").apply { if (id != null) addPathSegment(id) }.build()
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
            }
        })
    }

    private suspend fun <T> request(
        fallback: Int,
        build: (String, () -> Unit) -> Request,
        read: (Response, () -> Unit) -> T
    ): T = withContext(Dispatchers.IO) {
        val version = generation.get()
        val operationContext = currentCoroutineContext()
        val verify = { operationContext.ensureActive(); check(version) }
        try {
            for (attempt in 0..1) {
                verify()
                val token = try { tokenProvider(attempt == 1) }
                catch (error: CancellationException) { throw error }
                catch (_: FirebaseNetworkException) { throw AttachmentFailure(R.string.attachment_network_error) }
                catch (_: IOException) { throw AttachmentFailure(R.string.attachment_network_error) }
                catch (_: Exception) { throw AttachmentFailure(R.string.attachment_sign_in) }
                verify()
                if (token.isNullOrBlank()) throw AttachmentFailure(R.string.attachment_sign_in)
                val call = client.newCall(build("Bearer $token", verify))
                calls.add(call)
                try {
                    verify()
                    val response = call.awaitResponse()
                    if (response.code == 401 && attempt == 0) {
                        response.close()
                        continue
                    }
                    response.use {
                        verify()
                        Log.d("StudySync", "Attachment API response: ${it.code}")
                        if (!it.isSuccessful) throw responseFailure(it, fallback)
                        val result = read(it, verify)
                        verify()
                        return@withContext result
                    }
                } finally { calls.remove(call) }
            }
            throw AttachmentFailure(R.string.attachment_sign_in)
        } catch (error: IOException) {
            verify()
            throw (error.cause as? AttachmentFailure ?: AttachmentFailure(R.string.attachment_network_error))
        }
    }

    private fun responseFailure(response: Response, fallback: Int): AttachmentFailure {
        val code = runCatching {
            val source = response.body?.source() ?: return@runCatching ""
            source.request(4096)
            JSONObject(source.buffer.clone().readUtf8(minOf(source.buffer.size, 4096L)))
                .optJSONObject("error")?.optString("code")
        }.getOrNull()
        val message = when {
            response.code == 401 -> R.string.attachment_sign_in
            response.code == 403 -> R.string.attachment_denied
            code == "ATTACHMENT_TASK_MISSING" -> R.string.attachment_sync_first
            code == "ATTACHMENT_MISSING" -> R.string.attachment_missing
            code == "ATTACHMENT_EMPTY" -> R.string.attachment_empty
            code == "ATTACHMENT_BAD_TYPE" || response.code == 415 -> R.string.attachment_bad_type
            response.code == 413 -> R.string.attachment_too_large
            response.code in listOf(402, 429) -> R.string.attachment_quota_error
            response.code == 409 -> R.string.attachment_api_conflict
            response.code == 503 -> R.string.attachment_api_unavailable
            response.code == 404 -> R.string.attachment_setup_error
            else -> fallback
        }
        return AttachmentFailure(message)
    }

    private fun record(value: AttachmentRecord?, taskId: String, fallback: Int): TaskAttachment {
        val id = value?.id ?: throw AttachmentFailure(fallback)
        val name = value.name ?: throw AttachmentFailure(fallback)
        val type = value.contentType ?: throw AttachmentFailure(fallback)
        val size = value.size ?: throw AttachmentFailure(fallback)
        try { AttachmentValidator.checkIds(taskId, id) }
        catch (_: IllegalArgumentException) { throw AttachmentFailure(fallback) }
        if (value.taskId != taskId || name.isBlank() || AttachmentValidator.name(name) != name ||
            type !in AttachmentValidator.types || size !in 1..AttachmentValidator.MAX_SIZE) {
            throw AttachmentFailure(fallback)
        }
        return TaskAttachment(id, taskId, name, type, size)
    }

    override suspend fun list(taskId: String): List<TaskAttachment> = request(R.string.attachment_list_error,
        { token, _ -> Request.Builder().url(url(taskId)).header("Authorization", token).build() }
    ) { response, _ ->
        try {
            val entries = gson.fromJson(response.body?.charStream(), Array<AttachmentRecord>::class.java)
                ?: throw AttachmentFailure(R.string.attachment_list_error)
            val result = entries.map { record(it, taskId, R.string.attachment_list_error) }
            if (result.map { it.id }.distinct().size != result.size) throw AttachmentFailure(R.string.attachment_list_error)
            result.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
        } catch (_: com.google.gson.JsonParseException) { throw AttachmentFailure(R.string.attachment_list_error) }
    }

    override suspend fun upload(attachment: TaskAttachment, file: File, progress: (Int) -> Unit) {
        record(AttachmentRecord(attachment.id, attachment.taskId, attachment.name, attachment.contentType, attachment.size),
            attachment.taskId, R.string.attachment_upload_error)
        if (!file.isFile || file.length() != attachment.size) throw AttachmentFailure(R.string.attachment_read_error)
        request(R.string.attachment_upload_error, { token, verify ->
            val body = object : RequestBody() {
                override fun contentType() = attachment.contentType.toMediaType()
                override fun contentLength() = attachment.size
                override fun writeTo(sink: BufferedSink) {
                    try {
                        progress(0)
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            var sent = 0L
                            while (true) {
                                verify()
                                val count = input.read(buffer)
                                if (count < 0) break
                                sent += count
                                if (sent > attachment.size) throw AttachmentFailure(R.string.attachment_upload_error)
                                sink.write(buffer, 0, count)
                                progress(((sent * 100) / attachment.size).coerceIn(0, 99).toInt())
                            }
                            if (sent != attachment.size) throw AttachmentFailure(R.string.attachment_upload_error)
                        }
                    } catch (error: Exception) {
                        // OkHttp request bodies must report failures as IOExceptions.
                        throw IOException("Attachment upload interrupted", error)
                    }
                }
            }
            Request.Builder().url(url(attachment.taskId, attachment.id)).header("Authorization", token)
                .header("X-Attachment-Name", URLEncoder.encode(attachment.name, "UTF-8").replace("+", "%20"))
                .put(body).build()
        }) { response, _ ->
            val saved = try { gson.fromJson(response.body?.charStream(), AttachmentRecord::class.java) }
            catch (_: com.google.gson.JsonParseException) { throw AttachmentFailure(R.string.attachment_upload_error) }
            if (record(saved, attachment.taskId, R.string.attachment_upload_error) != attachment) {
                throw AttachmentFailure(R.string.attachment_upload_error)
            }
        }
        progress(100)
    }

    override suspend fun download(attachment: TaskAttachment, file: File, progress: (Int) -> Unit) {
        try {
            record(AttachmentRecord(attachment.id, attachment.taskId, attachment.name, attachment.contentType, attachment.size),
                attachment.taskId, R.string.attachment_download_error)
            request(R.string.attachment_download_error,
                { token, _ -> Request.Builder().url(url(attachment.taskId, attachment.id)).header("Authorization", token).build() }
            ) { response, verify ->
                val body = response.body ?: throw AttachmentFailure(R.string.attachment_download_error)
                if (body.contentLength() >= 0 && body.contentLength() != attachment.size ||
                    body.contentType()?.let { "${it.type}/${it.subtype}" } != attachment.contentType) {
                    throw AttachmentFailure(R.string.attachment_download_error)
                }
                progress(0)
                var copied = 0L
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        verify()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > attachment.size || copied > AttachmentValidator.MAX_SIZE) {
                            throw AttachmentFailure(R.string.attachment_download_error)
                        }
                        output.write(buffer, 0, count)
                        progress(((copied * 100) / attachment.size.coerceAtLeast(1)).coerceIn(0, 99).toInt())
                    }
                } }
                if (copied != attachment.size) throw AttachmentFailure(R.string.attachment_download_error)
            }
            progress(100)
        } catch (error: Exception) { file.delete(); throw error }
    }

    override suspend fun delete(attachment: TaskAttachment) {
        request(R.string.attachment_delete_error,
            { token, _ -> Request.Builder().url(url(attachment.taskId, attachment.id)).header("Authorization", token).delete().build() }
        ) { _, _ -> Unit }
    }

    override fun cancel() {
        generation.incrementAndGet()
        synchronized(calls) { calls.forEach { it.cancel() } }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) { cancel(); removeWatcher() }
    }
}
