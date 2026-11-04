package com.studysync.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class FocusResourcesTest {
    @Test
    fun focusScreenLabelsAndHistoryUseTheSelectedLanguage() {
        val context = RuntimeEnvironment.getApplication()
        val english = AppLanguage.context(context, "en")
        val zulu = AppLanguage.context(context, "zu")
        assertEquals("Focus sessions", english.getString(R.string.focus_sessions))
        assertEquals("Izikhathi zokugxila", zulu.getString(R.string.focus_sessions))
        assertEquals("25 minutes", focusDurationText(english, DEFAULT_FOCUS_DURATION))
        assertEquals("Imizuzu eyi-25", focusDurationText(zulu, DEFAULT_FOCUS_DURATION))
        assertEquals("Umzuzu oyi-1", focusDurationText(zulu, 60_000))
        for (state in FocusState.entries) {
            val label = focusStateLabel(state)
            assertNotEquals(english.getString(label), zulu.getString(label))
        }
        val completedAt = Instant.parse("2026-11-04T12:30:00Z").toEpochMilli()
        for (localized in listOf(english, zulu)) {
            val locale = localized.resources.configuration.locales[0]
            val date = focusDateText(completedAt, locale, ZoneId.of("Africa/Johannesburg"))
            val label = localized.getString(R.string.focus_completed_date, date)
            assertTrue(label.contains("2026"))
            assertFalse(label.contains("%1"))
        }
        assertNotEquals(
            focusDateText(completedAt, Locale.ENGLISH),
            focusDateText(completedAt, Locale.forLanguageTag("zu"))
        )
    }
}
