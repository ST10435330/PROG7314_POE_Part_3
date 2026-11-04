package com.studysync.app

import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AttachmentFilesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun registerProvider() {
        val info = ProviderInfo().apply {
            authority = "${context.packageName}.attachments"
            name = FileProvider::class.java.name
            packageName = context.packageName
            exported = false
            grantUriPermissions = true
            metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.attachment_paths) }
        }
        shadowOf(context.packageManager).addOrUpdateProvider(info)
    }

    @Test
    fun externalOpeningUsesAnUnexportedProviderAndReadOnlyTemporaryGrant() {
        registerProvider()
        val record = TaskAttachment(AttachmentValidator.newId(), "task-1", "Notes.pdf", "application/pdf", 8)
        val files = AttachmentFiles(context, "alice")
        val file = files.file(record).apply { writeText("%PDF-1.7") }
        files.open(file, record.contentType)
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.attachments", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val unrelated = File(context.cacheDir, "unrelated.txt").apply { writeText("private") }
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.attachments", unrelated)
            fail("The provider must not expose the whole cache")
        } catch (_: IllegalArgumentException) { }
        files.clear()
        unrelated.delete()
    }

    @Test
    fun accountAndTaskCacheCleanupDoesNotRemoveAnotherAccountsFiles() {
        val record = TaskAttachment(AttachmentValidator.newId(), "task-1", "Notes.pdf", "application/pdf", 8)
        val alice = AttachmentFiles(context, "alice")
        val bob = AttachmentFiles(context, "bob")
        val first = alice.file(record).apply { writeText("%PDF-1.7") }
        val second = bob.file(record).apply { writeText("%PDF-1.7") }
        assertNotEquals(first, second)
        alice.clearTask(record.taskId)
        assertFalse(first.exists())
        assertTrue(second.exists())
        AttachmentFiles.clearOtherAccounts(context, "alice") { true }
        assertFalse(second.exists())
        alice.clear()
    }

    @Test
    fun newAttachmentTextAndErrorsResolveInEnglishAndIsiZulu() {
        val english = AppLanguage.context(context, "en")
        val zulu = AppLanguage.context(context, "zu")
        for (resource in listOf(R.string.attachment_button, R.string.attachment_add,
            R.string.attachment_open, R.string.attachment_save_copy, R.string.attachment_denied,
            R.string.attachment_too_large, R.string.attachment_cleanup_error)) {
            assertNotEquals(english.getString(resource), zulu.getString(resource))
        }
        assertEquals("Transferred: 50%", english.getString(R.string.attachment_progress, 50))
        assertEquals("Okudlulisiwe: 50%", zulu.getString(R.string.attachment_progress, 50))
    }
}
