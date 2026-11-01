package com.studysync.app

import android.content.Context
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/** Persistent scheduling with network constraints and exponential retry backoff.
 * Reference: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
 */
object StudySync {
    private const val USER = "userId"
    fun tag(userId: String) = "studysync-$userId"
    private fun name(userId: String) = "${tag(userId)}-uploads"
    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start(context: Context, userId: String) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val periodic = PeriodicWorkRequestBuilder<StudySyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(USER to userId))
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(tag(userId)).build()
        manager.enqueueUniquePeriodicWork(
            "${tag(userId)}-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        manager.enqueueUniqueWork(name(userId), ExistingWorkPolicy.KEEP, request(userId))
    }

    fun enqueue(
        context: Context,
        userId: String,
        manual: Boolean = false
    ) {
        // Appending avoids losing an edit made just as an existing worker exits.
        // A manual retry starts a fresh attempt instead of waiting behind backoff.
        val policy = if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(name(userId), policy, request(userId))
    }

    private fun request(userId: String) = OneTimeWorkRequestBuilder<StudySyncWorker>()
        .setInputData(workDataOf(USER to userId))
        .setConstraints(constraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(tag(userId)).build()
}

class StudySyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString("userId") ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser?.uid != userId) return Result.success()
        val api = ApiStudyRepository(userId)
        val outcome = StudySyncEngine(
            userId,
            LocalStudyStore.get(applicationContext, userId),
            OfflineStudyRepository(applicationContext, userId, remote = api),
            upload = api::upload,
            canRun = { !isStopped && FirebaseAuth.getInstance().currentUser?.uid == userId }
        ).run()
        return when (outcome) {
            SyncOutcome.RETRY -> Result.retry()
            SyncOutcome.BLOCKED -> Result.failure()
            SyncOutcome.COMPLETE, SyncOutcome.STOPPED -> Result.success()
        }
    }
}
