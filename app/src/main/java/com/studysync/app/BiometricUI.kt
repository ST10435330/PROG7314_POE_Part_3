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
            "StudySync is locked",
            style = MaterialTheme.typography.headlineMedium
        )

        Text("Verify your identity to open your study planner.")

        BiometricActionButton(
            userId = userId,
            biometricLock = biometricLock,
            action = BiometricLock.Action.UNLOCK,
            label = "Unlock with biometrics"
        )

        OutlinedButton(
            onClick = onUseGoogle,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Use Google sign-in instead")
        }

        Text(
            "Google recovery signs you out first. After signing in, " +
                    "enable biometric protection again in Settings.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun BiometricSettings(
    userId: String,
    biometricLock: BiometricLock
) {
    val enabled = biometricLock.isEnabled(userId)

    Text(
        "Biometric protection",
        style = MaterialTheme.typography.titleMedium
    )

    Text(
        if (enabled) {
            "Enabled for this account on this device."
        } else {
            "Use a fingerprint or supported strong face recognition " +
                    "when reopening the app."
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
            "Disable biometric protection"
        } else {
            "Enable biometric protection"
        }
    )

    Text(
        "Changing this setting requires biometric verification. " +
                "Restore defaults changes task preferences only.",
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
                    "Could not open biometric verification. Please reopen the app."
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
            if (busy) "Waiting for verification..." else label
        )
    }

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}