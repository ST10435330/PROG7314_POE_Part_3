package com.studysync.app

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Only a result from a picker launched by this unlocked session can resume it.
// Reference: https://developer.android.com/training/basics/intents/result
@Composable
fun AttachmentLaunchers(model: AttachmentsViewModel, biometricLock: BiometricLock,
    unlocked: Boolean, canAccess: () -> Boolean) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        biometricLock.completeDocumentPicker()
        model.receiveSelection(uri)
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        biometricLock.completeDocumentPicker()
        model.receiveDestination(uri)
    }

    LaunchedEffect(unlocked, model.pickerRequested, model.busy) {
        if (unlocked && canAccess() && model.pickerRequested && !model.busy && biometricLock.beginDocumentPicker()) {
            model.pickerLaunched()
            try { picker.launch(AttachmentValidator.types) }
            catch (_: ActivityNotFoundException) { biometricLock.cancelDocumentPicker(); model.receiveSelection(null) }
            catch (_: SecurityException) { biometricLock.cancelDocumentPicker(); model.receiveSelection(null) }
        }
    }
    LaunchedEffect(unlocked, model.selectedUri, model.busy) {
        if (unlocked && canAccess() && model.selectedUri != null && !model.busy) model.uploadSelection()
    }
    LaunchedEffect(unlocked, model.readyFile, model.busy) {
        if (unlocked && canAccess() && !model.busy) {
            when (model.readyFile?.action) {
                AttachmentAction.OPEN -> model.openReadyFile()
                AttachmentAction.SAVE_COPY -> if (biometricLock.beginDocumentPicker()) {
                    val name = model.beginExport()
                    if (name == null) biometricLock.cancelDocumentPicker()
                    else try { save.launch(name) }
                    catch (_: ActivityNotFoundException) { biometricLock.cancelDocumentPicker(); model.receiveDestination(null) }
                    catch (_: SecurityException) { biometricLock.cancelDocumentPicker(); model.receiveDestination(null) }
                }
                null -> Unit
            }
        }
    }
    LaunchedEffect(unlocked, model.destinationUri, model.busy) {
        if (unlocked && canAccess() && model.destinationUri != null && !model.busy) model.saveCopy()
    }
}

@Composable
fun TaskAttachmentsDialog(task: StudyTask, model: AttachmentsViewModel) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<TaskAttachment?>(null) }
    AlertDialog(
        onDismissRequest = model::closeTask,
        title = { Text(context.getString(R.string.attachment_title, task.title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(context.getString(R.string.attachment_limits))
                Button(onClick = model::requestPicker, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(context.getString(R.string.attachment_add))
                }
                OutlinedButton(onClick = model::reload, enabled = !model.busy) {
                    Text(context.getString(R.string.refresh))
                }
                if (model.busy) {
                    val progress = model.progress
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(context.getString(R.string.attachment_progress, progress))
                    }
                    Text(context.getString(R.string.attachment_working))
                    TextButton(onClick = model::cancelOperation) { Text(context.getString(R.string.cancel)) }
                }
                if (model.cached) Text(context.getString(R.string.attachment_cached))
                model.error?.let { Text(context.getString(it), color = MaterialTheme.colorScheme.error) }
                model.message?.let { Text(context.getString(it), color = MaterialTheme.colorScheme.primary) }
                if (model.canRetryUpload && !model.busy) {
                    TextButton(onClick = model::retryUpload) { Text(context.getString(R.string.attachment_retry_upload)) }
                }
                if (!model.busy && model.error == null && !model.cached && model.attachments.isEmpty()) {
                    Text(context.getString(R.string.attachment_empty_list))
                }
                model.attachments.forEach { attachment ->
                    HorizontalDivider()
                    Text(attachment.name, style = MaterialTheme.typography.titleSmall)
                    Text(context.getString(R.string.attachment_size, (attachment.size + 1023) / 1024))
                    OutlinedButton(enabled = !model.busy, onClick = { model.download(attachment, AttachmentAction.OPEN) },
                        modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.attachment_open)) }
                    OutlinedButton(enabled = !model.busy, onClick = { model.download(attachment, AttachmentAction.SAVE_COPY) },
                        modifier = Modifier.fillMaxWidth()) { Text(context.getString(R.string.attachment_save_copy)) }
                    TextButton(enabled = !model.busy, onClick = { deleting = attachment }) {
                        Text(context.getString(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = model::closeTask) { Text(context.getString(R.string.done)) } }
    )
    deleting?.let { attachment ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(context.getString(R.string.attachment_confirm_delete)) },
            text = { Text(context.getString(R.string.attachment_delete_prompt, attachment.name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; model.delete(attachment) }) {
                    Text(context.getString(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(context.getString(R.string.cancel)) } }
        )
    }
}
