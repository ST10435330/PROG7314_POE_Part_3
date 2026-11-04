package com.studysync.app

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AttachmentsViewModel @JvmOverloads constructor(
    application: Application,
    private val repositoryFactory: (String) -> AttachmentRepository = { AttachmentRepository(application, it) },
    private val signedInUserId: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid }
) : AndroidViewModel(application) {
    var taskId by mutableStateOf<String?>(null)
        private set
    var attachments by mutableStateOf<List<TaskAttachment>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableStateOf<Int?>(null)
        private set
    var cached by mutableStateOf(false)
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    var message by mutableStateOf<Int?>(null)
        private set
    var pickerRequested by mutableStateOf(false)
        private set
    var selectedUri by mutableStateOf<Uri?>(null)
        private set
    var readyFile by mutableStateOf<ReadyAttachment?>(null)
        private set
    var destinationUri by mutableStateOf<Uri?>(null)
        private set
    var canRetryUpload by mutableStateOf(false)
        private set

    private var userId: String? = null
    private var repository: AttachmentRepository? = null
    private var operation: Job? = null
    private var revision = 0
    private var pickerRevision: Int? = null
    private var exportRevision: Int? = null
    private var exportFile: ReadyAttachment? = null
    private var prepared: PreparedAttachment? = null

    fun setUser(uid: String?) {
        if (userId == uid) return
        closeTask()
        repository?.close()
        repository = null
        userId = uid
        viewModelScope.launch(Dispatchers.IO) {
            AttachmentFiles.clearOtherAccounts(getApplication(), uid) {
                signedInUserId() == uid
            }
        }
    }

    private fun repo(): AttachmentRepository {
        val uid = userId ?: throw AttachmentFailure(R.string.attachment_sign_in)
        repository?.let { return it }
        return try {
            repositoryFactory(uid).also { repository = it }
        } catch (_: IllegalStateException) {
            throw AttachmentFailure(R.string.attachment_setup_error)
        } catch (_: IllegalArgumentException) {
            throw AttachmentFailure(R.string.attachment_setup_error)
        }
    }

    fun openTask(id: String) {
        closeTask()
        taskId = id
        reload()
    }

    fun closeTask() {
        revision++
        operation?.cancel()
        repository?.cancel()
        prepared?.file?.delete()
        prepared = null
        taskId = null
        attachments = emptyList()
        busy = false
        progress = null
        error = null
        message = null
        cached = false
        canRetryUpload = false
        pickerRequested = false
        pickerRevision = null
        selectedUri = null
        readyFile = null
        destinationUri = null
        exportRevision = null
        exportFile = null
    }

    private fun run(fallback: Int, action: suspend (AttachmentRepository, Int) -> Unit) {
        if (busy || userId == null || taskId == null) return
        val version = revision
        busy = true
        error = null
        message = null
        operation = viewModelScope.launch {
            try { action(repo(), version) }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                if (revision == version) error = attachmentError(exception, fallback)
                Log.w("StudySync", "Attachment operation failed", exception)
            } finally {
                if (revision == version) { busy = false; progress = null }
            }
        }
    }

    fun reload() {
        val id = taskId ?: return
        run(R.string.attachment_list_error) { repository, version ->
            val local = repository.cached(id)
            if (revision != version) return@run
            attachments = local
            cached = true
            val loaded = repository.list(id)
            if (revision == version) { attachments = loaded; cached = false }
        }
    }

    fun requestPicker() {
        if (busy || taskId == null) return
        pickerRevision = revision
        pickerRequested = true
    }

    fun pickerLaunched() { pickerRequested = false }

    fun receiveSelection(uri: Uri?) {
        if (pickerRevision != revision) return
        pickerRevision = null
        if (uri == null) message = R.string.attachment_cancelled else selectedUri = uri
    }

    fun uploadSelection() {
        val uri = selectedUri ?: return
        val id = taskId ?: return
        selectedUri = null
        run(R.string.attachment_upload_error) { repository, version ->
            prepared?.file?.delete()
            prepared = null
            canRetryUpload = false
            prepared = repository.prepare(id, uri)
            canRetryUpload = true
            uploadPrepared(repository, version)
        }
    }

    private suspend fun uploadPrepared(repository: AttachmentRepository, version: Int) {
        val source = prepared ?: return
        repository.upload(source) { percent ->
            if (revision == version) progress = percent
        }
        if (revision != version) return
        source.file.delete()
        prepared = null
        canRetryUpload = false
        message = R.string.attachment_uploaded
        attachments = repository.list(source.attachment.taskId)
        cached = false
    }

    fun retryUpload() = run(R.string.attachment_upload_error) { repository, version ->
        uploadPrepared(repository, version)
    }

    fun download(attachment: TaskAttachment, action: AttachmentAction) {
        run(R.string.attachment_download_error) { repository, version ->
            val file = repository.download(attachment) { percent ->
                if (revision == version) progress = percent
            }
            if (revision == version) readyFile = ReadyAttachment(attachment, file, action)
        }
    }

    fun openReadyFile() {
        val ready = readyFile ?: return
        readyFile = null
        try {
            val repository = repo()
            repository.requireAccount()
            repository.files.open(ready.file, ready.attachment.contentType)
        }
        catch (exception: Exception) { error = attachmentError(exception, R.string.attachment_open_error) }
    }

    fun beginExport(): String? {
        val ready = readyFile ?: return null
        try { repo().requireAccount() } catch (exception: Exception) {
            readyFile = null
            error = attachmentError(exception, R.string.attachment_sign_in)
            return null
        }
        readyFile = null
        exportFile = ready
        exportRevision = revision
        return ready.attachment.name
    }

    fun receiveDestination(uri: Uri?) {
        if (exportRevision != revision) return
        if (uri == null) {
            exportFile = null
            exportRevision = null
            message = R.string.attachment_cancelled
        } else destinationUri = uri
    }

    fun saveCopy() {
        val uri = destinationUri ?: return
        val ready = exportFile ?: return
        destinationUri = null
        run(R.string.attachment_save_copy_error) { repository, version ->
            withContext(Dispatchers.IO) {
                repository.requireAccount()
                repository.files.export(ready.file, uri, repository::requireAccount)
            }
            if (revision == version) {
                exportFile = null
                exportRevision = null
                message = R.string.attachment_copy_saved
            }
        }
    }

    fun delete(attachment: TaskAttachment) = run(R.string.attachment_delete_error) { repository, version ->
        repository.delete(attachment)
        if (revision == version) {
            attachments = attachments.filterNot { it.id == attachment.id }
            message = R.string.attachment_deleted
        }
    }

    fun cancelOperation() {
        operation?.cancel()
        repository?.cancel()
        // Invalidate callbacks from the cancelled operation without losing the selected task.
        revision++
        pickerRevision = null
        pickerRequested = false
        exportRevision = null
        exportFile = null
        destinationUri = null
        busy = false
        progress = null
        readyFile = null
        selectedUri = null
        error = null
        message = R.string.attachment_cancelled
    }

    override fun onCleared() {
        operation?.cancel()
        repository?.close()
        prepared?.file?.delete()
    }
}
