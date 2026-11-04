package com.studysync.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

class AttachmentFiles(private val context: Context, userId: String) {
    private val directory = File(context.cacheDir, "attachments/${accountFolder(userId)}")

    suspend fun prepare(taskId: String, uri: Uri): PreparedAttachment {
        var name = context.getString(R.string.attachment_default_name)
        var reportedSize: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) reportedSize = cursor.getLong(sizeColumn)
            }
        }
        if ((reportedSize ?: 0) > AttachmentValidator.MAX_SIZE) throw AttachmentFailure(R.string.attachment_too_large)
        val id = AttachmentValidator.newId()
        AttachmentValidator.checkIds(taskId, id)
        val staged = File(directory, "staging/$id").apply { parentFile?.mkdirs() }
        val coroutine = currentCoroutineContext()
        val typeAndSize = context.contentResolver.openInputStream(uri)?.use {
            AttachmentValidator.copy(it, staged, context.contentResolver.getType(uri)) { coroutine.ensureActive() }
        } ?: throw AttachmentFailure(R.string.attachment_read_error)
        return PreparedAttachment(
            TaskAttachment(id, taskId, AttachmentValidator.name(name).ifBlank {
                context.getString(R.string.attachment_default_name)
            }, typeAndSize.first, typeAndSize.second), staged
        )
    }

    fun file(attachment: TaskAttachment): File {
        AttachmentValidator.checkIds(attachment.taskId, attachment.id)
        return File(directory, "${attachment.taskId}/${attachment.id}.${AttachmentValidator.extension(attachment.contentType)}")
            .apply { parentFile?.mkdirs() }
    }

    fun open(file: File, type: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun export(file: File, uri: Uri, checkAccount: () -> Unit = {}) {
        val coroutine = currentCoroutineContext()
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutine.ensureActive()
                    checkAccount()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: throw AttachmentFailure(R.string.attachment_save_copy_error)
    }

    fun remove(attachment: TaskAttachment) {
        val target = file(attachment)
        revoke(context, target)
        target.delete()
    }

    fun clearTask(taskId: String) {
        AttachmentValidator.checkTaskId(taskId)
        clearDirectory(context, File(directory, taskId))
    }

    fun clear() = clearDirectory(context, directory)

    companion object {
        private fun accountFolder(uid: String): String = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }

        fun clearOtherAccounts(context: Context, uid: String?, canContinue: () -> Boolean) {
            val keep = uid?.let { accountFolder(it) }
            File(context.cacheDir, "attachments").listFiles()?.forEach {
                if (canContinue() && it.name != keep) clearDirectory(context, it)
            }
        }

        private fun revoke(context: Context, file: File) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        private fun clearDirectory(context: Context, directory: File) {
            directory.walkBottomUp().forEach { file ->
                if (file.isFile) revoke(context, file)
                file.delete()
            }
        }
    }
}
