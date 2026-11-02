package com.studysync.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Account-specific opt-in, durable registration, and private notification display.
 * https://firebase.google.com/docs/cloud-messaging/android/receive-messages
 * https://developer.android.com/develop/ui/views/notifications/notification-permission
 */
object PushNotifications {
    private const val CHANNEL = "study_updates"
    private val counter = MutableStateFlow(0L)
    val changes = counter.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences("studysync_push", Context.MODE_PRIVATE)

    fun enabled(context: Context, uid: String) =
        prefs(context).getBoolean("enabled_$uid", false)

    fun message(context: Context, uid: String) =
        prefs(context).getString("message_$uid", null)

    fun status(context: Context, uid: String, text: String) {
        check(prefs(context).edit().putString("message_$uid", text).commit())
        counter.update { it + 1 }
    }

    @Synchronized
    fun setEnabled(context: Context, uid: String, value: Boolean) {
        check(prefs(context).edit().putBoolean("enabled_$uid", value).commit())
        if (!value) clear(context)
        status(context, uid, if (value) "Connecting notifications..." else "Notifications are off.")
        schedule(context, uid)
    }

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Study updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    @Synchronized
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    fun schedule(context: Context, uid: String, test: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setInputData(workDataOf("uid" to uid, "test" to test))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(if (test) 10L else 0L, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "studysync-push",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )

        if (test) status(
            context,
            uid,
            "Test queued. Put the app in the background and watch for a notification."
        )
    }

    /** Data messages are checked here in both foreground and background. */
    @Synchronized
    fun show(
        context: Context,
        data: Map<String, String>,
        currentUser: () -> String? = { FirebaseAuth.getInstance().currentUser?.uid }
    ): Boolean {
        val uid = currentUser() ?: return false
        val event = data["eventId"] ?: return false

        if (data["userId"] != uid ||
            data["type"] !in setOf("test", "task_update") ||
            !event.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            !enabled(context, uid) ||
            !canPost(context)
        ) return false

        val storage = prefs(context)
        val seen = JSONArray(storage.getString("seen_$uid", "[]"))
        val events = (0 until seen.length()).map { seen.getString(it) }
        if (event in events) return false

        createChannel(context)

        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = if (data["type"] == "test")
            "Push notifications are connected."
        else
            "Your study tasks have changed. Open StudySync to view them."

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        if (currentUser() != uid) return false

        try {
            // A repeated delivery uses the same tag even after a process restart.
            NotificationManagerCompat.from(context)
                .notify("studysync:$uid:$event", 0, notification)
        } catch (_: SecurityException) {
            return false
        }

        check(
            storage.edit()
                .putString("seen_$uid", JSONArray((events + event).takeLast(100)).toString())
                .commit()
        )

        Log.d("StudySync", "Push notification displayed")
        return true
    }

    suspend fun reconcile(context: Context, uid: String, sendTest: Boolean) {
        val api = ApiStudyRepository(uid)
        val storage = prefs(context)

        fun requireAccount() {
            check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        }

        requireAccount()

        val wanted = enabled(context, uid) && canPost(context)
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = wanted
        val oldToken = storage.getString("token_$uid", null)

        if (!wanted) {
            if (oldToken != null) {
                api.unregisterDevice(oldToken)
                storage.edit().remove("token_$uid").commit()
            }

            // Opt-out also resets an expired SDK token before a later re-enable.
            messaging.deleteToken().await()
            requireAccount()

            status(
                context,
                uid,
                if (enabled(context, uid))
                    "Android is blocking notifications. Check system settings."
                else
                    "Notifications are off."
            )
            return
        }

        val token = messaging.token.await()
        requireAccount()

        if (!enabled(context, uid)) {
            schedule(context, uid)
            return
        }

        if (oldToken != null && oldToken != token) {
            api.unregisterDevice(oldToken)
        }

        // Remember attempted registration so opt-out can revoke a lost reply too.
        check(storage.edit().putString("token_$uid", token).commit())
        api.registerDevice(token)
        requireAccount()

        if (!enabled(context, uid) || !canPost(context)) {
            schedule(context, uid)
            return
        }

        if (sendTest) api.testNotification(token)

        status(
            context,
            uid,
            if (sendTest) "Test sent. Check your notification tray."
            else "Notifications are connected."
        )
    }
}

/** Network work stays outside FirebaseMessagingService's short callback window. */
class PushRegistrationWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.failure()

        if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
            return Result.success()
        }

        return try {
            PushNotifications.reconcile(
                applicationContext,
                uid,
                inputData.getBoolean("test", false)
            )
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
                return Result.success()
            }

            PushNotifications.status(
                applicationContext,
                uid,
                apiErrorMessage(
                    exception,
                    "Could not connect notifications. Check your connection and retry."
                )
            )

            // Never log a token, request body, or Firebase exception message.
            Log.w("StudySync", "Push registration failed: ${exception.javaClass.simpleName}")

            if (exception is IOException ||
                exception is ApiException && exception.retryable
            ) Result.retry()
            else Result.failure()
        }
    }
}

class StudyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        if (PushNotifications.enabled(this, uid)) {
            PushNotifications.schedule(this, uid)
        }

        Log.d("StudySync", "FCM token changed; registration scheduled when enabled")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushNotifications.show(this, message.data)
    }

    override fun onDeletedMessages() {
        FirebaseAuth.getInstance().currentUser?.uid?.let {
            StudySync.enqueue(this, it)
        }
    }
}