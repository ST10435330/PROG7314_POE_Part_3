package com.studysync.app

import java.io.File
import java.io.IOException

internal class AttachmentTestCloud : AttachmentCloud {
    val records = linkedMapOf<String, TaskAttachment>()
    val content = mutableMapOf<String, ByteArray>()
    var fail = false
    var afterUpload: () -> Unit = {}
    var afterList: () -> Unit = {}
    val uploadIds = mutableListOf<String>()
    var uploadCalls = 0
    var downloadCalls = 0
    var deleteCalls = 0

    override suspend fun list(taskId: String): List<TaskAttachment> {
        if (fail) throw IOException("Offline test")
        afterList()
        return records.values.filter { it.taskId == taskId }
    }
    override suspend fun upload(attachment: TaskAttachment, file: File, progress: (Int) -> Unit) {
        uploadCalls++
        uploadIds.add(attachment.id)
        if (fail) throw IOException("Offline test")
        records.putIfAbsent(attachment.id, attachment)
        content.putIfAbsent(attachment.id, file.readBytes())
        progress(100)
        afterUpload()
    }
    override suspend fun download(attachment: TaskAttachment, file: File, progress: (Int) -> Unit) {
        downloadCalls++
        file.writeBytes(byteArrayOf(1))
        if (fail) throw IOException("Offline test")
        file.writeBytes(content.getValue(attachment.id))
        progress(100)
    }
    override suspend fun delete(attachment: TaskAttachment) {
        if (fail) throw IOException("Offline test")
        deleteCalls++
        records.remove(attachment.id)
        content.remove(attachment.id)
    }
    override fun cancel() { }
    override fun close() { }
}

internal class AttachmentTestTasks(var records: List<StudyTask>) : StudyRepository {
    override suspend fun getTasks() = records
    override suspend fun getSubjects() = emptyList<Subject>()
    override suspend fun createSubject(subject: Subject): Subject = error("Not used")
    override suspend fun updateSubject(subject: Subject): Subject = error("Not used")
    override suspend fun deleteSubject(subjectId: String) { error("Not used") }
    override suspend fun createTask(task: StudyTask): StudyTask = error("Not used")
    override suspend fun updateTask(task: StudyTask): StudyTask = error("Not used")
    override suspend fun deleteTask(taskId: String) { error("Not used") }
}
