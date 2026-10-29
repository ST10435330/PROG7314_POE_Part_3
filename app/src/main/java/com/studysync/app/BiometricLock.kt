package com.studysync.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


class BiometricLock(context: Context) {
    enum class Action { ENABLE, UNLOCK, DISABLE }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        "studysync_biometrics", Context.MODE_PRIVATE
    )
    private var enabledAccounts by mutableStateOf(preferences.all.keys.toSet())
    private var prompt: BiometricPrompt? = null
    private var generation = 0

    var unlockedUserId by mutableStateOf<String?>(null)
        private set

    fun isEnabled(userId: String): Boolean = alias(userId) in enabledAccounts

    // The UI must call this when the application leaves the foreground.
    fun lock() {
        unlockedUserId = null
        generation++
        prompt?.cancelAuthentication()
        prompt = null
        Log.d("StudySync", "Biometric session locked")
    }

    fun authenticate(
        activity: FragmentActivity,
        userId: String,
        action: Action,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (prompt != null) {
            onError("An authentication request is already open.")
            return
        }

        val availability = BiometricManager.from(appContext).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            onError(
                when (availability) {
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                        "Add a fingerprint or strong face credential in phone settings."
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                        "This phone does not support strong biometric authentication."
                    else -> "Biometrics are unavailable. Try again or use Google sign-in."
                }
            )
            return
        }

        try {
            check(FirebaseAuth.getInstance().currentUser?.uid == userId)
            check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            check((action == Action.ENABLE) != isEnabled(userId))

            val cipher = prepareCipher(userId, action)
            val requestGeneration = ++generation

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    if (requestGeneration != generation ||
                        FirebaseAuth.getInstance().currentUser?.uid != userId ||
                        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) {
                        onError("Authentication cancelled. Please try again.")
                        return
                    }

                    prompt = null

                    try {
                        val authenticatedCipher =
                            checkNotNull(result.cryptoObject?.cipher)

                        if (action == Action.ENABLE) {
                            val encrypted = authenticatedCipher.doFinal(
                                challenge(userId)
                            )

                            val record = JSONObject()
                                .put("iv", encode(authenticatedCipher.iv))
                                .put("data", encode(encrypted))

                            check(
                                preferences.edit()
                                    .putString(alias(userId), record.toString())
                                    .commit()
                            )

                            enabledAccounts = enabledAccounts + alias(userId)
                        } else {
                            val record = savedRecord(userId)

                            val decrypted = authenticatedCipher.doFinal(
                                decode(record.getString("data"))
                            )

                            check(
                                MessageDigest.isEqual(
                                    decrypted,
                                    challenge(userId)
                                )
                            )

                            if (action == Action.DISABLE) {
                                removeEnrollment(userId)
                            }
                        }

                        unlockedUserId = userId

                        Log.d(
                            "StudySync",
                            "Biometric operation succeeded: $action"
                        )

                        onSuccess()
                    } catch (exception: Exception) {
                        Log.w(
                            "StudySync",
                            "Biometric verification failed",
                            exception
                        )

                        onError(
                            "Verification failed. Use Google sign-in to recover."
                        )
                    }
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    if (requestGeneration == generation) {
                        prompt = null
                    }

                    Log.d(
                        "StudySync",
                        "Biometric prompt closed: $errorCode"
                    )

                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // The native prompt stays open for another attempt.
                    Log.d("StudySync", "Biometric did not match")
                }
            }

            val nativePrompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                callback
            )

            prompt = nativePrompt

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("StudySync verification")
                .setSubtitle("Verify your identity to continue")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                )
                .setNegativeButtonText("Cancel")
                .build()

            nativePrompt.authenticate(
                info,
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (exception: Exception) {
            prompt = null

            Log.w(
                "StudySync",
                "Biometric operation unavailable",
                exception
            )

            onError(
                "Biometric setup is unavailable. " +
                        "Use Google sign-in, then enable it again."
            )
        }
    }

    // Call only after a fresh Google sign-in completes, never for a cached user.
    fun resetAfterGoogleSignIn(userId: String) {
        check(FirebaseAuth.getInstance().currentUser?.uid == userId)

        if (isEnabled(userId)) {
            removeEnrollment(userId)
        }

        Log.d(
            "StudySync",
            "Biometric recovery completed after Google sign-in"
        )
    }

    @Suppress("DEPRECATION")
    private fun prepareCipher(
        userId: String,
        action: Action
    ): Cipher {
        val keyAlias = alias(userId)

        if (action == Action.ENABLE) {
            val specification = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or
                        KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                specification.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                specification.setUserAuthenticationValidityDurationSeconds(-1)
            }

            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
                .apply { init(specification.build()) }
                .generateKey()
        }

        val key = keyStore().getKey(keyAlias, null) as? SecretKey
            ?: error("Biometric key is missing")

        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            if (action == Action.ENABLE) {
                init(Cipher.ENCRYPT_MODE, key)
            } else {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(
                        128,
                        decode(savedRecord(userId).getString("iv"))
                    )
                )
            }
        }
    }

    private fun removeEnrollment(userId: String) {
        check(
            preferences.edit()
                .remove(alias(userId))
                .commit()
        )

        enabledAccounts = enabledAccounts - alias(userId)

        try {
            keyStore().deleteEntry(alias(userId))
        } catch (exception: Exception) {
            Log.w(
                "StudySync",
                "Could not remove unused biometric key",
                exception
            )
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

    private fun savedRecord(userId: String) = JSONObject(
        checkNotNull(
            preferences.getString(alias(userId), null)
        )
    )

    private fun challenge(userId: String) =
        "StudySync unlock:$userId".toByteArray(Charsets.UTF_8)

    private fun alias(userId: String): String =
        "studysync.biometric." + encode(
            MessageDigest.getInstance("SHA-256")
                .digest(userId.toByteArray(Charsets.UTF_8))
        )

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)
}