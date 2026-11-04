package com.studysync.app

import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FocusSessionsViewModelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<FocusTestActivity>
    private lateinit var sessions: FocusSessionsViewModel

    @Before
    fun setup() {
        context.deleteDatabase("studysync_focus.db")
        controller = Robolectric.buildActivity(FocusTestActivity::class.java).setup()
        sessions = viewModel()
        sessions.setUser("user-a")
        await { !sessions.loadingHistory }
    }

    @After
    fun cleanup() {
        controller.get().viewModelStore.clear()
        controller.pause().stop().destroy()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    private fun viewModel(): FocusSessionsViewModel {
        // Bind each test to its own Application instead of the cached AndroidX factory.
        val factory = ViewModelProvider.AndroidViewModelFactory(context)
        return ViewModelProvider(controller.get(), factory)[FocusSessionsViewModel::class.java]
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "Focus state=${sessions.timer.state}, saving=${sessions.saving}, " +
                "saved=${sessions.saved}, error=${sessions.saveError}, " +
                "loading=${sessions.loadingHistory}, scope=${sessions.viewModelScope.isActive}",
            condition()
        )
    }

    @Test
    fun backgroundCompletionAppearsInHistoryExactlyOnce() {
        sessions.selectSubject(Subject("subject-1", "Mathematics"))
        sessions.start()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(26))
        sessions.refresh()
        await { sessions.saved && !sessions.loadingHistory }
        repeat(3) { sessions.refresh(); sessions.saveCompletion() }
        assertEquals(FocusState.COMPLETED, sessions.timer.state)
        assertEquals(0L, sessions.remainingMillis)
        assertEquals("Mathematics", sessions.history.single().subjectName)
        FocusSessionStore(context).use { assertEquals(1, it.history("user-a").size) }
    }

    @Test
    fun pauseResumeResetAndCancelDoNotSaveIncompleteSessions() {
        sessions.start()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        sessions.pause()
        assertEquals(20 * 60_000L, sessions.remainingMillis)
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        sessions.refresh()
        assertEquals(20 * 60_000L, sessions.remainingMillis)
        sessions.start()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        sessions.reset()
        assertEquals(DEFAULT_FOCUS_DURATION, sessions.remainingMillis)
        sessions.start()
        sessions.cancel()
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        sessions.refresh()
        assertEquals(FocusState.CANCELLED, sessions.timer.state)
        FocusSessionStore(context).use { assertTrue(it.history("user-a").isEmpty()) }
    }

    @Test
    fun timerSurvivesActivityRecreationAndLanguageSwitching() {
        sessions.start()
        val id = sessions.timer.sessionId
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        AppLanguage.apply("zu")
        controller.recreate()
        val retained = viewModel()
        assertSame(sessions, retained)
        retained.refresh()
        assertEquals(id, retained.timer.sessionId)
        assertTrue(retained.remainingMillis in (20 * 60_000L - 1000)..(20 * 60_000L))
        assertEquals("Ukugxila kuyaqhubeka", controller.get().getString(focusStateLabel(retained.timer.state)))
    }

    @Test
    fun signingOutCancelsTheOldTimerAndHidesItsHistory() {
        sessions.start()
        sessions.setUser(null)
        sessions.start()
        assertEquals(FocusState.READY, sessions.timer.state)
        sessions.setUser("user-b")
        await { !sessions.loadingHistory }
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        sessions.refresh()
        assertTrue(sessions.history.isEmpty())
        FocusSessionStore(context).use { assertTrue(it.history("user-a").isEmpty()) }
    }

    @Test
    fun reopeningTheAppLoadsOnlyTheSignedInAccountsHistory() {
        sessions.selectDuration(1)
        sessions.start()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        sessions.refresh()
        await { sessions.saved && !sessions.loadingHistory }
        val completedId = sessions.history.single().sessionId
        controller.pause().stop().destroy()

        controller = Robolectric.buildActivity(FocusTestActivity::class.java).setup()
        sessions = viewModel()
        sessions.setUser("user-b")
        await { !sessions.loadingHistory }
        assertTrue(sessions.history.isEmpty())
        sessions.setUser("user-a")
        await { !sessions.loadingHistory }
        assertEquals(completedId, sessions.history.single().sessionId)
        assertEquals(FocusState.READY, sessions.timer.state)
        assertEquals(DEFAULT_FOCUS_DURATION, sessions.remainingMillis)
    }

    @Test
    fun completionSaveFailureCanBeRetriedWithoutLosingOrDuplicatingTheSession() {
        FocusSessionStore(context).use {
            it.writableDatabase.execSQL(
                "CREATE TRIGGER fail_focus BEFORE INSERT ON focus_sessions " +
                    "BEGIN SELECT RAISE(ABORT, 'Test write failure'); END"
            )
        }
        sessions.selectDuration(1)
        sessions.start()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        sessions.refresh()
        await { sessions.saveError != null && !sessions.saving }
        val id = sessions.timer.sessionId
        sessions.reset()
        assertEquals(id, sessions.timer.sessionId)
        FocusSessionStore(context).use { it.writableDatabase.execSQL("DROP TRIGGER fail_focus") }
        sessions.saveCompletion()
        await { sessions.saved && !sessions.loadingHistory }
        assertEquals(id, sessions.history.single().sessionId)
        sessions.reset()
        assertNotEquals(id, sessions.timer.sessionId)
        assertEquals(1, sessions.history.size)
    }

    companion object {
        private const val WAIT_TIMEOUT_NANOS = 5_000_000_000L
    }
}

class FocusTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_StudySync)
        super.onCreate(savedInstanceState)
    }
}
