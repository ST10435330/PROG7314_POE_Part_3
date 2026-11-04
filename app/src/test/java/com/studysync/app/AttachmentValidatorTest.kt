package com.studysync.app

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class AttachmentValidatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun approvedSignaturesAreRecognized() {
        assertEquals("application/pdf", AttachmentValidator.type("%PDF-1.7".toByteArray()))
        assertEquals("image/jpeg", AttachmentValidator.type(byteArrayOf(-1, -40, -1)))
        assertEquals("image/png", AttachmentValidator.type(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("image/webp", AttachmentValidator.type("RIFF1234WEBP".toByteArray()))
        assertNull(AttachmentValidator.type("<svg>image</svg>".toByteArray()))
    }

    @Test
    fun actualBytesAreCheckedEvenWhenMimeTypeClaimsPdf() {
        val file = temporary.newFile()
        try {
            AttachmentValidator.copy(ByteArrayInputStream("<html>unsafe</html>".toByteArray()), file, "application/pdf")
            fail("Expected an invalid file")
        } catch (error: AttachmentFailure) {
            assertEquals(R.string.attachment_bad_type, error.messageId)
            assertFalse(file.exists())
        }
    }

    @Test
    fun reportedMimeMustMatchTheSignature() {
        try {
            AttachmentValidator.copy(ByteArrayInputStream("%PDF-1.7".toByteArray()), temporary.newFile(), "image/png")
            fail("Expected a MIME mismatch")
        } catch (error: AttachmentFailure) { assertEquals(R.string.attachment_bad_type, error.messageId) }
    }

    @Test
    fun unknownMimeIsAcceptedOnlyWhenTheBytesIdentifyASupportedFile() {
        val file = temporary.newFile()
        val result = AttachmentValidator.copy(ByteArrayInputStream("%PDF-1.7".toByteArray()), file, null)
        assertEquals("application/pdf" to 8L, result)
    }

    @Test
    fun exactSizeLimitIsAllowedAndLargerStreamsAreRemoved() {
        val bytes = ByteArray(AttachmentValidator.MAX_SIZE.toInt() + 1)
        "%PDF-".toByteArray().copyInto(bytes)
        val accepted = temporary.newFile()
        assertEquals(AttachmentValidator.MAX_SIZE,
            AttachmentValidator.copy(ByteArrayInputStream(bytes, 0, bytes.size - 1), accepted, "application/pdf").second)
        val rejected = temporary.newFile()
        try {
            AttachmentValidator.copy(ByteArrayInputStream(bytes), rejected, "application/pdf")
            fail("Expected the byte limit to be enforced")
        } catch (error: AttachmentFailure) {
            assertEquals(R.string.attachment_too_large, error.messageId)
            assertFalse(rejected.exists())
        }
    }

    @Test
    fun emptyAndCancelledCopiesDoNotLeavePartialFiles() {
        val empty = temporary.newFile()
        try {
            AttachmentValidator.copy(ByteArrayInputStream(byteArrayOf()), empty, null)
            fail("Expected an empty-file error")
        } catch (error: AttachmentFailure) {
            assertEquals(R.string.attachment_empty, error.messageId)
            assertFalse(empty.exists())
        }
        val cancelled = temporary.newFile()
        try {
            AttachmentValidator.copy(ByteArrayInputStream("%PDF-1.7".toByteArray()), cancelled, null) {
                throw CancellationException()
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertFalse(cancelled.exists()) }
    }

    @Test
    fun storagePathsNeverUseTheDisplayNameOrAnArbitraryUrl() {
        val id = AttachmentValidator.newId()
        assertEquals("users/alice/tasks/task-1/attachments/$id", AttachmentValidator.path("alice", "task-1", id))
        for ((task, file) in listOf("../bob" to id, "task-1" to "notes.pdf", "task-1" to "gs://other/file")) {
            try { AttachmentValidator.path("alice", task, file); fail("Expected unsafe path rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("Notes.pdf", AttachmentValidator.name("/Notes.pdf\n\\"))
        assertEquals(150, AttachmentValidator.name("A".repeat(200)).length)
    }
}
