package com.studysync.app

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("studysync_settings", 0)

    @Before
    fun clearSettings() {
        preferences.edit().clear().commit()
    }

    @Test
    fun newInstallDefaultsToEnglish() = runBlocking {
        assertEquals(AppSettings(), SettingsStorage(context).load())
        assertEquals("en", AppLanguage.saved(context))
    }

    @Test
    fun bothLanguagesPersistWithTaskPreferences() = runBlocking {
        for (language in listOf("zu", "en")) {
            val settings = AppSettings("HIGH", TaskSort.TITLE, language)
            SettingsStorage(context).save(settings)

            // A new storage instance represents reopening the settings after launch.
            assertEquals(settings, SettingsStorage(context).load())
            assertEquals(language, AppLanguage.saved(context))
            assertEquals(language, preferences.getString("language", null))
            val savedFile = File(context.applicationInfo.dataDir, "shared_prefs/studysync_settings.xml")
            assertTrue(savedFile.readText().contains("<string name=\"language\">$language</string>"))
        }
    }

    @Test
    fun existingSettingsWithoutLanguageKeepTheirTaskPreferences() = runBlocking {
        preferences.edit()
            .putString("defaultPriority", "LOW")
            .putString("defaultSort", TaskSort.PRIORITY.name)
            .commit()

        assertEquals(
            AppSettings("LOW", TaskSort.PRIORITY, "en"),
            SettingsStorage(context).load()
        )
    }

    @Test
    fun unsupportedStoredLanguageFallsBackToEnglish() = runBlocking {
        preferences.edit().putString("language", "unsupported").commit()
        assertEquals("en", SettingsStorage(context).load().language)
        assertEquals("en", AppLanguage.saved(context))
    }

    @Test
    fun invalidLanguageCannotOverwriteSavedPreferences() = runBlocking {
        val original = AppSettings("HIGH", TaskSort.TITLE, "zu")
        SettingsStorage(context).save(original)
        try {
            SettingsStorage(context).save(AppSettings(language = "fr"))
            fail("An unsupported language should be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(original, SettingsStorage(context).load())
        }
    }

    @Test
    fun restoringTaskDefaultsKeepsTheSavedLanguage() = runBlocking {
        val settings = AppSettings("HIGH", TaskSort.TITLE, "zu")
        SettingsStorage(context).save(settings.restoreTaskDefaults())
        assertEquals(AppSettings(language = "zu"), SettingsStorage(context).load())
    }
}
