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
                error = "Could not update notification settings. Please retry."
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
        else error = "Notifications remain off. You can allow them in Android settings."
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
                error = "Could not schedule notification registration. Please retry."
            }
        }
    }

    Text("Push notifications", style = MaterialTheme.typography.titleMedium)
    Text("Receive study updates on this device. This choice is saved separately for each account.")

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Allow study notifications", modifier = Modifier.weight(1f))

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
                        error = "Allow notifications in Android settings, then enable this switch."
                }
            }
        )
    }

    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (!permitted) Text("Android currently blocks these notifications.")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            enabled = enabled && permitted && !busy,
            onClick = {
                try {
                    PushNotifications.schedule(context, userId, test = true)
                    error = null
                } catch (_: Exception) {
                    error = "Could not queue a test notification. Please retry."
                }
            }
        ) {
            Text("Send test")
        }

        TextButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                } catch (_: Exception) {
                    error = "Open Android settings and choose StudySync notifications."
                }
            }
        ) {
            Text("Android settings")
        }
    }

    Text(
        "The test waits at least 10 seconds so you can put the app in the background.",
        style = MaterialTheme.typography.bodySmall
    )
}