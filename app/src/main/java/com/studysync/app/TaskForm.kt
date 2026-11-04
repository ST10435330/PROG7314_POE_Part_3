package com.studysync.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun TaskForm(
    subjects: List<Subject>,
    saving: Boolean,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (StudyTask) -> Unit,
    initialTask: StudyTask? = null,
    defaultPriority: String = "MEDIUM"
) {
    val context = LocalContext.current
    var title by rememberSaveable(initialTask?.taskId) {
        mutableStateOf(initialTask?.title ?: "")
    }
    var description by rememberSaveable(initialTask?.taskId) {
        mutableStateOf(initialTask?.description ?: "")
    }
    var subjectId by rememberSaveable(initialTask?.taskId) {
        mutableStateOf(initialTask?.subjectId ?: "")
    }
    var dueDate by rememberSaveable(initialTask?.taskId) {
        mutableStateOf(initialTask?.dueDate ?: LocalDate.now().toString())
    }
    var priority by rememberSaveable(initialTask?.taskId) {
        mutableStateOf(initialTask?.priority ?: defaultPriority)
    }
    var validationError by rememberSaveable(initialTask?.taskId) {
        mutableStateOf<String?>(null)
    }

    AlertDialog(
        onDismissRequest = {
            if (!saving) onDismiss()
        },
        title = {
            Text(if (initialTask == null) context.getString(R.string.add_task) else context.getString(R.string.edit_task))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        validationError = null
                    },
                    label = { Text(context.getString(R.string.task_title)) },
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                TaskChoice(
                    label = context.getString(R.string.subject),
                    selectedText = subjects.find {
                        it.subjectId == subjectId
                    }?.name ?: context.getString(R.string.choose_subject),
                    choices = subjects.map { it.subjectId to it.name },
                    enabled = !saving,
                    onSelect = {
                        subjectId = it
                        validationError = null
                    }
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = {
                        description = it
                        validationError = null
                    },
                    label = { Text(context.getString(R.string.description_optional)) },
                    enabled = !saving,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = dueDate,
                    onValueChange = {
                        dueDate = it
                        validationError = null
                    },
                    label = { Text(context.getString(R.string.due_date)) },
                    supportingText = { Text(context.getString(R.string.date_format_hint)) },
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                TaskChoice(
                    label = context.getString(R.string.priority),
                    selectedText = priorityText(context, priority),
                    choices = listOf(
                        "HIGH" to context.getString(R.string.high),
                        "MEDIUM" to context.getString(R.string.medium),
                        "LOW" to context.getString(R.string.low)
                    ),
                    enabled = !saving,
                    onSelect = { priority = it }
                )

                (validationError ?: saveError)?.let {
                    Text(messageText(context, it), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val task = (initialTask ?: StudyTask()).copy(
                        subjectId = subjectId,
                        title = title.trim(),
                        description = description.trim(),
                        dueDate = dueDate.trim(),
                        priority = priority
                    )

                    validationError = TaskValidator.validate(task)

                    if (validationError == null) {
                        onSave(task)
                    }
                }
            ) {
                Text(if (saving) context.getString(R.string.saving) else context.getString(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !saving,
                onClick = onDismiss
            ) {
                Text(context.getString(R.string.cancel))
            }
        }
    )
}

@Composable
internal fun TaskChoice(
    label: String,
    selectedText: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedText)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                choices.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
