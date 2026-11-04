package com.studysync.app

import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AttachmentCleanupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uid = UUID.randomUUID().toString()
    private lateinit var store: LocalStudyStore
    private val task = StudyTask(taskId = "task-1", subjectId = "subject-1", title = "Study")

    @Before
    fun setup() { store = LocalStudyStore.get(context, uid) }
    @After
    fun close() { store.close() }

    private fun queueDeletion() {
        store.saveTask(task)
        store.acknowledge(store.pendingChanges().last().operationId)
        store.deleteTask(task.taskId)
    }

    @Test
    fun cleanupWaitsForCloudAcknowledgementAndSurvivesDatabaseReopening() {
        queueDeletion()
        assertEquals(1, store.attachmentCleanupCount())
        assertTrue(store.attachmentCleanupTasks().isEmpty())
        val operation = store.pendingChanges().single()
        store.close()
        assertEquals(operation, store.pendingChanges().single())
        store.acknowledge(operation.operationId)
        store.close()
        assertEquals(listOf(task.taskId), store.attachmentCleanupTasks())
    }

    @Test
    fun failedTaskWritesRollbackTheCleanupIntentAndCachedAttachmentDeletion() {
        store.saveTask(task)
        val attachment = TaskAttachment(AttachmentValidator.newId(), task.taskId, "Notes.pdf", "application/pdf", 10)
        store.cacheAttachments(task.taskId, listOf(attachment))
        store.writableDatabase.execSQL(
            "CREATE TRIGGER reject_write BEFORE INSERT ON outbox BEGIN SELECT RAISE(ABORT,'Test failure'); END"
        )
        try { store.deleteTask(task.taskId); fail("Expected rollback") } catch (_: android.database.SQLException) { }
        assertEquals(listOf(task), store.getTasks())
        assertEquals(listOf(attachment), store.attachments(task.taskId))
        assertEquals(0, store.attachmentCleanupCount())
    }

    @Test
    fun rejectedTaskDeletionDoesNotBecomeEligibleForCloudCleanup() {
        queueDeletion()
        store.reject(store.pendingChanges().single().operationId, "Rejected")
        assertTrue(store.attachmentCleanupTasks().isEmpty())
        store.discardRejected()
        assertEquals(0, store.attachmentCleanupCount())
    }

    @Test
    fun storageFailureKeepsItsQueueWithoutChangingTheTaskOutbox() = runBlocking {
        queueDeletion()
        store.acknowledge(store.pendingChanges().single().operationId)
        store.saveTask(task.copy(taskId = "other-task"))
        val before = store.pendingChanges()
        val cloud = AttachmentTestCloud().apply { fail = true }
        assertFalse(AttachmentCleanup(store, cloud, { emptyList() }, { true }).run())
        assertEquals(before, store.pendingChanges())
        store.close()
        assertEquals("retry", store.attachmentCleanupError())
        assertEquals(1, store.attachmentCleanupCount())
        cloud.fail = false
        assertTrue(AttachmentCleanup(store, cloud, { emptyList() }, { true }).run())
        assertEquals(0, store.attachmentCleanupCount())
    }

    @Test
    fun cleanupDeletesFilesButStopsWhenTheAccountChanges() = runBlocking {
        queueDeletion()
        store.acknowledge(store.pendingChanges().single().operationId)
        val cloud = AttachmentTestCloud()
        val record = TaskAttachment(AttachmentValidator.newId(), task.taskId, "Notes.pdf", "application/pdf", 10)
        cloud.records[record.id] = record
        var current = true
        cloud.afterList = { current = false }
        assertTrue(AttachmentCleanup(store, cloud, { emptyList() }, { current }).run())
        assertEquals(0, cloud.deleteCalls)
        assertEquals(1, store.attachmentCleanupCount())
        current = true
        cloud.afterList = {}
        assertTrue(AttachmentCleanup(store, cloud, { emptyList() }, { current }).run())
        assertEquals(1, cloud.deleteCalls)
        assertTrue(cloud.records.isEmpty())
    }

    @Test
    fun aRestoredTaskKeepsItsCloudAttachments() = runBlocking {
        queueDeletion()
        store.acknowledge(store.pendingChanges().single().operationId)
        val cloud = AttachmentTestCloud()
        assertTrue(AttachmentCleanup(store, cloud, { listOf(task) }, { true }).run())
        assertEquals(0, cloud.deleteCalls)
        assertEquals(0, store.attachmentCleanupCount())
    }

    @Test
    fun cleanupStopsWhenANewerLocalDeletionReplacesItsIntent() = runBlocking {
        queueDeletion()
        store.acknowledge(store.pendingChanges().single().operationId)
        val cloud = AttachmentTestCloud()
        val record = TaskAttachment(AttachmentValidator.newId(), task.taskId, "Notes.pdf", "application/pdf", 10)
        cloud.records[record.id] = record
        cloud.afterList = {
            store.saveTask(task)
            store.deleteTask(task.taskId)
        }
        AttachmentCleanup(store, cloud, { emptyList() }, { true }).run()
        assertEquals(0, cloud.deleteCalls)
        assertEquals(1, store.attachmentCleanupCount())
        assertTrue(store.attachmentCleanupTasks().isEmpty())
    }

    @Test
    fun staleCleanupCannotConsumeANewerDeletionOfARestoredTask() {
        queueDeletion()
        store.acknowledge(store.pendingChanges().single().operationId)
        val oldToken = store.attachmentCleanupToken(task.taskId)
        store.saveTask(task)
        store.deleteTask(task.taskId)
        assertTrue(store.attachmentCleanupTasks().isEmpty())
        val newestDelete = store.pendingChanges().last()
        store.acknowledge(newestDelete.operationId)
        store.finishAttachmentCleanup(task.taskId, oldToken)
        assertEquals(listOf(task.taskId), store.attachmentCleanupTasks())
        assertEquals(newestDelete.operationId, store.attachmentCleanupToken(task.taskId))
    }

    @Test
    fun versionTwoMigrationPreservesCacheOutboxIdsRejectionsAndSyncState() {
        val file = context.getDatabasePath(checkNotNull(store.databaseName))
        file.parentFile?.mkdirs()
        val payload = Gson().toJson(task)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE cache(kind TEXT NOT NULL, record_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind,record_id))")
            db.execSQL("CREATE TABLE outbox(sequence INTEGER PRIMARY KEY AUTOINCREMENT, operation_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, record_id TEXT NOT NULL, method TEXT NOT NULL, payload TEXT, error TEXT)")
            db.execSQL("CREATE TABLE local_meta(id INTEGER PRIMARY KEY, version INTEGER NOT NULL, message TEXT)")
            db.execSQL("INSERT INTO local_meta VALUES(1,42,'Saved status')")
            db.execSQL("INSERT INTO cache VALUES('tasks',?,?)", arrayOf(task.taskId, payload))
            db.execSQL("INSERT INTO outbox(operation_id,kind,record_id,method,payload,error) VALUES('old-put','tasks',?,'PUT',?,'Old rejection')", arrayOf(task.taskId, payload))
            db.execSQL("INSERT INTO outbox(operation_id,kind,record_id,method,payload,error) VALUES('old-delete','tasks','deleted-task','DELETE',NULL,NULL)")
            db.version = 2
        }
        assertEquals(42L, store.version())
        assertEquals(3, store.writableDatabase.version)
        assertEquals(listOf(task), store.getTasks())
        assertEquals("Saved status", store.syncMessage())
        val changes = store.pendingChanges()
        assertEquals(listOf("old-put", "old-delete"), changes.map { it.operationId })
        assertEquals("Old rejection", changes.first().error)
        assertEquals(payload, changes.first().payload)
        assertEquals(1, store.attachmentCleanupCount())
        assertTrue(store.attachmentCleanupTasks().isEmpty())
        store.acknowledge("old-delete")
        assertEquals(listOf("deleted-task"), store.attachmentCleanupTasks())
        assertEquals("old-put", store.pendingChanges().single().operationId)
    }
}
