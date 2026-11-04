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

@Composable
fun TaskProgressPanel(
    tasks: List<StudyTask>,
    subjects: List<Subject>
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val overall = TaskLogic.progress(tasks)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                context.getString(R.string.study_progress),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                context.resources.getQuantityString(
                    R.plurals.progress_overall, overall.total,
                    overall.completed, overall.total, overall.percentage
                )
            )

            LinearProgressIndicator(
                progress = { overall.fraction },
                modifier = Modifier.fillMaxWidth()
            )

            if (subjects.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) context.getString(R.string.hide_subject_progress)
                        else context.getString(R.string.show_subject_progress)
                    )
                }
            }

            if (expanded) {
                subjects.sortedBy { it.name.lowercase() }.forEach { subject ->
                    val progress = TaskLogic.progress(tasks, subject.subjectId)

                    Text(
                        subject.name,
                        style = MaterialTheme.typography.titleSmall
                    )

                    Text(
                        context.resources.getQuantityString(
                            R.plurals.progress_subject, progress.total,
                            progress.completed, progress.total, progress.percentage
                        )
                    )

                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun TaskFiltersDialog(
    subjects: List<Subject>,
    selectedSubjectId: String,
    filter: TaskFilter,
    sort: TaskSort,
    onSubjectChange: (String) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.filter_and_sort)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TaskChoice(
                    label = context.getString(R.string.subject),
                    selectedText = subjects.find {
                        it.subjectId == selectedSubjectId
                    }?.name ?: context.getString(R.string.all_subjects),
                    choices = listOf("" to context.getString(R.string.all_subjects)) +
                            subjects.map { it.subjectId to it.name },
                    enabled = true,
                    onSelect = onSubjectChange
                )

                TaskChoice(
                    label = context.getString(R.string.status),
                    selectedText = context.getString(filter.label),
                    choices = TaskFilter.entries.map { it.name to context.getString(it.label) },
                    enabled = true,
                    onSelect = {
                        onFilterChange(TaskFilter.valueOf(it))
                    }
                )

                TaskChoice(
                    label = context.getString(R.string.sort_order),
                    selectedText = context.getString(sort.label),
                    choices = TaskSort.entries.map { it.name to context.getString(it.label) },
                    enabled = true,
                    onSelect = {
                        onSortChange(TaskSort.valueOf(it))
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text(context.getString(R.string.reset))
            }
        }
    )
}
