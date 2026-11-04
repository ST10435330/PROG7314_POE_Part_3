package com.studysync.app

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

interface AttachmentCloud {
    suspend fun list(taskId: String): List<TaskAttachment>
    suspend fun upload(attachment: TaskAttachment, file: File, progress: (Int) -> Unit)
    suspend fun download(attachment: TaskAttachment, file: File, progress: (Int) -> Unit)
    suspend fun delete(attachment: TaskAttachment)
    fun cancel()
    fun close()
}

class FirebaseAttachmentCloud(private val userId: String) : AttachmentCloud {
    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance().apply {
        maxUploadRetryTimeMillis = 30_000
        maxDownloadRetryTimeMillis = 30_000
        maxOperationRetryTimeMillis = 15_000
    }
    private val transfers = java.util.Collections.synchronizedSet(mutableSetOf<StorageTask<*>>())
    private val listener = FirebaseAuth.AuthStateListener {
        if (it.currentUser?.uid != userId) cancel()
    }

    init { auth.addAuthStateListener(listener) }

    private fun checkAccount() {
        if (auth.currentUser?.uid != userId) throw AttachmentFailure(R.string.attachment_sign_in)
        val manager = auth.app.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        val network = manager.getNetworkCapabilities(manager.activeNetwork)
        if (network?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            throw AttachmentFailure(R.string.attachment_network_error)
        }
    }

    private fun reference(attachment: TaskAttachment) = storage.reference.child(
        AttachmentValidator.path(userId, attachment.taskId, attachment.id)
    )

    override suspend fun list(taskId: String): List<TaskAttachment> {
        checkAccount()
        AttachmentValidator.checkTaskId(taskId)
        val folder = storage.reference.child("users/$userId/tasks/$taskId/attachments")
        val result = mutableListOf<TaskAttachment>()
        var token: String? = null
        do {
            checkAccount()
            val page = if (token == null) folder.list(100).await() else folder.list(100, token).await()
            for (item in page.items) {
                checkAccount()
                try {
                    val metadata = item.metadata.await()
                    AttachmentValidator.checkIds(taskId, item.name)
                    val type = metadata.contentType ?: throw AttachmentFailure(R.string.attachment_bad_type)
                    if (type !in AttachmentValidator.types || metadata.sizeBytes !in 1..AttachmentValidator.MAX_SIZE) {
                        throw AttachmentFailure(R.string.attachment_bad_type)
                    }
                    result.add(TaskAttachment(item.name, taskId,
                        AttachmentValidator.name(metadata.getCustomMetadata("displayName") ?: item.name),
                        type, metadata.sizeBytes))
                } catch (exception: StorageException) {
                    if (exception.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw exception
                }
            }
            token = page.pageToken
        } while (token != null)
        checkAccount()
        return result.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }

    override suspend fun upload(attachment: TaskAttachment, file: File, progress: (Int) -> Unit) {
        checkAccount()
        val ref = reference(attachment)
        // A lost response must not create a second cloud object on retry.
        val existing = try { ref.metadata.await() } catch (exception: StorageException) {
            if (exception.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw exception
            null
        }
        checkAccount()
        if (existing != null) {
            if (existing.sizeBytes == attachment.size && existing.contentType == attachment.contentType &&
                existing.getCustomMetadata("displayName") == attachment.name
            ) return
            throw AttachmentFailure(R.string.attachment_upload_error)
        }
        val metadata = StorageMetadata.Builder().setContentType(attachment.contentType)
            .setCustomMetadata("displayName", attachment.name).build()
        val task = ref.putFile(Uri.fromFile(file), metadata)
        transfers.add(task)
        task.addOnProgressListener {
            progress(((it.bytesTransferred * 100) / attachment.size).coerceIn(0, 100).toInt())
        }
        try {
            task.await()
            checkAccount()
        } catch (exception: CancellationException) {
            task.cancel()
            throw exception
        } finally { transfers.remove(task) }
    }

    override suspend fun download(attachment: TaskAttachment, file: File, progress: (Int) -> Unit) {
        checkAccount()
        val task = reference(attachment).getFile(file)
        transfers.add(task)
        task.addOnProgressListener {
            progress(((it.bytesTransferred * 100) / attachment.size.coerceAtLeast(1)).coerceIn(0, 100).toInt())
        }
        try {
            task.await()
            checkAccount()
        } catch (exception: CancellationException) {
            task.cancel()
            throw exception
        } finally { transfers.remove(task) }
    }

    override suspend fun delete(attachment: TaskAttachment) {
        checkAccount()
        try { reference(attachment).delete().await() } catch (exception: StorageException) {
            if (exception.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw exception
        }
        checkAccount()
    }

    override fun cancel() {
        synchronized(transfers) { transfers.toList() }.forEach { it.cancel() }
    }

    override fun close() {
        cancel()
        auth.removeAuthStateListener(listener)
    }
}

class AttachmentRepository(
    context: Context,
    private val userId: String,
    private val cloud: AttachmentCloud = FirebaseAttachmentCloud(userId),
    private val tasks: StudyRepository = ApiStudyRepository(userId),
    private val currentUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid }
) {
    private val store = LocalStudyStore.get(context, userId)
    val files = AttachmentFiles(context.applicationContext, userId)

    fun requireAccount() {
        if (currentUserId() != userId) throw AttachmentFailure(R.string.attachment_sign_in)
    }

    private fun checkTask(taskId: String) {
        requireAccount()
        if (store.getTasks().none { it.taskId == taskId }) throw AttachmentFailure(R.string.attachment_task_missing)
    }

    suspend fun cached(taskId: String): List<TaskAttachment> = withContext(Dispatchers.IO) {
        checkTask(taskId)
        store.attachments(taskId)
    }

    suspend fun list(taskId: String): List<TaskAttachment> = withContext(Dispatchers.IO) {
        checkTask(taskId)
        val records = cloud.list(taskId)
        records.forEach {
            AttachmentValidator.checkIds(it.taskId, it.id)
            require(it.taskId == taskId)
        }
        checkTask(taskId)
        store.cacheAttachments(taskId, records)
        records
    }

    suspend fun prepare(taskId: String, uri: Uri): PreparedAttachment {
        var prepared: PreparedAttachment? = null
        try {
            return withContext(Dispatchers.IO) {
                checkTask(taskId)
                prepared = files.prepare(taskId, uri)
                checkTask(taskId)
                checkNotNull(prepared)
            }
        } catch (exception: Exception) {
            prepared?.file?.delete()
            when (exception) {
                is CancellationException, is AttachmentFailure -> throw exception
                else -> throw AttachmentFailure(R.string.attachment_read_error)
            }
        }
    }

    suspend fun upload(prepared: PreparedAttachment, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val attachment = prepared.attachment
        checkTask(attachment.taskId)
        if (tasks.getTasks().none { it.taskId == attachment.taskId }) {
            throw AttachmentFailure(R.string.attachment_sync_first)
        }
        checkTask(attachment.taskId)
        cloud.upload(attachment, prepared.file, progress)
        checkTask(attachment.taskId)
    }

    suspend fun download(attachment: TaskAttachment, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        checkTask(attachment.taskId)
        val target = files.file(attachment)
        if (target.exists() && target.length() == attachment.size) return@withContext target
        val partial = File(target.path + ".part")
        try {
            cloud.download(attachment, partial, progress)
            checkTask(attachment.taskId)
            if (partial.length() != attachment.size || partial.length() > AttachmentValidator.MAX_SIZE) {
                throw AttachmentFailure(R.string.attachment_download_error)
            }
            partial.inputStream().use {
                val header = ByteArray(12)
                val count = it.read(header)
                if (count <= 0 || AttachmentValidator.type(header.copyOf(count)) != attachment.contentType) {
                    throw AttachmentFailure(R.string.attachment_bad_type)
                }
            }
            check(partial.renameTo(target))
            target
        } finally { partial.delete() }
    }

    suspend fun delete(attachment: TaskAttachment) = withContext(Dispatchers.IO) {
        checkTask(attachment.taskId)
        cloud.delete(attachment)
        requireAccount()
        files.remove(attachment)
        store.cacheAttachments(attachment.taskId, store.attachments(attachment.taskId).filterNot { it.id == attachment.id })
    }

    fun cancel() = cloud.cancel()
    fun close() = cloud.close()
}

fun attachmentError(exception: Exception, fallback: Int): Int = when (exception) {
    is AttachmentFailure -> exception.messageId
    is StorageException -> when (exception.errorCode) {
        StorageException.ERROR_NOT_AUTHENTICATED -> R.string.attachment_sign_in
        StorageException.ERROR_NOT_AUTHORIZED -> R.string.attachment_denied
        StorageException.ERROR_OBJECT_NOT_FOUND -> R.string.attachment_missing
        StorageException.ERROR_BUCKET_NOT_FOUND, StorageException.ERROR_PROJECT_NOT_FOUND -> R.string.attachment_setup_error
        StorageException.ERROR_QUOTA_EXCEEDED -> R.string.attachment_quota_error
        StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> R.string.attachment_network_error
        StorageException.ERROR_CANCELED -> R.string.attachment_cancelled
        else -> fallback
    }
    is IOException -> R.string.attachment_network_error
    is android.content.ActivityNotFoundException -> R.string.attachment_no_viewer
    else -> fallback
}
