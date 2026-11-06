package com.studysync.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Presentation only: cloud and local task dates remain in ISO format. */
internal fun taskDateText(value: String?, locale: Locale): String = try {
    LocalDate.parse(value.orEmpty()).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
} catch (_: DateTimeParseException) { value.orEmpty() }

@Composable
private fun TaskPill(text: String, background: Color, foreground: Color) {
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskMetadata(task: StudyTask, today: LocalDate) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val date = taskDateText(task.dueDate, LocalConfiguration.current.locales[0])
    Text(if (date.isBlank()) context.getString(R.string.ui_no_due_date) else context.getString(R.string.task_due, date),
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val priorityColors = when (task.priority) {
            "HIGH" -> colors.errorContainer to colors.onErrorContainer
            "LOW" -> colors.secondaryContainer to colors.onSecondaryContainer
            else -> colors.tertiaryContainer to colors.onTertiaryContainer
        }
        TaskPill(context.getString(R.string.task_priority, priorityText(context, task.priority)),
            priorityColors.first, priorityColors.second)
        TaskPill(context.getString(if (task.completed) R.string.completed else R.string.pending),
            if (task.completed) colors.secondaryContainer else colors.surfaceVariant,
            if (task.completed) colors.onSecondaryContainer else colors.onSurfaceVariant)
        if (TaskLogic.isOverdue(task, today)) {
            TaskPill(context.getString(R.string.overdue), colors.errorContainer, colors.onErrorContainer)
        }
    }
}

@Composable
internal fun TaskActionRow(
    task: StudyTask,
    enabled: Boolean,
    onAttachments: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember(task.taskId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onToggle, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(context.getString(if (task.completed) R.string.reopen_task else R.string.mark_complete))
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_task_more),
                    contentDescription = context.getString(R.string.ui_task_actions, task.title))
            }
            DropdownMenu(expanded = menuOpen && enabled, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(context.getString(R.string.attachment_button)) },
                    onClick = { menuOpen = false; onAttachments() }, enabled = enabled)
                DropdownMenuItem(text = { Text(context.getString(R.string.edit)) },
                    onClick = { menuOpen = false; onEdit() }, enabled = enabled)
                DropdownMenuItem(text = { Text(context.getString(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() }, enabled = enabled)
            }
        }
    }
}
