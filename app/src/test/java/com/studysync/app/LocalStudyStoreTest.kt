package com.studysync.app

import android.database.sqlite.SQLiteException
import com.google.gson.Gson
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
class LocalStudyStoreTest {
    private val stores = mutableListOf<LocalStudyStore>()

    private val subject = Subject(
        "subject-1",
        "Programming"
    )

    private val task = StudyTask(
        taskId = "task-1",
        subjectId = "subject-1",
        title = "Revise Kotlin",
        dueDate = "2026-11-30"
    )

    private fun store(
        uid: String = UUID.randomUUID().toString()
    ): LocalStudyStore =
        LocalStudyStore.get(
            RuntimeEnvironment.getApplication(),
            uid
        ).also {
            stores.add(it)
        }

    @After
    fun closeStores() {
        stores.distinct().forEach { it.close() }
    }

    @Test
    fun savingCreatesBothCacheAndImmutableOperation() {
        val db = store()

        db.saveSubject(subject)
        val first = db.pendingChanges().single()

        db.saveSubject(subject.copy(name = "Updated"))
        val second = db.pendingChanges().last()

        assertEquals("subjects", first.kind)
        assertEquals("PUT", first.method)
        assertEquals(subject.subjectId, first.recordId)
        assertNotEquals(first.operationId, second.operationId)

        assertEquals(
            "Programming",
            Gson().fromJson(first.payload, Subject::class.java).name
        )

        assertEquals("Updated", db.getSubjects().single().name)
        assertEquals(2, db.pendingCount())
    }

    @Test
    fun cacheAndOperationIdsSurviveClosingTheDatabase() {
        val uid = UUID.randomUUID().toString()
        val db = store(uid)

        db.saveTask(task)
        val pending = db.pendingChanges()
        db.close()

        val reopened = store(uid)

        assertEquals(listOf(task), reopened.getTasks())
        assertEquals(pending, reopened.pendingChanges())
    }

    @Test
    fun accountsNeverShareCacheOrPendingChanges() {
        val first = store()
        val second = store()

        first.saveSubject(subject)

        assertTrue(second.getSubjects().isEmpty())
        assertEquals(0, second.pendingCount())

        second.saveSubject(
            subject.copy(name = "Other account")
        )

        assertEquals(
            "Programming",
            first.getSubjects().single().name
        )

        assertEquals(
            "Other account",
            second.getSubjects().single().name
        )
    }

    @Test
    fun acknowledgingAnOlderSavePreservesTheNewerEdit() {
        val db = store()

        db.saveTask(task)
        val first = db.pendingChanges().single()

        val updated = task.copy(completed = true)
        db.saveTask(updated)

        val newest = db.pendingChanges().last()

        db.acknowledge(first.operationId)
        db.acknowledge(first.operationId)

        assertEquals(listOf(newest), db.pendingChanges())
        assertEquals(listOf(updated), db.getTasks())
    }

    @Test
    fun cloudRefreshPreservesPendingEditsAndPendingDeletes() {
        val db = store()

        assertTrue(
            db.mergeRemote(
                db.version(),
                listOf(subject),
                listOf(task)
            )
        )

        db.saveSubject(
            subject.copy(name = "Offline edit")
        )
        db.deleteTask(task.taskId)

        assertTrue(
            db.mergeRemote(
                db.version(),
                listOf(subject),
                listOf(task)
            )
        )

        assertEquals(
            "Offline edit",
            db.getSubjects().single().name
        )

        assertTrue(db.getTasks().isEmpty())
        assertEquals(2, db.pendingCount())

        val deletion = db.pendingChanges().last()

        assertEquals("DELETE", deletion.method)
        assertNull(deletion.payload)
    }

    @Test
    fun staleRefreshKeepsLatestEdit() {
        val db = store()

        db.mergeRemote(
            db.version(),
            listOf(subject),
            listOf(task)
        )

        val beforeNetworkRequest = db.version()
        val updated = task.copy(title = "Latest local title")

        db.saveTask(updated)
        db.acknowledge(
            db.pendingChanges().single().operationId
        )

        assertFalse(
            db.mergeRemote(
                beforeNetworkRequest,
                listOf(subject),
                listOf(task)
            )
        )

        assertEquals(listOf(updated), db.getTasks())
    }

    @Test
    fun cleanSnapshotRemovesRecords() {
        val db = store()

        db.mergeRemote(
            db.version(),
            listOf(subject),
            listOf(task)
        )

        db.mergeRemote(
            db.version(),
            emptyList(),
            emptyList()
        )

        assertTrue(db.getSubjects().isEmpty())
        assertTrue(db.getTasks().isEmpty())
        assertEquals(0, db.pendingCount())
    }

    @Test
    fun outboxFailureRollsBackTheCacheSave() {
        val db = store()

        // Simulate an upload-queue insert failing after the cache write.
        db.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox
            BEGIN SELECT RAISE(ABORT, 'test outbox failure'); END
            """.trimIndent()
        )

        assertThrows(SQLiteException::class.java) {
            db.saveSubject(subject)
        }

        assertTrue(db.getSubjects().isEmpty())
        assertEquals(0, db.pendingCount())
        assertEquals(0L, db.version())
    }
}