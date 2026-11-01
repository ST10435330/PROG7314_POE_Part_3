package com.studysync.app

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class SyncOutcome { COMPLETE, RETRY, BLOCKED, STOPPED }

/** Uploads immutable operations in order, without replacing newer local edits. */
class StudySyncEngine(
    userId: String,
    private val store: LocalStudyStore,
    private val repository: OfflineStudyRepository,
    private val upload: suspend (PendingStudyChange) -> Unit,
    private val canRun: () -> Boolean
) {
    private val lock = locks.computeIfAbsent(userId) { Mutex() }

    suspend fun run(): SyncOutcome = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!canRun()) return@withLock SyncOutcome.STOPPED
            try {
                store.setSyncMessage("Sync started.")
                val deadline = SystemClock.elapsedRealtime() + 180_000L
                var attempts = 0
                var successes = 0
                var retriedRejected = false

                while (attempts < 50 && SystemClock.elapsedRealtime() < deadline) {
                    currentCoroutineContext().ensureActive()
                    if (!canRun()) return@withLock SyncOutcome.STOPPED
                    val change = store.pendingChanges(limit = 1, readyOnly = true).firstOrNull()
                    if (change == null) {
                        // A successfully uploaded parent may unblock a previously rejected task.
                        if (successes > 0 && !retriedRejected && store.rejectedCount() > 0) {
                            retriedRejected = true
                            store.retryRejected()
                            continue
                        }
                        break
                    }
                    attempts++
                    try {
                        upload(change)
                    } catch (exception: ApiException) {
                        if (!canRun()) return@withLock SyncOutcome.STOPPED
                        if (exception.statusCode in listOf(400, 409, 422) && !exception.retryable) {
                            store.reject(change.operationId, rejectionMessage(change, exception))
                            Log.w("StudySync", "Sync change rejected: ${exception.statusCode}")
                            continue
                        }
                        throw exception
                    } catch (exception: IllegalArgumentException) {
                        if (!canRun()) return@withLock SyncOutcome.STOPPED
                        // Locally invalid payloads have not reached the API successfully.
                        store.reject(change.operationId, rejectionMessage(change, exception))
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    if (!canRun()) return@withLock SyncOutcome.STOPPED
                    store.acknowledge(change.operationId)
                    successes++
                }

                if (store.pendingChanges(1, readyOnly = true).isNotEmpty()) {
                    store.setSyncMessage("More changes are waiting for the next sync attempt.")
                    return@withLock SyncOutcome.RETRY
                }
                if (!canRun()) return@withLock SyncOutcome.STOPPED
                val applied = repository.refreshFromCloud()
                if (!canRun()) return@withLock SyncOutcome.STOPPED
                if (!applied || store.pendingChanges(1, readyOnly = true).isNotEmpty()) {
                    return@withLock SyncOutcome.RETRY
                }
                store.setSyncMessage(
                    if (store.rejectedCount() > 0) "Some changes need attention. Edit them or retry."
                    else "Last sync completed."
                )
                Log.d("StudySync", "Sync finished; uploads acknowledged: $successes")
                SyncOutcome.COMPLETE
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ApiException) {
                if (!canRun()) return@withLock SyncOutcome.STOPPED
                store.setSyncMessage(exception.message ?: "Sync could not finish.")
                if (exception.retryable) SyncOutcome.RETRY else SyncOutcome.BLOCKED
            } catch (exception: Exception) {
                if (!canRun()) return@withLock SyncOutcome.STOPPED
                store.setSyncMessage("Sync could not finish. Your queued changes are saved.")
                Log.e("StudySync", "Sync failed with queued data retained", exception)
                SyncOutcome.BLOCKED
            }
        }
    }

    private fun rejectionMessage(change: PendingStudyChange, exception: Exception): String {
        val label = runCatching {
            JSONObject(change.payload ?: "{}").optString(
                if (change.kind == "subjects") "name" else "title"
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Deleted item"
        return "$label: ${exception.message ?: "Change rejected."}"
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
