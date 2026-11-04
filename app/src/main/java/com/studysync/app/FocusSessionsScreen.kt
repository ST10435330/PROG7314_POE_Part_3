package com.studysync.app

import android.util.Log
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun FocusSessionsScreen(
    repository: StudyRepository,
    sessions: FocusSessionsViewModel,
    dataVersion: Long = 0L
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var subjects by remember { mutableStateOf<List<Subject>>(emptyList()) }
    var subjectError by remember { mutableStateOf(false) }
    var subjectReload by remember { mutableIntStateOf(0) }

    LaunchedEffect(repository, dataVersion, subjectReload) {
        try {
            subjects = repository.getSubjects()
            subjectError = false
            if (sessions.timer.state == FocusState.READY &&
                subjects.none { it.subjectId == sessions.timer.subjectId }
            ) sessions.selectSubject(null)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            subjectError = true
            Log.e("StudySync", "Focus subjects loading failed", exception)
        }
    }

    DisposableEffect(lifecycle, sessions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) sessions.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(sessions) { sessions.refresh() }

    val timer = sessions.timer
    val ready = timer.state == FocusState.READY
    val active = timer.state == FocusState.RUNNING || timer.state == FocusState.PAUSED
    val timeText = focusTimeText(sessions.remainingMillis)
    val timeDescription = context.getString(R.string.focus_time_remaining, timeText)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(context.getString(R.string.focus_sessions), style = MaterialTheme.typography.headlineMedium)
            Text(context.getString(R.string.focus_description))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TaskChoice(
                        label = context.getString(R.string.focus_duration),
                        selectedText = focusDurationText(context, timer.durationMillis),
                        choices = listOf(1, 5, 25, 50).map { minutes ->
                            minutes.toString() to focusDurationText(context, minutes * 60_000L)
                        },
                        enabled = ready,
                        onSelect = { sessions.selectDuration(it.toInt()) }
                    )
                    TaskChoice(
                        label = context.getString(R.string.focus_subject),
                        selectedText = timer.subjectName ?: context.getString(R.string.focus_no_subject),
                        choices = listOf("" to context.getString(R.string.focus_no_subject)) +
                            subjects.map { it.subjectId to it.name },
                        enabled = ready,
                        onSelect = { id -> sessions.selectSubject(subjects.find { it.subjectId == id }) }
                    )
                    if (subjectError) {
                        Text(context.getString(R.string.focus_subject_error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { subjectReload++ }) { Text(context.getString(R.string.retry)) }
                    } else if (subjects.isEmpty()) {
                        Text(context.getString(R.string.focus_no_subjects_hint))
                    }
                    Text(
                        timeText,
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.semantics { contentDescription = timeDescription }
                    )
                    Text(context.getString(focusStateLabel(timer.state)))
                    when (timer.state) {
                        FocusState.READY -> Button(onClick = sessions::start, modifier = Modifier.fillMaxWidth()) {
                            Text(context.getString(R.string.focus_start))
                        }
                        FocusState.RUNNING -> Button(onClick = sessions::pause, modifier = Modifier.fillMaxWidth()) {
                            Text(context.getString(R.string.focus_pause))
                        }
                        FocusState.PAUSED -> Button(onClick = sessions::start, modifier = Modifier.fillMaxWidth()) {
                            Text(context.getString(R.string.focus_resume))
                        }
                        else -> Unit
                    }
                    if (active) {
                        OutlinedButton(onClick = sessions::cancel, modifier = Modifier.fillMaxWidth()) {
                            Text(context.getString(R.string.focus_cancel))
                        }
                    }
                    if (timer.state != FocusState.READY) {
                        OutlinedButton(
                            onClick = sessions::reset,
                            enabled = !sessions.saving && (timer.state != FocusState.COMPLETED || sessions.saved),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(context.getString(R.string.focus_reset))
                        }
                    }
                    if (sessions.saving) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(context.getString(R.string.focus_saving))
                    }
                    if (sessions.saved) {
                        Text(context.getString(R.string.focus_saved), color = MaterialTheme.colorScheme.primary)
                    }
                    sessions.saveError?.let { error ->
                        Text(context.getString(error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = sessions::saveCompletion) { Text(context.getString(R.string.retry)) }
                    }
                    Text(context.getString(R.string.focus_history_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text(context.getString(R.string.focus_history), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = sessions::reloadHistory, enabled = !sessions.loadingHistory) {
                Text(context.getString(R.string.refresh))
            }
            if (sessions.loadingHistory) LinearProgressIndicator(Modifier.fillMaxWidth())
            sessions.historyError?.let { error ->
                Text(context.getString(error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = sessions::reloadHistory) { Text(context.getString(R.string.retry)) }
            }
            if (!sessions.loadingHistory && sessions.historyError == null && sessions.history.isEmpty()) {
                Text(context.getString(R.string.focus_empty_history))
            }
        }
        items(sessions.history, key = { it.sessionId }) { session ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        context.getString(
                            R.string.focus_completed_date,
                            focusDateText(session.completedAt, locale)
                        )
                    )
                    Text(focusDurationText(context, session.durationMillis))
                    Text(session.subjectName ?: context.getString(R.string.focus_no_subject))
                }
            }
        }
    }
}

fun focusDurationText(context: Context, durationMillis: Long): String {
    val minutes = (durationMillis / 60_000).toInt()
    return context.resources.getQuantityString(R.plurals.focus_minutes, minutes, minutes)
}

fun focusDateText(
    completedAt: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault()
): String = Instant.ofEpochMilli(completedAt).atZone(zone).format(
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
)

fun focusStateLabel(state: FocusState): Int = when (state) {
    FocusState.READY -> R.string.focus_ready
    FocusState.RUNNING -> R.string.focus_running
    FocusState.PAUSED -> R.string.focus_paused
    FocusState.COMPLETED -> R.string.focus_completed
    FocusState.CANCELLED -> R.string.focus_cancelled
}
