package com.studysync.app

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LocalSyncStatus(
    val pending: Int = 0,
    val rejected: Int = 0,
    val message: String? = null,
    val rejection: String? = null
)

/**
 * Reads and writes SQLite data without requiring a network call.
 * Cloud snapshots are imported separately so network failures cannot block edits.
 * Reference: https://developer.android.com/topic/architecture/data-layer/offline-first
 */
class OfflineStudyRepository(
    context: Context,
    private val userId: String,
    private val remote: StudyRepository = ApiStudyRepository(userId),
    private val currentUserId: () -> String? = {
        FirebaseAuth.getInstance().currentUser?.uid
    },
    private val scheduleSync: (Boolean) -> Unit = { manual ->
        StudySync.enqueue(context, userId, manual)
    }
) : StudyRepository {
    private val store = LocalStudyStore.get(context, userId)
    private val localLock = locks.computeIfAbsent(userId) { Mutex() }

    val changes = store.changes

    override suspend fun getSubjects(): List<Subject> = local {
        store.getSubjects().sortedBy { it.name.lowercase() }
    }

    override suspend fun getTasks(): List<StudyTask> = local {
        store.getTasks()
    }

    override suspend fun createSubject(subject: Subject): Subject = write {
        val saved = subject.copy(
            subjectId = UUID.randomUUID().toString(),
            name = subject.name.trim(),
            lecturerName = subject.lecturerName.trim()
        )

        validateSubject(saved)
        store.saveSubject(saved)
        saved
    }

    override suspend fun updateSubject(subject: Subject): Subject = write {
        require(
            store.getSubjects().any {
                it.subjectId == subject.subjectId
            }
        ) {
            "This subject is no longer on this device. Refresh the list."
        }

        val saved = subject.copy(
            name = subject.name.trim(),
            lecturerName = subject.lecturerName.trim()
        )

        validateSubject(saved)
        store.saveSubject(saved)
        saved
    }

    override suspend fun deleteSubject(subjectId: String) {
        write {
            require(
                store.getTasks().none {
                    it.subjectId == subjectId
                }
            ) {
                "Delete or move this subject's tasks first."
            }

            store.deleteSubject(subjectId)
        }
    }

    override suspend fun createTask(task: StudyTask): StudyTask = write {
        val saved = cleanTask(
            task.copy(taskId = UUID.randomUUID().toString())
        )

        validateTask(saved)
        store.saveTask(saved)
        saved
    }

    override suspend fun updateTask(task: StudyTask): StudyTask = write {
        require(
            store.getTasks().any {
                it.taskId == task.taskId
            }
        ) {
            "This task is no longer on this device. Refresh the list."
        }

        val saved = cleanTask(task)

        validateTask(saved)
        store.saveTask(saved)
        saved
    }

    override suspend fun deleteTask(taskId: String) {
        write {
            store.deleteTask(taskId)
        }
    }

    suspend fun pendingCount(): Int = local {
        store.pendingCount()
    }

    suspend fun syncStatus(): LocalSyncStatus = local {
        LocalSyncStatus(store.pendingCount(), store.rejectedCount(), store.syncMessage(), store.firstRejection())
    }

    suspend fun retrySync() {
        write(manual = true) {
            store.retryRejected()
            store.setSyncMessage("Sync requested. Waiting for a connection if needed.")
        }
    }

    suspend fun discardRejected() {
        write(manual = true) {
            store.discardRejected()
            store.setSyncMessage("Rejected edits discarded. Cloud data will load when connected.")
        }
    }

    suspend fun refreshFromCloud(): Boolean {
        val expectedVersion = local { store.version() }

        // Keep the local lock free while waiting for HTTP responses.
        val subjects = remote.getSubjects()
        requireAccount()

        val tasks = remote.getTasks()

        return local {
            store.mergeRemote(
                expectedVersion,
                subjects,
                tasks
            ).also { applied ->
                Log.d(
                    "StudySync",
                    "Cloud cache refresh applied: $applied"
                )
            }
        }
    }

    private fun validateSubject(subject: Subject) {
        val error = SubjectValidator.validate(
            subject.name,
            subject.lecturerName,
            store.getSubjects(),
            subject.subjectId
        )

        require(error == null) {
            error ?: "Invalid subject."
        }
    }

    private fun cleanTask(task: StudyTask) = task.copy(
        subjectId = task.subjectId.trim(),
        title = task.title.trim(),
        description = task.description.trim(),
        dueDate = task.dueDate.trim()
    )

    private fun validateTask(task: StudyTask) {
        val error = TaskValidator.validate(task)

        require(error == null) {
            error ?: "Invalid task."
        }

        require(
            store.getSubjects().any {
                it.subjectId == task.subjectId
            }
        ) {
            "Choose one of your existing subjects."
        }
    }

    private fun requireAccount() {
        if (currentUserId() != userId) {
            throw ApiException(
                "Your account changed. Please sign in again.", statusCode = 401
            )
        }
    }

    private suspend fun <T> local(
        block: () -> T
    ): T = withContext(Dispatchers.IO) {
        localLock.withLock {
            requireAccount()
            block().also { requireAccount() }
        }
    }

    private suspend fun <T> write(manual: Boolean = false, block: () -> T): T = withContext(NonCancellable) {
        val result = local(block)
        try {
            scheduleSync(manual)
        } catch (exception: Exception) {
            // The saved outbox remains durable; startup/periodic work can recover it.
            Log.w("StudySync", "Could not schedule sync immediately", exception)
        }
        result
    }

    companion object {
        // Keep validation and writes ordered across instances for one account.
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
