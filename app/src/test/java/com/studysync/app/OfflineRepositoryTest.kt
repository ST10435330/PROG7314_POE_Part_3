package com.studysync.app

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OfflineRepositoryTest {
    private val uid = UUID.randomUUID().toString()
    private var activeUser: String? = uid

    private val cloud = FakeCloud()

    private val store by lazy {
        LocalStudyStore.get(
            RuntimeEnvironment.getApplication(),
            uid
        )
    }

    private val repository by lazy {
        OfflineStudyRepository(
            RuntimeEnvironment.getApplication(),
            uid,
            cloud,
            currentUserId = { activeUser }
        )
    }

    private val subject = Subject(
        "cloud-subject",
        "Programming"
    )

    private val task = StudyTask(
        taskId = "cloud-task",
        subjectId = subject.subjectId,
        title = "Revise Kotlin",
        dueDate = "2026-11-30"
    )

    @After
    fun closeStore() {
        store.close()
    }

    @Test
    fun offlineCrudUsesNoNetwork() = runBlocking {
        cloud.failure = IOException("Offline")

        val savedSubject = repository.createSubject(
            Subject("", " Programming ")
        )

        val savedTask = repository.createTask(
            task.copy(subjectId = savedSubject.subjectId)
        )

        assertTrue(savedSubject.subjectId.isNotBlank())
        assertNotEquals(task.taskId, savedTask.taskId)
        assertEquals("Programming", savedSubject.name)

        val updated = repository.updateTask(
            savedTask.copy(completed = true)
        )

        assertEquals(listOf(updated), repository.getTasks())
        assertEquals(3, repository.pendingCount())
        assertEquals(0, cloud.reads)
    }

    @Test
    fun invalidChangesStayOut() = runBlocking {
        val saved = repository.createSubject(
            Subject("", "Programming")
        )

        invalid {
            repository.createSubject(
                Subject("", "PROGRAMMING")
            )
        }

        invalid {
            repository.createTask(
                task.copy(
                    subjectId = saved.subjectId,
                    dueDate = "2026-02-30"
                )
            )
        }

        invalid {
            repository.createTask(
                task.copy(subjectId = "missing")
            )
        }

        assertEquals(1, repository.pendingCount())
        assertTrue(repository.getTasks().isEmpty())
    }

    @Test
    fun linkedSubjectCannotBeDeleted() = runBlocking {
        val saved = repository.createSubject(
            Subject("", "Programming")
        )

        val savedTask = repository.createTask(
            task.copy(subjectId = saved.subjectId)
        )

        invalid {
            repository.deleteSubject(saved.subjectId)
        }

        assertEquals(2, repository.pendingCount())

        repository.deleteTask(savedTask.taskId)
        repository.deleteSubject(saved.subjectId)

        assertTrue(repository.getTasks().isEmpty())
        assertTrue(repository.getSubjects().isEmpty())

        assertEquals(
            listOf("PUT", "PUT", "DELETE", "DELETE"),
            store.pendingChanges().map { it.method }
        )
    }

    @Test
    fun cloudRefreshPopulatesCache() = runBlocking {
        cloud.subjects = listOf(subject)
        cloud.tasks = listOf(task)

        assertTrue(repository.refreshFromCloud())
        assertEquals(listOf(subject), repository.getSubjects())
        assertEquals(listOf(task), repository.getTasks())
        assertEquals(0, repository.pendingCount())
    }

    @Test
    fun refreshKeepsPendingEdit() = runBlocking {
        cloud.subjects = listOf(subject)
        repository.refreshFromCloud()

        repository.updateSubject(
            subject.copy(name = "Local edit")
        )

        assertTrue(repository.refreshFromCloud())

        assertEquals(
            "Local edit",
            repository.getSubjects().single().name
        )

        assertEquals(1, repository.pendingCount())
    }

    @Test
    fun failedRefreshKeepsCache() = runBlocking {
        cloud.subjects = listOf(subject)
        repository.refreshFromCloud()

        cloud.failure = IOException("Offline")

        try {
            repository.refreshFromCloud()
            fail("Expected a network failure")
        } catch (_: IOException) {
            assertEquals(
                listOf(subject),
                repository.getSubjects()
            )
        }
    }

    @Test
    fun accountChangeBlocksAccess() = runBlocking {
        repository.createSubject(
            Subject("", "Programming")
        )

        activeUser = "another-account"

        blocked {
            repository.getSubjects()
        }

        blocked {
            repository.createSubject(
                Subject("", "Networks")
            )
        }

        assertEquals(1, store.pendingCount())
    }

    @Test
    fun editDuringRefreshWins() = runBlocking {
        cloud.subjects = listOf(subject)
        repository.refreshFromCloud()

        cloud.beforeSubjects = {
            repository.updateSubject(
                subject.copy(name = "Newer edit")
            )
        }

        assertFalse(repository.refreshFromCloud())

        assertEquals(
            "Newer edit",
            repository.getSubjects().single().name
        )

        assertEquals(1, repository.pendingCount())
    }

    private suspend fun invalid(
        block: suspend () -> Unit
    ) {
        try {
            block()
            fail("Expected validation failure")
        } catch (exception: IllegalArgumentException) {
            assertFalse(exception.message.isNullOrBlank())
        }
    }

    private suspend fun blocked(
        block: suspend () -> Unit
    ) {
        try {
            block()
            fail("Expected account protection")
        } catch (_: ApiException) {
            // Access was refused before reading or writing.
        }
    }

    private class FakeCloud : StudyRepository {
        var subjects = emptyList<Subject>()
        var tasks = emptyList<StudyTask>()
        var failure: IOException? = null
        var reads = 0
        var beforeSubjects: (suspend () -> Unit)? = null

        override suspend fun getSubjects(): List<Subject> {
            reads++
            failure?.let { throw it }
            beforeSubjects?.invoke()
            return subjects
        }

        override suspend fun getTasks(): List<StudyTask> {
            reads++
            failure?.let { throw it }
            return tasks
        }

        // Offline edits must never use the old cloud write endpoints.
        override suspend fun createSubject(
            subject: Subject
        ): Subject = error("Unexpected cloud write")

        override suspend fun updateSubject(
            subject: Subject
        ): Subject = error("Unexpected cloud write")

        override suspend fun deleteSubject(
            subjectId: String
        ): Unit = error("Unexpected cloud write")

        override suspend fun createTask(
            task: StudyTask
        ): StudyTask = error("Unexpected cloud write")

        override suspend fun updateTask(
            task: StudyTask
        ): StudyTask = error("Unexpected cloud write")

        override suspend fun deleteTask(
            taskId: String
        ): Unit = error("Unexpected cloud write")
    }
}