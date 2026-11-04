package com.studysync.app

import java.io.File

data class TaskAttachment(
    val id: String,
    val taskId: String,
    val name: String,
    val contentType: String,
    val size: Long
)

data class PreparedAttachment(val attachment: TaskAttachment, val file: File)

enum class AttachmentAction { OPEN, SAVE_COPY }

data class ReadyAttachment(
    val attachment: TaskAttachment,
    val file: File,
    val action: AttachmentAction
)

class AttachmentFailure(val messageId: Int) : Exception()
