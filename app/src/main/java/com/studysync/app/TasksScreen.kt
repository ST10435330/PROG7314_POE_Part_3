package com.studysync.app

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
fun TasksScreen(
    repository: StudyRepository,
    settings: AppSettings,
    dataVersion: Long = 0L,
    attachments: AttachmentsViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var subjects by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<StudyTask>>(emptyList()) }

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable {
        mutableStateOf(TaskFilter.ALL)
    }
    var sort by rememberSaveable(settings.defaultSort) {
        mutableStateOf(settings.defaultSort)
    }
    var selectedSubjectId by rememberSaveable {
        mutableStateOf("")
    }
    var showFilters by rememberSaveable {
        mutableStateOf(false)
    }
    var completionError by remember {
        mutableStateOf<Pair<String, String>?>(null)
    }
    var today by remember {
        mutableStateOf(LocalDate.now())
    }

    // Refresh date-dependent labels if the screen stays open overnight.
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            delay(60_000L)
        }
    }
    LaunchedEffect(repository, reloadKey, dataVersion) {
        // Keep open forms mounted during cache updates.
        loadError = null

        try {
            val loadedSubjects = repository.getSubjects()
            val loadedTasks = repository.getTasks()

            subjects = loadedSubjects
            tasks = loadedTasks
            if (selectedSubjectId.isNotBlank() &&
                loadedSubjects.none { it.subjectId == selectedSubjectId }
            ) {
                selectedSubjectId = ""
            }

            // A previously selected task may have been removed.
            if (editingId != null &&
                loadedTasks.none { it.taskId == editingId }
            ) {
                editingId = null
                showForm = false
            }

            if (pendingDeleteId != null &&
                loadedTasks.none { it.taskId == pendingDeleteId }
            ) {
                pendingDeleteId = null
            }

            Log.d("StudySync", "Tasks and subjects loaded")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            loadError = apiErrorMessage(
                exception,
                AppMessage.TASKS_LOAD_ERROR.name
            )
            Log.e("StudySync", "Task loading failed", exception)
        } finally {
            loading = false
        }
    }
    val visibleTasks = TaskLogic.select(
        tasks = tasks,
        filter = filter,
        sort = sort,
        subjectId = selectedSubjectId,
        today = today
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(context.getString(R.string.my_tasks), style = MaterialTheme.typography.headlineMedium)
        }

        when {
            loading -> {
                item { CircularProgressIndicator() }
            }

            loadError != null -> {
                item {
                    Text(
                        messageText(context, loadError.orEmpty()),
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { reloadKey++ }) {
                        Text(context.getString(R.string.retry))
                    }
                }
            }

            else -> {
                    item {
                        TaskProgressPanel(
                            tasks = tasks,
                            subjects = subjects
                        )
                    }

                    item {
                        TextButton(
                            enabled = !saving,
                            onClick = { showFilters = true }
                        ) {
                            Text(context.getString(R.string.filter_and_sort))
                        }
                        TextButton(
                            enabled = !saving,
                            onClick = {
                                completionError = null
                                reloadKey++
                            }
                        ) {
                            Text(context.getString(R.string.refresh))
                        }

                        Text(
                            context.resources.getQuantityString(
                                R.plurals.tasks_shown, tasks.size,
                                visibleTasks.size, tasks.size, context.getString(filter.label)
                            )
                        )
                    }

                    if (saving && !showForm && pendingDeleteId == null) {
                        item {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        Button(
                            enabled = subjects.isNotEmpty() && !saving,
                            onClick = {
                                editingId = null
                                saveError = null
                                showForm = true
                            }
                        ) {
                            Text(context.getString(R.string.add_task))
                        }
                    }

                if (subjects.isEmpty()) {
                    item {
                        Text(context.getString(R.string.subject_required_first))
                    }
                } else if (tasks.isEmpty()) {
                    item {
                        Text(context.getString(R.string.no_tasks))
                    }
                } else if (visibleTasks.isEmpty()) {
                    item {
                        Text(context.getString(R.string.no_filtered_tasks))
                    }
                }

                items(visibleTasks, key = { it.taskId }) { task ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(
                                subjects.find {
                                    it.subjectId == task.subjectId
                                }?.name ?: context.getString(R.string.subject_unavailable)
                            )

                            if (task.description.isNotBlank()) {
                                Text(task.description)
                            }

                            Text(context.getString(R.string.task_due, task.dueDate))

                            Text(
                                context.getString(R.string.task_priority, priorityText(context, task.priority))
                            )

                            Text(
                                context.getString(
                                    if (task.completed) R.string.completed else R.string.pending
                                )
                            )

                            if (TaskLogic.isOverdue(task, today)) {
                                Text(
                                    context.getString(R.string.overdue),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            TextButton(
                                enabled = !saving,
                                onClick = {
                                    if (!saving) {
                                        saving = true
                                        completionError = null

                                        scope.launch {
                                            try {
                                                val updated = repository.updateTask(
                                                    task.copy(
                                                        completed = !task.completed
                                                    )
                                                )

                                                tasks = tasks.map {
                                                    if (it.taskId == updated.taskId) {
                                                        updated
                                                    } else {
                                                        it
                                                    }
                                                }

                                                Log.d(
                                                    "StudySync",
                                                    if (updated.completed) {
                                                        "Task completed"
                                                    } else {
                                                        "Task reopened"
                                                    }
                                                )
                                            } catch (exception: CancellationException) {
                                                throw exception
                                            } catch (exception: Exception) {
                                                completionError = task.taskId to apiErrorMessage(
                                                    exception,
                                                    AppMessage.TASK_UPDATE_ERROR.name
                                                )

                                                Log.e(
                                                    "StudySync",
                                                    "Task completion update failed",
                                                    exception
                                                )
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(
                                    if (task.completed) context.getString(R.string.reopen_task)
                                    else context.getString(R.string.mark_complete)
                                )
                            }

                            completionError?.let { (failedId, message) ->
                                if (failedId == task.taskId) {
                                    Text(
                                        messageText(context, message),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            TextButton(
                                enabled = !saving,
                                onClick = { attachments.openTask(task.taskId) }
                            ) { Text(context.getString(R.string.attachment_button)) }

                            Row {
                                TextButton(
                                    enabled = !saving,
                                    onClick = {
                                        editingId = task.taskId
                                        saveError = null
                                        showForm = true
                                    }
                                ) {
                                    Text(context.getString(R.string.edit))
                                }

                                TextButton(
                                    enabled = !saving,
                                    onClick = {
                                        deleteError = null
                                        pendingDeleteId = task.taskId
                                    }
                                ) {
                                    Text(
                                        context.getString(R.string.delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val attachmentTask = tasks.find { it.taskId == attachments.taskId }
    LaunchedEffect(loading, attachmentTask, attachments.taskId) {
        if (!loading && loadError == null && attachments.taskId != null && attachmentTask == null) {
            attachments.closeTask()
        }
    }
    if (attachmentTask != null) TaskAttachmentsDialog(attachmentTask, attachments)

    if (showForm && !loading && loadError == null) {
        TaskForm(
            subjects = subjects,
            saving = saving,
            saveError = saveError,
            initialTask = tasks.find { it.taskId == editingId },
            defaultPriority = settings.defaultPriority,
            onDismiss = {
                showForm = false
                editingId = null
                saveError = null
            },
            onSave = { draft ->
                if (!saving) {
                    saving = true
                    saveError = null
                    val creating = editingId == null

                    scope.launch {
                        try {
                            val saved = if (creating) {
                                repository.createTask(draft)
                            } else {
                                repository.updateTask(draft)
                            }

                            // The cache notification may already have loaded this task.
                            tasks = tasks.filterNot {
                                it.taskId == saved.taskId
                            } + saved

                            showForm = false
                            editingId = null

                            Log.d(
                                "StudySync",
                                if (creating) "Task created" else "Task edited"
                            )
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            saveError = apiErrorMessage(
                                exception,
                                AppMessage.TASK_SAVE_ERROR.name
                            )

                            Log.e("StudySync", "Task save failed", exception)
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        )
    }

    if (showFilters && !loading && loadError == null) {
        TaskFiltersDialog(
            subjects = subjects,
            selectedSubjectId = selectedSubjectId,
            filter = filter,
            sort = sort,
            onSubjectChange = { selectedSubjectId = it },
            onFilterChange = { filter = it },
            onSortChange = { sort = it },
            onReset = {
                selectedSubjectId = ""
                filter = TaskFilter.ALL
                sort = settings.defaultSort
            },
            onDismiss = { showFilters = false }
        )
    }

    val taskToDelete = tasks.find { it.taskId == pendingDeleteId }

    if (taskToDelete != null && !loading && loadError == null) {
        AlertDialog(
            onDismissRequest = {
                if (!saving) {
                    pendingDeleteId = null
                    deleteError = null
                }
            },
            title = { Text(context.getString(R.string.delete_task_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        context.getString(R.string.delete_task_message, taskToDelete.title)
                    )

                    Text(context.getString(R.string.attachment_task_delete_hint))

                    deleteError?.let {
                        Text(messageText(context, it), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        if (!saving) {
                            saving = true
                            deleteError = null

                            scope.launch {
                                try {
                                    repository.deleteTask(taskToDelete.taskId)

                                    tasks = tasks.filterNot {
                                        it.taskId == taskToDelete.taskId
                                    }

                                    pendingDeleteId = null
                                    Log.d("StudySync", "Task deleted")
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    deleteError = apiErrorMessage(
                                        exception,
                                        AppMessage.TASK_DELETE_ERROR.name
                                    )

                                    Log.e(
                                        "StudySync",
                                        "Task deletion failed",
                                        exception
                                    )
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        if (saving) context.getString(R.string.deleting) else context.getString(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        pendingDeleteId = null
                        deleteError = null
                    }
                ) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
}
