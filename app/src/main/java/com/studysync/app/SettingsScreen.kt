package com.studysync.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    settings: AppSettings,
    saving: Boolean,
    error: String?,
    message: String?,
    userId: String,
    biometricLock: BiometricLock,
    onSettingsChange: (AppSettings) -> Unit,
    accountEmail: String = ""
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            context.getString(R.string.settings),
            style = MaterialTheme.typography.headlineMedium
        )
        if (accountEmail.isNotBlank()) {
            Text(context.getString(R.string.signed_in_as, accountEmail))
        }

        TaskChoice(
            label = context.getString(R.string.language),
            selectedText = context.getString(
                if (settings.language == "zu") R.string.isizulu else R.string.english
            ),
            choices = listOf(
                "en" to context.getString(R.string.english),
                "zu" to context.getString(R.string.isizulu)
            ),
            enabled = !saving,
            onSelect = { language ->
                onSettingsChange(settings.copy(language = language))
            }
        )

        Text(
            context.getString(R.string.language_hint),
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        BiometricSettings(
            userId = userId,
            biometricLock = biometricLock
        )

        HorizontalDivider()

        NotificationSettings(userId = userId)

        HorizontalDivider()

        Text(context.getString(R.string.settings_description))

        TaskChoice(
            label = context.getString(R.string.default_priority),
            selectedText = priorityText(context, settings.defaultPriority),
            choices = listOf(
                "HIGH" to context.getString(R.string.high),
                "MEDIUM" to context.getString(R.string.medium),
                "LOW" to context.getString(R.string.low)
            ),
            enabled = !saving,
            onSelect = { priority ->
                onSettingsChange(
                    settings.copy(defaultPriority = priority)
                )
            }
        )

        Text(
            context.getString(R.string.default_priority_hint),
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        TaskChoice(
            label = context.getString(R.string.default_sort),
            selectedText = context.getString(settings.defaultSort.label),
            choices = TaskSort.entries.map { it.name to context.getString(it.label) },
            enabled = !saving,
            onSelect = { sort ->
                onSettingsChange(
                    settings.copy(defaultSort = TaskSort.valueOf(sort))
                )
            }
        )

        Text(
            context.getString(R.string.default_sort_hint),
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text(context.getString(R.string.auto_save_hint))

        if (saving) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
            Text(context.getString(R.string.saving_settings))
        }

        error?.let {
            Text(messageText(context, it), color = MaterialTheme.colorScheme.error)
        }

        message?.let {
            Text(messageText(context, it), color = MaterialTheme.colorScheme.primary)
        }

        OutlinedButton(
            enabled = !saving && settings != settings.restoreTaskDefaults(),
            onClick = {
                onSettingsChange(settings.restoreTaskDefaults())
            }
        ) {
            Text(context.getString(R.string.restore_defaults))
        }
    }
}
