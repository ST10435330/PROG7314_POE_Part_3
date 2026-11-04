package com.studysync.app

import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AttachmentsViewModelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uid = UUID.randomUUID().toString()
    private var current: String? = uid
    private val task = StudyTask(taskId = "task-1", title = "Study")
    private lateinit var store: LocalStudyStore
    private lateinit var cloud: AttachmentTestCloud
    private lateinit var model: AttachmentsViewModel
    private lateinit var owner: ViewModelStore
    private lateinit var source: File

    @Before
    fun setup() {
        store = LocalStudyStore.get(context, uid)
        store.saveTask(task)
        cloud = AttachmentTestCloud()
        model = AttachmentsViewModel(context, { account ->
            AttachmentRepository(context, account, cloud, AttachmentTestTasks(listOf(task))) { current }
        }, { current })
        owner = ViewModelStore().apply { put("attachments", model) }
        source = File.createTempFile("attachment", ".pdf", context.cacheDir)
        source.writeText("%PDF-1.7\nNotes")
        model.setUser(uid)
        model.openTask(task.taskId)
        await { !model.busy }
    }

    @After
    fun cleanup() {
        owner.clear()
        AttachmentFiles(context, uid).clear()
        source.delete()
        store.close()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Attachment state: busy=${model.busy}, error=${model.error}", condition())
    }

    @Test
    fun pickerResultWaitsForTheUnlockedUiToConsumeIt() {
        model.requestPicker()
        model.pickerLaunched()
        model.receiveSelection(Uri.fromFile(source))
        assertNotNull(model.selectedUri)
        assertEquals(0, cloud.uploadCalls)
        model.uploadSelection()
        await { !model.busy }
        assertNull(model.error)
        assertEquals(1, model.attachments.size)
        assertEquals(1, cloud.uploadCalls)
    }

    @Test
    fun signingOutDiscardsPendingPickerResultsAndAttachmentState() {
        model.requestPicker()
        model.pickerLaunched()
        current = null
        model.setUser(null)
        model.receiveSelection(Uri.fromFile(source))
        assertNull(model.taskId)
        assertNull(model.selectedUri)
        assertTrue(model.attachments.isEmpty())
        assertEquals(0, cloud.uploadCalls)
    }

    @Test
    fun uploadFailureCanRetryTheSameObjectWithoutDuplicateFiles() {
        cloud.fail = true
        model.requestPicker()
        model.pickerLaunched()
        model.receiveSelection(Uri.fromFile(source))
        model.uploadSelection()
        await { !model.busy }
        assertNotNull(model.error)
        assertTrue(model.canRetryUpload)
        cloud.fail = false
        model.retryUpload()
        await { !model.busy }
        assertNull(model.error)
        assertEquals(1, cloud.uploadIds.distinct().size)
        assertEquals(2, cloud.uploadIds.size)
        assertEquals(1, model.attachments.size)
        assertFalse(model.canRetryUpload)
    }

    @Test
    fun cancellationInvalidatesDelayedPickerCallbacks() {
        model.requestPicker()
        model.pickerLaunched()
        model.cancelOperation()
        model.receiveSelection(Uri.fromFile(source))
        assertNull(model.selectedUri)
        assertEquals(R.string.attachment_cancelled, model.message)
        assertEquals(0, cloud.uploadCalls)
    }
}
