package com.studysync.app

import android.os.Bundle
import android.os.Build
import android.os.LocaleList
import android.content.res.Configuration
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AppLanguageTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences("studysync_settings", 0).edit().clear().commit()
    }

    @After
    fun resetLocales() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun backgroundResourcesUseSavedLanguageAfterReopening() = runBlocking {
        SettingsStorage(context).save(AppSettings(language = "zu"))
        val reopened = AppLanguage.context(context)
        assertEquals("zu", reopened.resources.configuration.locales[0].language)
        assertEquals("Izilungiselelo", reopened.getString(R.string.settings))
        assertEquals("Izaziso zixhunyiwe.", reopened.getString(R.string.notification_test_body))

        SettingsStorage(context).save(AppSettings(language = "en"))
        assertEquals("Settings", AppLanguage.context(context).getString(R.string.settings))
    }

    @Test
    fun activityResourcesSwitchBothWaysUsingAppCompat() {
        val controller = Robolectric.buildActivity(LanguageTestActivity::class.java).setup()
        try {
            for ((language, label) in listOf("zu" to "Izilungiselelo", "en" to "Settings")) {
                AppLanguage.apply(language)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(language, AppCompatDelegate.getApplicationLocales().toLanguageTags())
                if (Build.VERSION.SDK_INT >= 33) {
                    // Robolectric records the locale but Android delivers this change on a device.
                    val configuration = Configuration(controller.get().resources.configuration)
                    configuration.setLocales(
                        LocaleList.forLanguageTags(
                            AppCompatDelegate.getApplicationLocales().toLanguageTags()
                        )
                    )
                    controller.configurationChange(configuration)
                } else {
                    controller.recreate()
                }
                assertEquals(label, controller.get().getString(R.string.settings))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun savedAndLegacyStatusMessagesFollowTheCurrentLanguage() {
        val english = AppLanguage.context(context, "en")
        val zulu = AppLanguage.context(context, "zu")
        assertEquals("Last sync completed.", messageText(english, AppMessage.SYNC_COMPLETED.name))
        assertEquals("Ukuvumelanisa kokugcina kuqediwe.", messageText(zulu, AppMessage.SYNC_COMPLETED.name))
        assertEquals("Ukuvumelanisa kokugcina kuqediwe.", messageText(zulu, "Last sync completed."))
        assertEquals(zulu.getString(R.string.operation_failed), messageText(zulu, "Unknown server error"))
        assertEquals("", messageText(zulu, ""))
    }

    @Test
    fun rejectedMessagesKeepTheStudentsTitleAndTranslateTheReason() {
        val zulu = AppLanguage.context(context, "zu")
        assertEquals(
            "Maths: chapter 1: Leso sifundo sesikhona.",
            rejectionText(zulu, "Maths: chapter 1: ${AppMessage.SUBJECT_DUPLICATE.name}")
        )
        assertEquals(
            "Into esusiwe: Leso sifundo sesikhona.",
            rejectionText(zulu, "Deleted item: That subject already exists.")
        )
    }

    @Test
    fun validationLabelsAndFormattedResourcesUseIsiZulu() {
        val zulu = AppLanguage.context(context, "zu")
        val error = TaskValidator.validate(StudyTask())!!
        assertEquals("Faka isihloko somsebenzi.", messageText(zulu, error))
        assertEquals("Eliphezulu", priorityText(zulu, "HIGH"))
        assertEquals("Ayikaqedwa", zulu.getString(TaskFilter.PENDING.label))
        assertEquals(
            "Kuqedwe imisebenzi eyi-2 kweyi-4 · 50%",
            zulu.resources.getQuantityString(R.plurals.progress_overall, 4, 2, 4, 50)
        )
    }

    @Test
    fun taskCountsUseSingularAndPluralResources() {
        val english = AppLanguage.context(context, "en").resources
        val zulu = AppLanguage.context(context, "zu").resources
        assertEquals("1 device change waiting to sync", english.getQuantityString(R.plurals.sync_pending, 1, 1))
        assertEquals("2 device changes waiting to sync", english.getQuantityString(R.plurals.sync_pending, 2, 2))
        assertEquals("Ushintsho oluyi-1 ludinga ukunakwa.", zulu.getQuantityString(R.plurals.sync_rejected, 1, 1))
        assertEquals("Izinguquko eziyi-2 zidinga ukunakwa.", zulu.getQuantityString(R.plurals.sync_rejected, 2, 2))
        val localized = AppLanguage.context(context, "zu")
        assertEquals("Kuxhunywa izaziso…", messageText(localized, "Connecting notifications..."))
    }

    @Test
    fun allMessageKeysResolveToLocalizedText() {
        for (language in AppLanguage.codes) {
            val localized = AppLanguage.context(context, language)
            for (message in AppMessage.entries) {
                assertEquals(
                    localized.getString(message.resourceId),
                    messageText(localized, message.name)
                )
            }
        }
    }

    @Test
    fun unsupportedLocaleUsesEnglishResources() {
        assertEquals("Settings", AppLanguage.context(context, "fr").getString(R.string.settings))
    }
}

class LanguageTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_StudySync)
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.settings) })
    }
}
