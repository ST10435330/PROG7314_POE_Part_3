package com.studysync.app

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// Run separately so a Storage failure never blocks the task sync outbox.
class AttachmentCleanup(
    private val store: LocalStudyStore,
    private val cloud: AttachmentCloud,
    private val remoteTasks: suspend () -> List<StudyTask>,
    private val canRun: () -> Boolean,
    private val clearFiles: (String) -> Unit = {}
) {
    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        val ids = store.attachmentCleanupTasks()
        if (ids.isEmpty() || !canRun()) return@withContext true
        val existing = remoteTasks().map { it.taskId }.toSet()
        var successful = true
        for (id in ids) {
            if (!canRun()) return@withContext true
            val token = store.attachmentCleanupToken(id) ?: continue
            try {
                // A task restored by another device keeps its attachments.
                if (id !in existing) {
                    for (attachment in cloud.list(id)) {
                        if (!canRun()) return@withContext true
                        if (store.attachmentCleanupToken(id) != token) break
                        cloud.delete(attachment)
                    }
                    if (store.attachmentCleanupToken(id) != token) continue
                    clearFiles(id)
                }
                if (canRun()) store.finishAttachmentCleanup(id, token)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (!canRun()) return@withContext true
                val message = attachmentError(exception, R.string.attachment_cleanup_error)
                store.failAttachmentCleanup(id, if (message == R.string.attachment_denied) "permission" else "retry", token)
                successful = false
                Log.w("StudySync", "Attachment cleanup needs retry", exception)
            }
        }
        successful && store.attachmentCleanupTasks().isEmpty()
    }

    companion object {
        fun enqueue(context: Context, userId: String, retry: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<AttachmentCleanupWorker>()
                .setInputData(workDataOf("userId" to userId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "studysync-$userId-attachment-cleanup",
                if (retry) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request
            )
        }
    }
}

class AttachmentCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("userId") ?: return Result.failure()
        fun canRun() = !isStopped && FirebaseAuth.getInstance().currentUser?.uid == uid
        if (!canRun()) return Result.success()
        val store = LocalStudyStore.get(applicationContext, uid)
        if (store.attachmentCleanupTasks().isEmpty()) return Result.success()
        var cloud: AttachmentCloud? = null
        return try {
            val client = ApiAttachmentCloud(uid)
            cloud = client
            val api = ApiStudyRepository(uid)
            val files = AttachmentFiles(applicationContext, uid)
            if (AttachmentCleanup(store, client, api::getTasks, ::canRun, files::clearTask).run()) {
                Result.success()
            } else Result.retry()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (canRun()) {
                withContext(Dispatchers.IO) {
                    store.attachmentCleanupTasks().forEach { store.failAttachmentCleanup(it, "retry") }
                }
            }
            Log.w("StudySync", "Could not run attachment cleanup", exception)
            if (canRun()) Result.retry() else Result.success()
        } finally { cloud?.close() }
    }
}
