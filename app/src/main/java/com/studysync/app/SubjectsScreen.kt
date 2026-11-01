package com.studysync.app

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SubjectsScreen(repository: StudyRepository, dataVersion: Long = 0L) {
    val scope = rememberCoroutineScope()

    var subjects by remember {
        mutableStateOf<List<Subject>>(emptyList())
    }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var showForm by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    var name by rememberSaveable { mutableStateOf("") }
    var lecturerName by rememberSaveable { mutableStateOf("") }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }

    var saving by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, reloadKey, dataVersion) {
        // Refresh the list without hiding an open form.
        loadError = null

        try {
            subjects = repository.getSubjects()

            if (editingId != null &&
                subjects.none { it.subjectId == editingId }
            ) {
                showForm = false
                editingId = null
            }

            Log.d("StudySync", "Subjects loaded from repository")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            loadError = apiErrorMessage(
                exception,
                "Could not load subjects. Please try again."
            )
            Log.e("StudySync", "Subject loading failed", exception)
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "My subjects",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        when {
            loading -> {
                item {
                    CircularProgressIndicator()
                    Text("Loading subjects...")
                }
            }

            loadError != null -> {
                item {
                    Text(
                        loadError ?: "",
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { reloadKey++ }) {
                        Text("Retry")
                    }
                }
            }

            else -> {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            enabled = !saving,
                            onClick = {
                                editingId = null
                                name = ""
                                lecturerName = ""
                                formError = null
                                showForm = true
                            }
                        ) {
                            Text("Add subject")
                        }

                        TextButton(
                            enabled = !saving,
                            onClick = { reloadKey++ }
                        ) {
                            Text("Refresh")
                        }
                    }
                }

                if (subjects.isEmpty()) {
                    item { Text("No subjects yet.") }
                }

                items(subjects, key = { it.subjectId }) { subject ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                subject.name,
                                style = MaterialTheme.typography.titleMedium
                            )

                            if (subject.lecturerName.isNotBlank()) {
                                Text(subject.lecturerName)
                            }

                            Row {
                                TextButton(
                                    enabled = !saving,
                                    onClick = {
                                        editingId = subject.subjectId
                                        name = subject.name
                                        lecturerName = subject.lecturerName
                                        formError = null
                                        showForm = true
                                    }
                                ) {
                                    Text("Edit")
                                }

                                TextButton(
                                    enabled = !saving,
                                    onClick = {
                                        deleteError = null
                                        pendingDeleteId = subject.subjectId
                                    }
                                ) {
                                    Text(
                                        "Delete",
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

    if (showForm && !loading && loadError == null) {
        AlertDialog(
            onDismissRequest = {
                if (!saving) showForm = false
            },
            title = {
                Text(if (editingId == null) "Add subject" else "Edit subject")
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            formError = null
                        },
                        label = { Text("Subject name") },
                        enabled = !saving,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = lecturerName,
                        onValueChange = {
                            lecturerName = it
                            formError = null
                        },
                        label = { Text("Lecturer name (optional)") },
                        enabled = !saving,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    formError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        formError = SubjectValidator.validate(
                            name,
                            lecturerName,
                            subjects,
                            editingId
                        )

                        if (formError == null && !saving) {
                            val draft = Subject(
                                subjectId = editingId ?: "",
                                name = name.trim(),
                                lecturerName = lecturerName.trim()
                            )

                            saving = true

                            scope.launch {
                                try {
                                    val saved = if (draft.subjectId.isBlank()) {
                                        repository.createSubject(draft)
                                    } else {
                                        repository.updateSubject(draft)
                                    }

                                    subjects = (
                                            subjects.filterNot {
                                                it.subjectId == saved.subjectId
                                            } + saved
                                            ).sortedBy { it.name.lowercase() }

                                    showForm = false
                                    editingId = null
                                    Log.d("StudySync", "Subject saved through repository")
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    formError = apiErrorMessage(
                                        exception,
                                        "Could not save this subject."
                                    )
                                    Log.e("StudySync", "Subject save failed", exception)
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                ) {
                    Text(if (saving) "Saving..." else "Save")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { showForm = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    val subjectToDelete = subjects.find {
        it.subjectId == pendingDeleteId
    }

    if (subjectToDelete != null && !loading && loadError == null) {
        AlertDialog(
            onDismissRequest = {
                if (!saving) pendingDeleteId = null
            },
            title = { Text("Delete subject?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Delete ${subjectToDelete.name}? This cannot be undone."
                    )

                    deleteError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
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
                                    repository.deleteSubject(
                                        subjectToDelete.subjectId
                                    )

                                    subjects = subjects.filterNot {
                                        it.subjectId == subjectToDelete.subjectId
                                    }

                                    pendingDeleteId = null
                                    Log.d("StudySync", "Subject deleted through repository")
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    deleteError = apiErrorMessage(
                                        exception,
                                        "Could not delete this subject."
                                    )
                                    Log.e("StudySync", "Subject deletion failed", exception)
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        if (saving) "Deleting..." else "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { pendingDeleteId = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}