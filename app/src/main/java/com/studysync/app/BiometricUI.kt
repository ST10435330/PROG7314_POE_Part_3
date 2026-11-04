package com.studysync.app

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity


internal fun Context.findBiometricActivity(): FragmentActivity? {
    var current: Context = this

    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current

        val base = current.baseContext
        if (base === current) return null

        current = base
    }

    return current as? FragmentActivity
}

/** Keeps study screens outside the composition until verification succeeds. */
@Composable
fun BiometricLockScreen(
    userId: String,
    biometricLock: BiometricLock,
    onUseGoogle: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(
            16.dp,
            Alignment.CenterVertically
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            context.getString(R.string.biometric_locked),
            style = MaterialTheme.typography.headlineMedium
        )

        Text(context.getString(R.string.biometric_unlock_hint))

        BiometricActionButton(
            userId = userId,
            biometricLock = biometricLock,
            action = BiometricLock.Action.UNLOCK,
            label = context.getString(R.string.unlock_biometrics)
        )

        OutlinedButton(
            onClick = onUseGoogle,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(context.getString(R.string.use_google))
        }

        Text(
            context.getString(R.string.biometric_recovery_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun BiometricSettings(
    userId: String,
    biometricLock: BiometricLock
) {
    val context = LocalContext.current
    val enabled = biometricLock.isEnabled(userId)

    Text(
        context.getString(R.string.biometric_protection),
        style = MaterialTheme.typography.titleMedium
    )

    Text(
        if (enabled) {
            context.getString(R.string.biometric_enabled)
        } else {
            context.getString(R.string.biometric_description)
        }
    )

    BiometricActionButton(
        userId = userId,
        biometricLock = biometricLock,
        action = if (enabled) {
            BiometricLock.Action.DISABLE
        } else {
            BiometricLock.Action.ENABLE
        },
        label = if (enabled) {
            context.getString(R.string.disable_biometrics)
        } else {
            context.getString(R.string.enable_biometrics)
        }
    )

    Text(
        context.getString(R.string.biometric_settings_hint),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun BiometricActionButton(
    userId: String,
    biometricLock: BiometricLock,
    action: BiometricLock.Action,
    label: String
) {
    val context = LocalContext.current
    val activity = remember(context) {
        context.findBiometricActivity()
    }

    var busy by remember(userId) { mutableStateOf(false) }
    var error by remember(userId) { mutableStateOf<String?>(null) }

    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        onClick = {
            error = null

            if (activity == null) {
                error =
                    AppMessage.BIOMETRIC_OPEN_ERROR.name
            } else {
                busy = true

                biometricLock.authenticate(
                    activity = activity,
                    userId = userId,
                    action = action,
                    onSuccess = {
                        busy = false
                        error = null
                    },
                    onError = { message ->
                        busy = false
                        error = message
                    }
                )
            }
        }
    ) {
        Text(
            if (busy) context.getString(R.string.waiting_verification) else label
        )
    }

    error?.let {
        Text(messageText(context, it), color = MaterialTheme.colorScheme.error)
    }
}
