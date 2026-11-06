package com.studysync.app

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

// Short method names keep Robolectric SQLite paths within common Windows limits.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AttachmentRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uid = UUID.randomUUID().toString()
    private val task = StudyTask(taskId = "task-1", subjectId = "subject-1", title = "Study")
    private val bytes = "%PDF-1.7\nnotes".toByteArray()
    private lateinit var store: LocalStudyStore
    private lateinit var repository: AttachmentRepository
    private lateinit var cloud: AttachmentTestCloud
    private lateinit var remote: AttachmentTestTasks
    private var currentUser: String? = uid
    private lateinit var attachment: TaskAttachment
    private lateinit var source: File

    @Before
    fun setup() {
        store = LocalStudyStore.get(context, uid)
        store.saveTask(task)
        cloud = AttachmentTestCloud()
        remote = AttachmentTestTasks(listOf(task))
        repository = AttachmentRepository(context, uid, cloud, remote) { currentUser }
        attachment = TaskAttachment(AttachmentValidator.newId(), task.taskId, "Notes.pdf", "application/pdf", bytes.size.toLong())
        source = File.createTempFile("attachment", ".pdf", context.cacheDir).apply { writeBytes(bytes) }
    }

    @After
    fun cleanup() {
        source.delete()
        repository.files.clear()
        store.close()
    }

    @Test
    fun uploadNeedsSyncedTask() = runBlocking {
        remote.records = emptyList()
        try {
            repository.upload(PreparedAttachment(attachment, source)) { }
            fail("Expected sync requirement")
        } catch (error: AttachmentFailure) { assertEquals(R.string.attachment_sync_first, error.messageId) }
        assertEquals(0, cloud.uploadCalls)
        remote.records = listOf(task)
        cloud.fail = true
        try { repository.upload(PreparedAttachment(attachment, source)) { }; fail("Expected offline error") }
        catch (_: IOException) { assertTrue(source.exists()) }
        cloud.fail = false
        repeat(2) { repository.upload(PreparedAttachment(attachment, source)) { } }
        assertEquals(listOf(attachment), cloud.records.values.toList())
    }

    @Test
    fun listsUsePrivateCache() = runBlocking {
        cloud.records[attachment.id] = attachment
        assertEquals(listOf(attachment), repository.list(task.taskId))
        cloud.fail = true
        try { repository.list(task.taskId); fail("Expected offline error") } catch (_: IOException) { }
        assertEquals(listOf(attachment), repository.cached(task.taskId))
        val other = LocalStudyStore.get(context, UUID.randomUUID().toString())
        try { assertTrue(other.attachments(task.taskId).isEmpty()) } finally { other.close() }
    }

    @Test
    fun cachedFileOpensOffline() = runBlocking {
        cloud.content[attachment.id] = bytes
        val file = repository.download(attachment) { }
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(file.canonicalPath.startsWith(File(context.cacheDir, "attachments").canonicalPath))
        cloud.fail = true
        assertEquals(file, repository.download(attachment) { })
        assertEquals(1, cloud.downloadCalls)
        currentUser = "other-user"
        try { repository.download(attachment) { }; fail("Expected an account check") }
        catch (error: AttachmentFailure) { assertEquals(R.string.attachment_sign_in, error.messageId) }
    }

    @Test
    fun failedDownloadDeletesPartial() = runBlocking {
        cloud.fail = true
        try { repository.download(attachment) { }; fail("Expected failure") } catch (_: IOException) { }
        val target = repository.files.file(attachment)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun uploadChecksAccount() = runBlocking {
        cloud.afterUpload = { currentUser = "other-user" }
        try {
            repository.upload(PreparedAttachment(attachment, source)) { }
            fail("Expected account change rejection")
        } catch (error: AttachmentFailure) { assertEquals(R.string.attachment_sign_in, error.messageId) }
        assertTrue(store.attachments(task.taskId).isEmpty())
        assertEquals(1, cloud.records.size)
    }

    @Test
    fun deleteRemovesSelectedFile() = runBlocking {
        cloud.records[attachment.id] = attachment
        cloud.content[attachment.id] = bytes
        repository.list(task.taskId)
        val file = repository.download(attachment) { }
        repeat(2) { repository.delete(attachment) }
        assertFalse(file.exists())
        assertTrue(repository.cached(task.taskId).isEmpty())
    }

    @Test
    fun deletedTaskBlocksStorage() = runBlocking {
        store.deleteTask(task.taskId)
        try { repository.upload(PreparedAttachment(attachment, source)) { }; fail("Expected missing task") }
        catch (error: AttachmentFailure) { assertEquals(R.string.attachment_task_missing, error.messageId) }
        assertEquals(0, cloud.uploadCalls)
    }
}
