package com.studysync.app

import org.junit.Assert.*
import org.junit.Test

class FocusTimerTest {
    @Test
    fun defaultSessionIsTwentyFiveMinutesAndDoesNotRunUntilStarted() {
        val timer = FocusTimer()
        assertEquals(25 * 60_000L, timer.durationMillis)
        assertEquals(FocusState.READY, timer.update(9_000_000, 1_700_000_000_000).state)
        assertEquals("25:00", focusTimeText(timer.remaining(9_000_000)))
    }

    @Test
    fun countdownUsesElapsedTimeInsteadOfCountingTicks() {
        val timer = FocusTimer(durationMillis = 60_000).start(1000)
        assertEquals(47_655L, timer.remaining(13_345))
        assertEquals("00:48", focusTimeText(timer.remaining(13_345)))
        assertEquals(FocusState.RUNNING, timer.update(60_999, 100).state)
    }

    @Test
    fun pausingStopsTimeAndResumingExcludesThePausedInterval() {
        val paused = FocusTimer(durationMillis = 60_000).start(1000).pause(21_000, 50_000)
        assertEquals(FocusState.PAUSED, paused.state)
        assertEquals(40_000L, paused.remaining(500_000))
        assertEquals(FocusState.PAUSED, paused.update(500_000, 1_000_000).state)
        val resumed = paused.start(500_000)
        assertEquals(30_000L, resumed.remaining(510_000))
        assertEquals(FocusState.COMPLETED, resumed.update(540_000, 1_000_000).state)
    }

    @Test
    fun repeatedStartDoesNotExtendTheSession() {
        val started = FocusTimer(durationMillis = 60_000).start(1000)
        assertEquals(started, started.start(30_000))
        assertEquals(30_000L, started.start(30_000).remaining(31_000))
    }

    @Test
    fun aDelayedBackgroundUpdateCompletesOnceWithTheActualFinishTime() {
        val timer = FocusTimer(durationMillis = 60_000).start(1000)
        val completed = timer.update(121_000, 1_700_000_120_000)
        assertEquals(FocusState.COMPLETED, completed.state)
        assertEquals(0L, completed.remaining(121_000))
        assertEquals(1_700_000_060_000L, completed.completedAt)
        assertEquals(completed, completed.update(300_000, 1_700_000_300_000))
    }

    @Test
    fun changingTheWallClockDoesNotChangeTheCountdown() {
        val timer = FocusTimer(durationMillis = 60_000).start(1000)
        assertEquals(timer, timer.update(31_000, 1))
        assertEquals(timer, timer.update(31_000, Long.MAX_VALUE))
        assertEquals(30_000L, timer.remaining(31_000))
    }

    @Test
    fun cancellingAnUnfinishedSessionIsTerminal() {
        val cancelled = FocusTimer(durationMillis = 60_000).start(1000).cancel(20_000, 100_000)
        assertEquals(FocusState.CANCELLED, cancelled.state)
        assertNull(cancelled.completedAt)
        assertEquals(cancelled, cancelled.update(500_000, 1_000_000))
        assertEquals(cancelled, cancelled.start(500_000))
    }

    @Test
    fun anExpiredSessionCompletesBeforeAPauseOrCancelCanDiscardIt() {
        val timer = FocusTimer(durationMillis = 60_000).start(1000)
        assertEquals(FocusState.COMPLETED, timer.pause(61_000, 100_000).state)
        assertEquals(FocusState.COMPLETED, timer.cancel(61_000, 100_000).state)
    }

    @Test
    fun resettingDiscardsProgressAndCreatesANewSessionId() {
        val paused = FocusTimer(subjectId = "maths", subjectName = "Mathematics")
            .start(1000).pause(61_000, 100_000)
        val reset = paused.reset()
        assertNotEquals(paused.sessionId, reset.sessionId)
        assertEquals(FocusState.READY, reset.state)
        assertEquals(DEFAULT_FOCUS_DURATION, reset.remainingAtStart)
        assertEquals("Mathematics", reset.subjectName)
        assertNull(reset.completedAt)
    }

    @Test
    fun timeFormattingRoundsUpWithoutShowingNegativeTime() {
        assertEquals("01:00", focusTimeText(59_999))
        assertEquals("00:01", focusTimeText(1))
        assertEquals("00:00", focusTimeText(0))
        assertEquals("00:00", focusTimeText(-1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDurationIsRejected() {
        FocusTimer(durationMillis = 0)
    }
}
