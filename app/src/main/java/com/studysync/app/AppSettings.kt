package com.studysync.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppSettings(
    val defaultPriority: String = "MEDIUM",
    val defaultSort: TaskSort = TaskSort.DUE_DATE,
    val language: String = "en"
) {
    fun restoreTaskDefaults() = copy(
        defaultPriority = "MEDIUM",
        defaultSort = TaskSort.DUE_DATE
    )
}

class SettingsStorage(private val context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("studysync_settings", Context.MODE_PRIVATE)

    suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val savedPriority = preferences.getString(
            "defaultPriority",
            "MEDIUM"
        )

        val priority = savedPriority
            ?.takeIf { it in listOf("HIGH", "MEDIUM", "LOW") }
            ?: "MEDIUM"

        val savedSort = preferences.getString(
            "defaultSort",
            TaskSort.DUE_DATE.name
        )

        val sort = TaskSort.entries.find { it.name == savedSort }
            ?: TaskSort.DUE_DATE

        AppSettings(
            defaultPriority = priority,
            defaultSort = sort,
            language = AppLanguage.saved(context)
        )
    }

    suspend fun save(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            require(settings.defaultPriority in listOf("HIGH", "MEDIUM", "LOW")) {
                AppMessage.INVALID_DEFAULT_PRIORITY.name
            }

            require(settings.language in AppLanguage.codes) {
                AppMessage.INVALID_LANGUAGE.name
            }

            val saved = preferences.edit()
                .putString("defaultPriority", settings.defaultPriority)
                .putString("defaultSort", settings.defaultSort.name)
                .putString("language", settings.language)
                .commit()

            check(saved) {
                AppMessage.SETTINGS_STORAGE_ERROR.name
            }
        }
    }
}
