package com.studysync.app

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Log.d("StudySync", "Main screen created")

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2855B8)
                )
            ) {
                AuthScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySyncScreen(
    userId: String,
    userEmail: String,
    biometricLock: BiometricLock,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val repository: StudyRepository = remember(userId) {
        ApiStudyRepository(userId)
    }

    val settingsStorage = remember(context) {
        SettingsStorage(context.applicationContext)
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    var savingSettings by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settingsStorage, reloadKey) {
        loadError = null

        try {
            settings = settingsStorage.load()
            Log.d("StudySync", "Settings loaded")
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            loadError = "Could not load your settings. Please try again."
            Log.e("StudySync", "Settings loading failed", exception)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StudySync") },
                actions = {
                    TextButton(
                        enabled = !savingSettings,
                        onClick = onSignOut
                    ) {
                        Text("Sign out")
                    }
                }
            )
        }
    ) { padding ->
        val currentSettings = settings

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loadError != null -> {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            loadError ?: "",
                            color = MaterialTheme.colorScheme.error
                        )

                        Button(onClick = { reloadKey++ }) {
                            Text("Retry")
                        }
                    }
                }

                currentSettings == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                else -> {
                    TabRow(selectedTabIndex = selectedTab) {
                        listOf("Tasks", "Subjects", "Settings")
                            .forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    enabled = !savingSettings,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) }
                                )
                            }
                    }

                    when (selectedTab) {
                        0 -> TasksScreen(
                            repository = repository,
                            settings = currentSettings
                        )

                        1 -> SubjectsScreen(repository = repository)

                        2 -> SettingsScreen(
                            settings = currentSettings,
                            saving = savingSettings,
                            error = saveError,
                            message = saveMessage,
                            accountEmail = userEmail,
                            userId = userId,
                            biometricLock = biometricLock,
                            onSettingsChange = { updated ->
                                if (!savingSettings &&
                                    updated != currentSettings
                                ) {
                                    savingSettings = true
                                    saveError = null
                                    saveMessage = null

                                    scope.launch {
                                        try {
                                            settingsStorage.save(updated)
                                            settings = updated
                                            saveMessage = "Settings saved."

                                            Log.d(
                                                "StudySync",
                                                "Settings saved"
                                            )
                                        } catch (exception: CancellationException) {
                                            throw exception
                                        } catch (exception: Exception) {
                                            saveError =
                                                "Could not save settings. " +
                                                        "Please try again."

                                            Log.e(
                                                "StudySync",
                                                "Settings save failed",
                                                exception
                                            )
                                        } finally {
                                            savingSettings = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}