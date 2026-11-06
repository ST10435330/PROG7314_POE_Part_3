package com.studysync.app

import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
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
    var cleanupCount by remember(userId) { mutableIntStateOf(0) }
    var cleanupError by remember(userId) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable(userId) { mutableStateOf(false) }
    val jobs by remember(context, userId) {
        WorkManager.getInstance(context).getWorkInfosFlow(
            WorkQuery.Builder.fromTags(listOf(StudySync.tag(userId))).build()
        )
    }.collectAsState(initial = emptyList())
    val syncing = jobs.any { it.state == WorkInfo.State.RUNNING }

    LaunchedEffect(repository, dataVersion) {
        try {
            status = repository.syncStatus()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val store = LocalStudyStore.get(context, userId)
                cleanupCount = store.attachmentCleanupCount()
                cleanupError = store.attachmentCleanupError()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            status = status.copy(message = AppMessage.SYNC_STATUS_ERROR.name)
            Log.e("StudySync", "Sync status loading failed", exception)
        }
    }
    LaunchedEffect(repository, userId) {
        try {
            StudySync.start(context, userId)
        } catch (exception: Exception) {
            status = status.copy(message = AppMessage.SYNC_START_ERROR.name)
            Log.e("StudySync", "Sync scheduling failed", exception)
        }
    }

    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                status = status.copy(message = apiErrorMessage(exception, AppMessage.SYNC_UPDATE_ERROR.name))
            }
        }
    }

    val pendingText = if (status.pending > 0) {
        context.resources.getQuantityString(R.plurals.sync_pending, status.pending, status.pending)
    } else context.getString(R.string.sync_no_pending)

    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(context.getString(R.string.ui_sync_title), style = MaterialTheme.typography.labelLarge)
                    Text(status.message?.let { messageText(context, it) } ?: pendingText,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { showDetails = true }) { Text(context.getString(R.string.ui_show_details)) }
            }
            if (status.pending > 0 && status.message != null) Text(pendingText, style = MaterialTheme.typography.bodySmall)
            if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.rejected > 0) {
                Text(context.resources.getQuantityString(R.plurals.sync_rejected, status.rejected, status.rejected),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (cleanupCount > 0) {
                Text(context.resources.getQuantityString(R.plurals.attachment_cleanup_pending, cleanupCount, cleanupCount),
                    color = if (cleanupError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(context.getString(R.string.ui_sync_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pendingText)
                    status.message?.let { Text(messageText(context, it)) }
                    if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (cleanupCount > 0) {
                        Text(context.resources.getQuantityString(R.plurals.attachment_cleanup_pending, cleanupCount, cleanupCount))
                        if (cleanupError != null) {
                            Text(context.getString(if (cleanupError == "permission") R.string.attachment_denied else R.string.attachment_cleanup_error),
                                color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { AttachmentCleanup.enqueue(context, userId, retry = true) }) {
                            Text(context.getString(R.string.attachment_retry_cleanup))
                        }
                    }
                    if (status.rejected > 0) {
                        Text(context.resources.getQuantityString(R.plurals.sync_rejected, status.rejected, status.rejected),
                            color = MaterialTheme.colorScheme.error)
                        status.rejection?.let { Text(rejectionText(context, it), color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(enabled = !syncing, onClick = { perform { repository.retrySync() } }) {
                        Text(context.getString(R.string.sync_now))
                    }
                    if (status.rejected > 0) {
                        TextButton(enabled = !syncing, onClick = { showDetails = false; confirmDiscard = true }) {
                            Text(context.getString(R.string.discard_rejected))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text(context.getString(R.string.done)) } }
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(context.getString(R.string.discard_rejected_title)) },
            text = { Text(context.getString(R.string.discard_rejected_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    perform { repository.discardRejected() }
                }) { Text(context.getString(R.string.discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(context.getString(R.string.cancel)) }
            }
        )
    }
}
