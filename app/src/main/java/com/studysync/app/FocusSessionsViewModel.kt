package com.studysync.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FocusSessionsViewModel(application: Application) : AndroidViewModel(application) {
    var timer by mutableStateOf(FocusTimer())
        private set
    var remainingMillis by mutableLongStateOf(DEFAULT_FOCUS_DURATION)
        private set
    var history by mutableStateOf<List<CompletedFocusSession>>(emptyList())
        private set
    var loadingHistory by mutableStateOf(false)
        private set
    var historyError by mutableStateOf<Int?>(null)
        private set
    var saveError by mutableStateOf<Int?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set

    private var userId: String? = null
    private var accountRevision = 0
    private var ticker: Job? = null
    private var historyJob: Job? = null
    private var saveJob: Job? = null

    fun setUser(id: String?) {
        if (id == userId) return
        accountRevision++
        ticker?.cancel()
        historyJob?.cancel()
        saveJob?.cancel()
        userId = id
        timer = FocusTimer()
        remainingMillis = timer.durationMillis
        history = emptyList()
        loadingHistory = false
        historyError = null
        saveError = null
        saved = false
        saving = false
        if (id != null) reloadHistory()
    }

    fun selectSubject(subject: Subject?) {
        if (timer.state != FocusState.READY) return
        timer = timer.copy(subjectId = subject?.subjectId, subjectName = subject?.name)
    }

    fun selectDuration(minutes: Int) {
        if (timer.state != FocusState.READY || minutes !in listOf(1, 5, 25, 50)) return
        timer = FocusTimer(
            durationMillis = minutes * 60_000L,
            subjectId = timer.subjectId,
            subjectName = timer.subjectName
        )
        remainingMillis = timer.durationMillis
    }

    fun start() {
        if (userId == null || timer.state !in listOf(FocusState.READY, FocusState.PAUSED)) return
        timer = timer.start(SystemClock.elapsedRealtime())
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (timer.state == FocusState.RUNNING) {
                refresh()
                if (timer.state == FocusState.RUNNING) delay(250)
            }
        }
    }

    fun pause() {
        timer = timer.pause(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        ticker?.cancel()
        refresh()
    }

    fun cancel() {
        timer = timer.cancel(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        ticker?.cancel()
        refresh()
    }

    fun reset() {
        refresh()
        if (saving || (timer.state == FocusState.COMPLETED && !saved)) return
        ticker?.cancel()
        timer = timer.reset()
        remainingMillis = timer.durationMillis
        saved = false
        saveError = null
    }

    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        timer = timer.update(now, System.currentTimeMillis())
        remainingMillis = timer.remaining(now)
        if (timer.state == FocusState.COMPLETED && !saved && !saving && saveError == null) {
            saveCompletion()
        }
    }

    fun saveCompletion() {
        val account = userId ?: return
        val revision = accountRevision
        if (timer.state != FocusState.COMPLETED || saving || saved) return
        val completed = timer
        saving = true
        saveError = null
        saveJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FocusSessionStore(getApplication()).use { store ->
                        check(store.saveCompleted(account, completed))
                    }
                }
                if (userId == account && accountRevision == revision) {
                    saved = true
                    reloadHistory()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (userId == account && accountRevision == revision) saveError = R.string.focus_save_error
                Log.e("StudySync", "Focus history save failed", exception)
            } finally {
                if (userId == account && accountRevision == revision) saving = false
            }
        }
    }

    fun reloadHistory() {
        val account = userId ?: return
        val revision = accountRevision
        historyJob?.cancel()
        loadingHistory = true
        historyError = null
        historyJob = viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    FocusSessionStore(getApplication()).use { it.history(account) }
                }
                if (userId == account && accountRevision == revision) history = loaded
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (userId == account && accountRevision == revision) historyError = R.string.focus_history_error
                Log.e("StudySync", "Focus history loading failed", exception)
            } finally {
                if (userId == account && accountRevision == revision) loadingHistory = false
            }
        }
    }
}
