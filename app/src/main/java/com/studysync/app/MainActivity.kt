package com.studysync.app

import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModelProvider
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
        // Older Android versions need the saved language before activity creation.
        if (Build.VERSION.SDK_INT < 33) {
            AppLanguage.apply(AppLanguage.saved(this))
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            AppLanguage.apply(AppLanguage.saved(this))
        }
        PushNotifications.createChannel(this)
        enableEdgeToEdge()

        Log.d("StudySync", "Main screen created")

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2855B8)
                )
            ) {
                AuthScreen(
                    focusSessions = ViewModelProvider(this@MainActivity)[FocusSessionsViewModel::class.java],
                    attachments = ViewModelProvider(this@MainActivity)[AttachmentsViewModel::class.java]
                )
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
    focusSessions: FocusSessionsViewModel,
    attachments: AttachmentsViewModel,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(userId) {
        try {
            PushNotifications.schedule(context, userId)
        } catch (_: Exception) {
            Log.w("StudySync", "Could not schedule push registration")
        }
    }

    val repository = remember(context, userId) {
        OfflineStudyRepository(context.applicationContext, userId)
    }

    val dataVersion by repository.changes.collectAsState()

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
            loadError = AppMessage.SETTINGS_LOAD_ERROR.name
            Log.e("StudySync", "Settings loading failed", exception)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.app_name)) },
                actions = {
                    TextButton(
                        enabled = !savingSettings,
                        onClick = onSignOut
                    ) {
                        Text(context.getString(R.string.sign_out))
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
                            messageText(context, loadError.orEmpty()),
                            color = MaterialTheme.colorScheme.error
                        )

                        Button(onClick = { reloadKey++ }) {
                            Text(context.getString(R.string.retry))
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
                    SyncStatusCard(
                        repository = repository,
                        userId = userId,
                        dataVersion = dataVersion
                    )
                    ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                        listOf(
                            context.getString(R.string.tasks),
                            context.getString(R.string.subjects),
                            context.getString(R.string.settings),
                            context.getString(R.string.focus_tab),
                            context.getString(R.string.sr_tab)
                        )
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
                        4 -> StudyResourcesScreen(onOpenFocus = { selectedTab = 3 })

                        3 -> FocusSessionsScreen(
                            repository = repository,
                            sessions = focusSessions,
                            dataVersion = dataVersion
                        )

                        0 -> TasksScreen(
                            repository = repository,
                            settings = currentSettings,
                            dataVersion = dataVersion,
                            attachments = attachments
                        )

                        1 -> SubjectsScreen(
                            repository = repository,
                            dataVersion = dataVersion
                        )

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
                                            saveMessage = AppMessage.SETTINGS_SAVED.name
                                            AppLanguage.apply(updated.language)

                                            Log.d(
                                                "StudySync",
                                                "Settings saved"
                                            )
                                        } catch (exception: CancellationException) {
                                            throw exception
                                        } catch (exception: Exception) {
                                            saveError =
                                                AppMessage.SETTINGS_SAVE_ERROR.name

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
