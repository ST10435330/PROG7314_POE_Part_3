package com.studysync.app

import android.content.MutableContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun AuthScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val biometricLock = remember(context) {
        BiometricLock(context)
    }

    val activity = remember(context) {
        context.findBiometricActivity()
    }

    val auth = remember { FirebaseAuth.getInstance() }

    val credentialManager = remember(context) {
        CredentialManager.create(context)
    }

    var user by remember { mutableStateOf(auth.currentUser) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Clear in-memory unlock permission whenever the activity stops.
    // Reference: https://developer.android.com/guide/components/activities/activity-lifecycle
    DisposableEffect(activity, biometricLock) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                biometricLock.lock()
            }
        }

        activity?.lifecycle?.addObserver(observer)

        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            biometricLock.lock()
        }
    }

    DisposableEffect(auth, biometricLock) {
        val listener = FirebaseAuth.AuthStateListener {
            if (user?.uid != it.currentUser?.uid) {
                biometricLock.lock()
            }

            user = it.currentUser
        }

        auth.addAuthStateListener(listener)

        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    // Shared by normal sign-out and Google recovery from the lock screen.
    val signOut: () -> Unit = {
        if (!busy) {
            busy = true
            error = null

            biometricLock.lock()
            auth.signOut()
            user = null

            scope.launch {
                try {
                    credentialManager.clearCredentialState(
                        ClearCredentialStateRequest()
                    )

                    Log.d("StudySync", "Signed out")
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    // Firebase is already signed out.
                    Log.w(
                        "StudySync",
                        "Could not clear credential provider state",
                        exception
                    )
                } finally {
                    busy = false
                }
            }
        }
    }

    val signedInUser = user

    if (signedInUser == null) {
        LoginScreen(
            busy = busy,
            error = error,
            onSignIn = {
                if (!busy) {
                    busy = true
                    error = null

                    scope.launch {
                        try {
                            val googleOption =
                                GetSignInWithGoogleOption.Builder(
                                    serverClientId = context.getString(
                                        R.string.default_web_client_id
                                    )
                                ).build()

                            val request = GetCredentialRequest.Builder()
                                .addCredentialOption(googleOption)
                                .build()

                            val response = credentialManager.getCredential(
                                context = MutableContextWrapper(context),
                                request = request
                            )

                            val credential = response.credential

                            if (credential !is CustomCredential ||
                                credential.type !=
                                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                            ) {
                                throw IllegalStateException(
                                    "Unexpected sign-in response."
                                )
                            }

                            val googleCredential =
                                GoogleIdTokenCredential.createFrom(
                                    credential.data
                                )

                            val firebaseCredential =
                                GoogleAuthProvider.getCredential(
                                    googleCredential.idToken,
                                    null
                                )

                            val result = auth
                                .signInWithCredential(firebaseCredential)
                                .await()

                            // Recovery requires fresh Google sign-in.
                            // A cached Firebase session never resets the lock.
                            val verifiedUser = checkNotNull(result.user)

                            biometricLock.resetAfterGoogleSignIn(
                                verifiedUser.uid
                            )

                            Log.d(
                                "StudySync",
                                if (result.additionalUserInfo?.isNewUser == true) {
                                    "Google account registered"
                                } else {
                                    "Google sign-in successful"
                                }
                            )
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: GetCredentialCancellationException) {
                            // Cancelling the chooser keeps the user signed out.
                            error = null
                        } catch (exception: NoCredentialException) {
                            error =
                                "No Google account was available. Check your " +
                                        "device's Google accounts and try again."
                        } catch (exception: FirebaseNetworkException) {
                            error =
                                "Could not connect. Check your internet and try again."
                        } catch (exception: Exception) {
                            error =
                                "Google sign-in failed. Please try again."

                            Log.e(
                                "StudySync",
                                "Google sign-in failed",
                                exception
                            )
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        )
    } else if (
        biometricLock.isEnabled(signedInUser.uid) &&
        biometricLock.unlockedUserId != signedInUser.uid
    ) {
        key(signedInUser.uid) {
            BiometricLockScreen(
                userId = signedInUser.uid,
                biometricLock = biometricLock,
                onUseGoogle = signOut
            )
        }
    } else {
        // Discard the previous user's screen state when accounts change.
        key(signedInUser.uid) {
            StudySyncScreen(
                userId = signedInUser.uid,
                userEmail = signedInUser.email.orEmpty(),
                biometricLock = biometricLock,
                onSignOut = signOut
            )
        }
    }
}

@Composable
private fun LoginScreen(
    busy: Boolean,
    error: String?,
    onSignIn: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "StudySync",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(Modifier.height(12.dp))

        Text("Organise your subjects and study tasks.")

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = onSignIn,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "Please wait..." else "Sign in with Google")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Your account is created the first time you sign in.",
            style = MaterialTheme.typography.bodySmall
        )

        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}