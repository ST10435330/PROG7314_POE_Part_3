package com.studysync.app

import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StudySyncTest {
    private val uid = UUID.randomUUID().toString()
    private var active = true
    private val cloud = FakeCloud()
    private val context get() = RuntimeEnvironment.getApplication()
    private val store by lazy { LocalStudyStore.get(context, uid) }
    private val repository by lazy {
        OfflineStudyRepository(context, uid, cloud,
            currentUserId = { if (active) uid else null }, scheduleSync = {})
    }
    private fun engine(send: suspend (PendingStudyChange) -> Unit = cloud::apply) =
        StudySyncEngine(uid, store, repository, send, canRun = { active })

    @After
    fun closeStore() { store.close() }

    @Test
    fun queuedChangesReachCloud() = runBlocking {
        val subject = repository.createSubject(Subject("", "Programming"))
        val task = repository.createTask(StudyTask(
            subjectId = subject.subjectId, title = "Revise", dueDate = "2026-11-30"
        ))
        assertEquals(SyncOutcome.COMPLETE, engine().run())
        assertEquals(0, store.pendingCount())
        assertEquals(task, cloud.tasks[task.taskId])
        assertEquals(subject, cloud.subjects[subject.subjectId])
    }

    @Test
    fun lostReplyKeepsSameOperation() = runBlocking {
        repository.createSubject(Subject("", "Programming"))
        val original = store.pendingChanges().single()
        var lost = false
        val sender: suspend (PendingStudyChange) -> Unit = {
            cloud.apply(it)
            if (!lost) {
                lost = true
                throw ApiException("Lost reply", retryable = true)
            }
        }
        assertEquals(SyncOutcome.RETRY, engine(sender).run())
        assertEquals(original, store.pendingChanges().single())
        assertEquals(SyncOutcome.COMPLETE, engine(sender).run())
        assertEquals(0, store.pendingCount())
        assertEquals(1, cloud.applied)
        assertEquals(listOf(original.operationId, original.operationId), cloud.received.map { it.operationId })
    }

    @Test
    fun newerEditIsUploadedNext() = runBlocking {
        val subject = repository.createSubject(Subject("", "Original"))
        var edited = false
        val sender: suspend (PendingStudyChange) -> Unit = {
            cloud.apply(it)
            if (!edited) {
                edited = true
                repository.updateSubject(subject.copy(name = "Latest"))
            }
        }
        assertEquals(SyncOutcome.COMPLETE, engine(sender).run())
        assertEquals(2, cloud.applied)
        assertEquals("Latest", store.getSubjects().single().name)
        assertEquals("Latest", cloud.subjects.values.single().name)
    }

    @Test
    fun accountSwitchKeepsQueue() = runBlocking {
        repository.createSubject(Subject("", "Programming"))
        val original = store.pendingChanges().single()
        assertEquals(SyncOutcome.STOPPED, engine {
            cloud.apply(it)
            active = false
        }.run())
        assertEquals(listOf(original), store.pendingChanges())
    }

    @Test
    fun rejectedEditCanBeCorrected() = runBlocking {
        val subject = repository.createSubject(Subject("", "Rejected"))
        val oldId = store.pendingChanges().single().operationId
        assertEquals(SyncOutcome.COMPLETE, engine().run())
        assertEquals(1, store.rejectedCount())
        assertEquals("Rejected", store.getSubjects().single().name)
        repository.updateSubject(subject.copy(name = "Fixed"))
        assertEquals(0, store.rejectedCount())
        assertNotEquals(oldId, store.pendingChanges().single().operationId)
        assertEquals(SyncOutcome.COMPLETE, engine().run())
        assertEquals(0, store.pendingCount())
        assertEquals("Fixed", cloud.subjects.values.single().name)
    }

    @Test
    fun discardKeepsOtherChanges() = runBlocking {
        val original = Subject("existing", "Original")
        cloud.subjects[original.subjectId] = original
        repository.refreshFromCloud()
        repository.updateSubject(original.copy(name = "Rejected"))
        store.reject(store.pendingChanges().single().operationId, "Rejected update")
        repository.createSubject(Subject("", "Other subject"))
        val other = store.pendingChanges(readyOnly = true).single()
        repository.discardRejected()
        assertEquals(listOf(other), store.pendingChanges())
        assertEquals(SyncOutcome.COMPLETE, engine().run())
        assertEquals(0, store.pendingCount())
        assertTrue(store.getSubjects().any { it == original })
        assertEquals(2, cloud.subjects.size)
    }

    @Test
    fun parentFixUnblocksTask() = runBlocking {
        val parent = Subject("parent", "Programming")
        store.mergeRemote(store.version(), listOf(parent), emptyList())
        val task = repository.createTask(StudyTask(
            subjectId = parent.subjectId, title = "Revise", dueDate = "2026-11-30"
        ))
        store.reject(store.pendingChanges().single().operationId, "Parent not uploaded")
        repository.updateSubject(parent.copy(name = "Fixed parent"))
        assertEquals(SyncOutcome.COMPLETE, engine().run())
        assertEquals(0, store.pendingCount())
        assertEquals(task, cloud.tasks[task.taskId])
    }

    @Test
    fun newerAckClearsOldRejection() {
        val subject = Subject("subject-1", "Original")
        store.saveSubject(subject)
        val old = store.pendingChanges().single()
        store.saveSubject(subject.copy(name = "Latest"))
        val newest = store.pendingChanges().last()
        store.reject(old.operationId, "Rejected earlier edit")
        store.acknowledge(newest.operationId)
        assertEquals(0, store.pendingCount())
        assertEquals("Latest", store.getSubjects().single().name)
    }

    @Test
    fun v1QueueSurvivesMigration() {
        val subject = Subject("legacy-subject", "Programming")
        val payload = Gson().toJson(subject)
        val file = context.getDatabasePath(checkNotNull(store.databaseName))
        file.parentFile?.mkdirs()
        // Create the actual previous schema, before this helper first opens it.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE cache(kind TEXT NOT NULL, record_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind, record_id))")
            db.execSQL("CREATE TABLE outbox(sequence INTEGER PRIMARY KEY AUTOINCREMENT, operation_id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, record_id TEXT NOT NULL, method TEXT NOT NULL, payload TEXT)")
            db.execSQL("CREATE TABLE local_meta(id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO local_meta VALUES(1, 7)")
            db.execSQL("INSERT INTO cache VALUES(?, ?, ?)", arrayOf("subjects", subject.subjectId, payload))
            db.execSQL("INSERT INTO outbox(operation_id,kind,record_id,method,payload) VALUES(?,?,?,?,?)",
                arrayOf("legacy-operation", "subjects", subject.subjectId, "PUT", payload))
            db.version = 1
        }
        assertEquals(7L, store.version())
        assertEquals(listOf(subject), store.getSubjects())
        assertEquals("legacy-operation", store.pendingChanges().single().operationId)
        assertEquals(0, store.rejectedCount())
        assertNull(store.syncMessage())
    }

    @Test
    fun manualRetryRequestsRestart() = runBlocking {
        val requests = mutableListOf<Boolean>()
        val repo = OfflineStudyRepository(context, uid, cloud,
            currentUserId = { uid }, scheduleSync = { requests.add(it) })
        repo.createSubject(Subject("", "Programming"))
        repo.retrySync()
        assertEquals(listOf(false, true), requests)
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun scheduleFailureKeepsSave() = runBlocking {
        val repo = OfflineStudyRepository(context, uid, cloud,
            currentUserId = { uid }, scheduleSync = { error("Scheduler unavailable") })
        val subject = repo.createSubject(Subject("", "Programming"))
        assertEquals(listOf(subject), store.getSubjects())
        assertEquals(1, store.pendingCount())
    }

    // This fake implements the server's receipt contract; no live cloud is used.
    private class FakeCloud : StudyRepository {
        val subjects = linkedMapOf<String, Subject>()
        val tasks = linkedMapOf<String, StudyTask>()
        val received = mutableListOf<PendingStudyChange>()
        private val receipts = mutableSetOf<String>()
        private val gson = Gson()
        var applied = 0
        fun apply(change: PendingStudyChange) {
            received.add(change)
            if (change.operationId in receipts) return
            if (change.method == "DELETE") {
                if (change.kind == "subjects") subjects.remove(change.recordId) else tasks.remove(change.recordId)
            } else if (change.kind == "subjects") {
                val subject = gson.fromJson(change.payload, Subject::class.java)
                if (subject.name == "Rejected") throw ApiException("Name conflict", statusCode = 409)
                subjects[subject.subjectId] = subject
            } else {
                val task = gson.fromJson(change.payload, StudyTask::class.java)
                if (task.subjectId !in subjects) throw ApiException("Subject missing", statusCode = 400)
                tasks[task.taskId] = task
            }
            receipts.add(change.operationId)
            applied++
        }
        override suspend fun getSubjects() = subjects.values.toList()
        override suspend fun getTasks() = tasks.values.toList()
        override suspend fun createSubject(subject: Subject): Subject = error("Use sync upload")
        override suspend fun updateSubject(subject: Subject): Subject = error("Use sync upload")
        override suspend fun deleteSubject(subjectId: String): Unit = error("Use sync upload")
        override suspend fun createTask(task: StudyTask): StudyTask = error("Use sync upload")
        override suspend fun updateTask(task: StudyTask): StudyTask = error("Use sync upload")
        override suspend fun deleteTask(taskId: String): Unit = error("Use sync upload")
    }
}
