package com.studysync.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium
        )
        if (accountEmail.isNotBlank()) {
            Text("Signed in as $accountEmail")
        }

        BiometricSettings(
            userId = userId,
            biometricLock = biometricLock
        )

        HorizontalDivider()

        Text("Choose how you organise your study tasks.")

        TaskChoice(
            label = "Default task priority",
            selectedText = settings.defaultPriority.lowercase()
                .replaceFirstChar { it.uppercase() },
            choices = listOf(
                "HIGH" to "High",
                "MEDIUM" to "Medium",
                "LOW" to "Low"
            ),
            enabled = !saving,
            onSelect = { priority ->
                onSettingsChange(
                    settings.copy(defaultPriority = priority)
                )
            }
        )

        Text(
            "Used when adding a new task. Existing tasks keep their priority.",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        TaskChoice(
            label = "Preferred task sorting",
            selectedText = settings.defaultSort.label,
            choices = TaskSort.entries.map { it.name to it.label },
            enabled = !saving,
            onSelect = { sort ->
                onSettingsChange(
                    settings.copy(defaultSort = TaskSort.valueOf(sort))
                )
            }
        )

        Text(
            "Used when opening the Tasks tab. You can temporarily change " +
                    "the order using Filter and sort.",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text("Changes save automatically on this device.")

        if (saving) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
            Text("Saving settings...")
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        message?.let {
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        OutlinedButton(
            enabled = !saving && settings != AppSettings(),
            onClick = {
                onSettingsChange(AppSettings())
            }
        ) {
            Text("Restore defaults")
        }
    }
}