package com.studysync.app

import java.util.Locale
import java.util.UUID

const val DEFAULT_FOCUS_DURATION = 25 * 60_000L

enum class FocusState { READY, RUNNING, PAUSED, COMPLETED, CANCELLED }

data class FocusTimer(
    val sessionId: String = UUID.randomUUID().toString(),
    val durationMillis: Long = DEFAULT_FOCUS_DURATION,
    val subjectId: String? = null,
    val subjectName: String? = null,
    val state: FocusState = FocusState.READY,
    val remainingAtStart: Long = durationMillis,
    val runningSince: Long = 0L,
    val completedAt: Long? = null
) {
    init {
        require(durationMillis > 0)
    }

    fun remaining(nowElapsed: Long): Long = when (state) {
        FocusState.RUNNING ->
            (remainingAtStart - (nowElapsed - runningSince).coerceAtLeast(0)).coerceAtLeast(0)
        else -> remainingAtStart
    }

    fun start(nowElapsed: Long): FocusTimer =
        if (state == FocusState.READY || state == FocusState.PAUSED) {
            copy(state = FocusState.RUNNING, runningSince = nowElapsed)
        } else this

    fun update(nowElapsed: Long, nowDate: Long): FocusTimer {
        if (state != FocusState.RUNNING || remaining(nowElapsed) > 0) return this
        // Record the finish time even if the app notices completion later.
        val overdue = (nowElapsed - runningSince - remainingAtStart).coerceAtLeast(0)
        return copy(
            state = FocusState.COMPLETED,
            remainingAtStart = 0,
            completedAt = nowDate - overdue
        )
    }

    fun pause(nowElapsed: Long, nowDate: Long): FocusTimer {
        val updated = update(nowElapsed, nowDate)
        return if (updated.state == FocusState.RUNNING) {
            updated.copy(state = FocusState.PAUSED, remainingAtStart = remaining(nowElapsed))
        } else updated
    }

    fun cancel(nowElapsed: Long, nowDate: Long): FocusTimer {
        val updated = update(nowElapsed, nowDate)
        return if (updated.state == FocusState.RUNNING || updated.state == FocusState.PAUSED) {
            updated.copy(state = FocusState.CANCELLED, remainingAtStart = updated.remaining(nowElapsed))
        } else updated
    }

    fun reset() = FocusTimer(
        durationMillis = durationMillis,
        subjectId = subjectId,
        subjectName = subjectName
    )
}

fun focusTimeText(remainingMillis: Long): String {
    val seconds = (remainingMillis.coerceAtLeast(0) + 999) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
