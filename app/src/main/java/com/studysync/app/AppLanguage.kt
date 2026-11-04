package com.studysync.app

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguage {
    val codes = listOf("en", "zu")

    fun saved(context: Context): String {
        val value = context.applicationContext
            .getSharedPreferences("studysync_settings", Context.MODE_PRIVATE)
            .getString("language", "en")
        return value?.takeIf { it in codes } ?: "en"
    }

    fun apply(language: String) {
        val code = language.takeIf { it in codes } ?: "en"
        val locales = LocaleListCompat.forLanguageTags(code)
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    // Background notifications also need the saved language on older Android versions.
    fun context(context: Context, language: String = saved(context)): Context {
        val code = language.takeIf { it in codes } ?: "en"
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(code)))
        return context.createConfigurationContext(configuration)
    }
}

fun priorityText(context: Context, priority: String): String {
    val resource = when (priority) {
        "HIGH" -> R.string.high
        "LOW" -> R.string.low
        else -> R.string.medium
    }
    return context.getString(resource)
}
