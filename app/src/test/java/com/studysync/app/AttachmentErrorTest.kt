package com.studysync.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AttachmentErrorTest {
    @Test
    fun providerMessagesStayLocalised() {
        for (message in listOf(R.string.attachment_sign_in, R.string.attachment_sync_first,
            R.string.attachment_api_conflict, R.string.attachment_api_unavailable)) {
            assertEquals(message, attachmentError(AttachmentFailure(message), R.string.attachment_upload_error))
        }
    }

    @Test
    fun nonProviderFailuresKeepTheirMeaning() {
        assertEquals(R.string.attachment_network_error,
            attachmentError(IOException("Offline"), R.string.attachment_upload_error))
        assertEquals(R.string.attachment_download_error,
            attachmentError(IllegalStateException("Synthetic failure"), R.string.attachment_download_error))
    }
}
