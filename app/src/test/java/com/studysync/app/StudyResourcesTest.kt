package com.studysync.app

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StudyResourcesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun guidesAreAvailableInBothLanguages() {
        val english = AppLanguage.context(context, "en")
        val zulu = AppLanguage.context(context, "zu")
        for (guide in studyGuides) {
            for (id in listOf(guide.title, guide.summary, guide.steps)) {
                assertTrue(english.getString(id).isNotBlank())
                assertTrue(zulu.getString(id).isNotBlank())
                assertNotEquals(english.getString(id), zulu.getString(id))
            }
            assertTrue(guide.citation.isNotBlank())
            assertTrue(guide.url.startsWith("https://doi.org/"))
        }
    }

    @Test
    fun referencesLaunchAsPublicWebLinks() {
        val url = studyGuides.first().url
        assertTrue(openStudyLink(context, url))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(intent.extras)
    }

    @Test
    fun unsafeLinksDoNotStartAnActivity() {
        for (url in listOf("file:///private.txt", "content://private/file", "javascript:alert(1)",
            "http://doi.org/example", "https://doi.org.evil.invalid/example", "https://user@doi.org/example")) {
            assertFalse(url, openStudyLink(context, url))
        }
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun unavailableHandlersDoNotCrash() {
        val missing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("Synthetic test") }
        }
        val blocked = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw SecurityException("Synthetic test") }
        }
        assertFalse(openStudyLink(missing, STUDY_VIDEO_URL))
        assertFalse(openStudyLink(blocked, STUDY_VIDEO_URL))
    }
}
