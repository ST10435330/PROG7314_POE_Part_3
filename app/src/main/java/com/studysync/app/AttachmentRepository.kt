package com.studysync.app

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

class AttachmentRepository(
    context: Context,
    private val userId: String,
    private val cloud: AttachmentCloud = ApiAttachmentCloud(userId),
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
    is IOException -> R.string.attachment_network_error
    is android.content.ActivityNotFoundException -> R.string.attachment_no_viewer
    else -> fallback
}
