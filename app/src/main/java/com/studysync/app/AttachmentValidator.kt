package com.studysync.app

import java.io.File
import java.io.InputStream
import java.util.UUID

object AttachmentValidator {
    const val MAX_SIZE = 5 * 1024 * 1024L
    val types = arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp")

    fun checkTaskId(taskId: String) {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
    }

    fun checkIds(taskId: String, attachmentId: String) {
        checkTaskId(taskId)
        require(attachmentId.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
    }

    fun path(userId: String, taskId: String, attachmentId: String): String {
        require(userId.isNotBlank() && '/' !in userId && userId != "." && userId != "..")
        checkIds(taskId, attachmentId)
        return "users/$userId/tasks/$taskId/attachments/$attachmentId"
    }

    fun name(value: String): String = value
        .filterNot { it.isISOControl() || it == '/' || it == '\\' }
        .trim().take(150)

    fun type(header: ByteArray): String? {
        val bytes = header.map { it.toInt() and 255 }
        return when {
            header.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> "application/pdf"
            bytes.take(3) == listOf(255, 216, 255) -> "image/jpeg"
            bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
            header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }

    fun copy(
        input: InputStream,
        target: File,
        reportedType: String?,
        checkCancelled: () -> Unit = {}
    ): Pair<String, Long> {
        try {
            var size = 0L
            val header = java.io.ByteArrayOutputStream()
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_SIZE) throw AttachmentFailure(R.string.attachment_too_large)
                    if (header.size() < 12) header.write(buffer, 0, minOf(count, 12 - header.size()))
                    output.write(buffer, 0, count)
                }
            }
            if (size == 0L) throw AttachmentFailure(R.string.attachment_empty)
            val detected = type(header.toByteArray()) ?: throw AttachmentFailure(R.string.attachment_bad_type)
            if (reportedType != null && reportedType != "application/octet-stream" && reportedType != detected) {
                throw AttachmentFailure(R.string.attachment_bad_type)
            }
            return detected to size
        } catch (exception: Exception) {
            target.delete()
            throw exception
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun extension(contentType: String): String = when (contentType) {
        "application/pdf" -> "pdf"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> throw AttachmentFailure(R.string.attachment_bad_type)
    }
}
