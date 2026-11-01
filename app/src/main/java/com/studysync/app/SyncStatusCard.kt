package com.studysync.app

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SyncStatusCard(repository: OfflineStudyRepository, userId: String, dataVersion: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(userId) { mutableStateOf(LocalSyncStatus()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val jobs by remember(context, userId) {
        WorkManager.getInstance(context).getWorkInfosFlow(
            WorkQuery.Builder.fromTags(listOf(StudySync.tag(userId))).build()
        )
    }.collectAsState(initial = emptyList())
    val syncing = jobs.any { it.state == WorkInfo.State.RUNNING }

    LaunchedEffect(repository, dataVersion) {
        try {
            status = repository.syncStatus()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            status = status.copy(message = "Could not read the sync status.")
            Log.e("StudySync", "Sync status loading failed", exception)
        }
    }
    LaunchedEffect(repository, userId) {
        try {
            StudySync.start(context, userId)
        } catch (exception: Exception) {
            status = status.copy(message = "Could not start background sync. Try Sync now.")
            Log.e("StudySync", "Sync scheduling failed", exception)
        }
    }

    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                status = status.copy(message = apiErrorMessage(exception, "Could not update sync. Please retry."))
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (status.pending > 0) "${status.pending} device changes waiting to sync"
                else "No device changes waiting to upload.",
                style = MaterialTheme.typography.bodySmall
            )
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (status.rejected > 0) {
                Text("${status.rejected} changes need attention.", color = MaterialTheme.colorScheme.error)
                status.rejection?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !syncing, onClick = { perform { repository.retrySync() } }) {
                    Text("Sync now")
                }
                if (status.rejected > 0) {
                    TextButton(enabled = !syncing, onClick = { confirmDiscard = true }) {
                        Text("Discard rejected changes")
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard rejected changes?") },
            text = { Text("Rejected device edits will be removed and cloud versions loaded when connected. Other queued changes are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    perform { repository.discardRejected() }
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") }
            }
        )
    }
}
