package com.studysync.app

import com.google.firebase.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AttachmentErrorTest {
    // Construct SDK failures locally: no Firebase project, account or network is used.
    private fun response(code: Int) = StorageException.fromExceptionAndHttpCode(
        IOException("Synthetic Storage response"), code
    )!!

    @Test
    fun paymentRequiredIsActionable() {
        assertEquals(R.string.attachment_quota_error,
            attachmentError(response(402), R.string.attachment_upload_error))
    }

    @Test
    fun deniedIsNotAssumedToBeBilling() {
        assertEquals(R.string.attachment_denied,
            attachmentError(response(403), R.string.attachment_list_error))
    }

    @Test
    fun signedOutAndMissingAreDistinct() {
        assertEquals(R.string.attachment_sign_in,
            attachmentError(response(401), R.string.attachment_download_error))
        assertEquals(R.string.attachment_missing,
            attachmentError(response(404), R.string.attachment_download_error))
    }

    @Test
    fun otherFailuresKeepTheirMeaning() {
        assertEquals(R.string.attachment_network_error,
            attachmentError(IOException("Offline"), R.string.attachment_upload_error))
        assertEquals(R.string.attachment_sync_first,
            attachmentError(AttachmentFailure(R.string.attachment_sync_first), R.string.attachment_upload_error))
        assertEquals(R.string.attachment_upload_error,
            attachmentError(response(500), R.string.attachment_upload_error))
    }
}
