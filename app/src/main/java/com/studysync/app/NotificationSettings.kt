package com.studysync.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotificationSettings(userId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val revision by PushNotifications.changes.collectAsState()

    var permissionRevision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val enabled = remember(userId, revision) {
        PushNotifications.enabled(context, userId)
    }
    val message = remember(userId, revision) {
        PushNotifications.message(context, userId)
    }
    val permitted = remember(permissionRevision, revision) {
        PushNotifications.canPost(context)
    }

    fun change(value: Boolean) {
        scope.launch {
            busy = true
            error = null

            try {
                withContext(Dispatchers.IO) {
                    PushNotifications.setEnabled(context, userId, value)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                error = AppMessage.NOTIFICATIONS_SAVE_ERROR.name
            } finally {
                busy = false
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRevision++

        if (granted) change(true)
        else error = AppMessage.NOTIFICATIONS_DENIED.name
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
        }

        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(permissionRevision) {
        if (enabled) {
            try {
                PushNotifications.schedule(context, userId)
            } catch (_: Exception) {
                error = AppMessage.NOTIFICATIONS_SCHEDULE_ERROR.name
            }
        }
    }

    Text(context.getString(R.string.push_notifications), style = MaterialTheme.typography.titleMedium)
    Text(context.getString(R.string.notification_description))

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(context.getString(R.string.allow_notifications), modifier = Modifier.weight(1f))

        Switch(
            checked = enabled,
            enabled = !busy,
            onCheckedChange = { value ->
                when {
                    !value -> change(false)
                    permitted -> change(true)
                    Build.VERSION.SDK_INT >= 33 ->
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)

                    else ->
                        error = AppMessage.NOTIFICATIONS_ALLOW_SETTINGS.name
                }
            }
        )
    }

    message?.let { Text(messageText(context, it), style = MaterialTheme.typography.bodySmall) }

    if (!permitted) Text(context.getString(R.string.notifications_blocked))
    error?.let { Text(messageText(context, it), color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            enabled = enabled && permitted && !busy,
            onClick = {
                try {
                    PushNotifications.schedule(context, userId, test = true)
                    error = null
                } catch (_: Exception) {
                    error = AppMessage.NOTIFICATION_TEST_ERROR.name
                }
            }
        ) {
            Text(context.getString(R.string.send_test))
        }

        TextButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                } catch (_: Exception) {
                    error = AppMessage.NOTIFICATION_SETTINGS_ERROR.name
                }
            }
        ) {
            Text(context.getString(R.string.android_settings))
        }
    }

    Text(
        context.getString(R.string.notification_test_hint),
        style = MaterialTheme.typography.bodySmall
    )
}
