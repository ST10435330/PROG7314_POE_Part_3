package com.studysync.app

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FocusSessionStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearHistory() {
        context.deleteDatabase("studysync_focus.db")
    }

    @After
    fun removeHistory() {
        context.deleteDatabase("studysync_focus.db")
    }

    private fun completed(subject: Boolean = true) = FocusTimer(
        durationMillis = 60_000,
        subjectId = if (subject) "subject-1" else null,
        subjectName = if (subject) "Mathematics" else null
    ).start(1000).update(61_000, 1_700_000_000_000)

    @Test
    fun completedHistorySurvivesClosingAndReopeningTheDatabase() {
        val timer = completed()
        FocusSessionStore(context).use { assertTrue(it.saveCompleted("user-a", timer)) }
        FocusSessionStore(context).use { reopened ->
            val record = reopened.history("user-a").single()
            assertEquals(timer.sessionId, record.sessionId)
            assertEquals("user-a", record.userId)
            assertEquals(timer.completedAt, record.completedAt)
            assertEquals(60_000L, record.durationMillis)
            assertEquals("subject-1", record.subjectId)
            assertEquals("Mathematics", record.subjectName)
        }
    }

    @Test
    fun readyRunningPausedAndCancelledSessionsAreNotSaved() {
        val ready = FocusTimer(durationMillis = 60_000)
        val running = ready.start(1000)
        val paused = running.pause(20_000, 100_000)
        val cancelled = running.cancel(20_000, 100_000)
        FocusSessionStore(context).use { store ->
            for (timer in listOf(ready, running, paused, cancelled, cancelled.reset())) {
                assertFalse(store.saveCompleted("user-a", timer))
            }
            assertTrue(store.history("user-a").isEmpty())
        }
        FocusSessionStore(context).use { assertTrue(it.history("user-a").isEmpty()) }
    }

    @Test
    fun retriesCannotDuplicateOrOverwriteACompletedSession() {
        val timer = completed()
        FocusSessionStore(context).use { store ->
            assertTrue(store.saveCompleted("user-a", timer))
            assertTrue(store.saveCompleted("user-a", timer))
            assertTrue(store.saveCompleted("user-a", timer.copy(subjectName = "Changed later")))
            assertEquals("Mathematics", store.history("user-a").single().subjectName)
        }
        FocusSessionStore(context).use { store ->
            assertTrue(store.saveCompleted("user-a", timer))
            assertEquals(1, store.history("user-a").size)
        }
    }

    @Test
    fun concurrentCompletionSavesStillCreateOnlyOneRecord() {
        val timer = completed()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val saves = (1..2).map {
                executor.submit<Boolean> {
                    start.await()
                    FocusSessionStore(context).use { it.saveCompleted("user-a", timer) }
                }
            }
            start.countDown()
            saves.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            FocusSessionStore(context).use { assertEquals(1, it.history("user-a").size) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun historyIsSeparatedByTheSignedInUser() {
        val first = completed()
        val second = completed(false)
        FocusSessionStore(context).use { store ->
            store.saveCompleted("user-a", first)
            store.saveCompleted("user-b", second)
            assertEquals(first.sessionId, store.history("user-a").single().sessionId)
            assertEquals(second.sessionId, store.history("user-b").single().sessionId)
            assertTrue(store.history("other-user").isEmpty())
            assertTrue(store.history("' OR 1=1 --").isEmpty())
        }
    }

    @Test
    fun sessionsWithoutSubjectsCanBeSavedAndHistoryIsNewestFirst() {
        val older = completed(false)
        val newer = completed().copy(completedAt = older.completedAt!! + 60_000)
        FocusSessionStore(context).use { store ->
            store.saveCompleted("user-a", older)
            store.saveCompleted("user-a", newer)
            val history = store.history("user-a")
            assertEquals(listOf(newer.sessionId, older.sessionId), history.map { it.sessionId })
            assertNull(history.last().subjectId)
            assertNull(history.last().subjectName)
        }
    }
}
