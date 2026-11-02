package com.studysync.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PushTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("studysync_push", 0)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val data = mapOf(
        "userId" to "user-a",
        "eventId" to "event-1",
        "type" to "test"
    )

    @Before
    fun setup() {
        preferences.edit().clear().putBoolean("enabled_user-a", true).commit()
        manager.cancelAll()
        PushNotifications.createChannel(context)
    }

    @Test
    fun accountGate() {
        assertFalse(PushNotifications.show(context, data) { null })
        assertFalse(PushNotifications.show(context, data) { "user-b" })
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun optOutGate() {
        preferences.edit().putBoolean("enabled_user-a", false).commit()
        assertFalse(PushNotifications.show(context, data) { "user-a" })
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun invalidPayloads() {
        for (bad in listOf(
            data - "eventId",
            data + ("eventId" to "../bad"),
            data + ("type" to "unknown"),
            data + ("userId" to "user-b")
        )) {
            assertFalse(PushNotifications.show(context, bad) { "user-a" })
        }

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun privateAndDeduplicated() {
        assertTrue(
            PushNotifications.show(
                context,
                data + ("body" to "Private remote content")
            ) { "user-a" }
        )

        val notification = manager.activeNotifications.single().notification

        assertEquals(
            "Push notifications are connected.",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.contentIntent)
        assertEquals(
            MainActivity::class.java.name,
            shadowOf(notification.contentIntent).savedIntent.component!!.className
        )

        // IDs are read from persistent preferences, not an in-memory receipt set.
        assertTrue(preferences.getString("seen_user-a", "")!!.contains("event-1"))
        assertFalse(PushNotifications.show(context, data) { "user-a" })
        assertEquals(1, manager.activeNotifications.size)

        PushNotifications.clear(context)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun accountChangesBeforePost() {
        var checks = 0

        assertFalse(
            PushNotifications.show(context, data) {
                if (++checks == 1) "user-a" else "user-b"
            }
        )

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    @Config(sdk = [33])
    fun permissionDenied() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(PushNotifications.show(context, data) { "user-a" })
        assertTrue(manager.activeNotifications.isEmpty())

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(PushNotifications.show(context, data) { "user-a" })
    }
}