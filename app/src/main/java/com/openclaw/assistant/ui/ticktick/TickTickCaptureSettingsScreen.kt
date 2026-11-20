package com.openclaw.assistant.ui.ticktick

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.service.TickTickVoiceCaptureService
import com.openclaw.assistant.ticktick.Gemma4AudioTranscriber
import com.openclaw.assistant.ticktick.Gemma4DownloadState
import com.openclaw.assistant.ticktick.Gemma4ModelManager
import com.openclaw.assistant.ticktick.GlossarySyncClient
import com.openclaw.assistant.ticktick.GlossarySyncOutcome
import com.openclaw.assistant.ticktick.GlossarySyncScheduler
import com.openclaw.assistant.ticktick.OnDeviceLanguageStatus
import com.openclaw.assistant.ticktick.OnDeviceLanguageSupport
import com.openclaw.assistant.ticktick.ScreenOffVolumeKeyBridge
import com.openclaw.assistant.ticktick.TickTickApiClient
import com.openclaw.assistant.ticktick.TickTickCaptureRepository
import com.openclaw.assistant.ticktick.TickTickOAuthHelper
import com.openclaw.assistant.ticktick.TranscriptNormalizer
import com.openclaw.assistant.ui.ticktick.TickTickLockCaptureActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TickTickCaptureSettingsContent(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository.getInstance(context) }
    val repository = remember { TickTickCaptureRepository.getInstance(context) }
    val apiClient = remember { TickTickApiClient() }
    val oauthHelper = remember { TickTickOAuthHelper(apiClient) }

    var enabled by remember { mutableStateOf(settings.tickTickCaptureEnabled) }
    var triggerMode by remember { mutableStateOf(settings.tickTickVolumeTrigger) }
    var holdThresholdMs by remember { mutableFloatStateOf(settings.tickTickHoldThresholdMs.toFloat()) }
    var speechEngine by remember { mutableStateOf(settings.tickTickSpeechEngine) }
    var multiLangSwitch by remember { mutableStateOf(settings.tickTickMultiLangSwitch) }
    var hapticFeedback by remember { mutableStateOf(settings.tickTickHapticFeedback) }
    var audioCue by remember { mutableStateOf(settings.tickTickAudioCue) }
    var statusNotifications by remember { mutableStateOf(settings.tickTickStatusNotifications) }
    var customVocabulary by remember { mutableStateOf(settings.tickTickCustomVocabulary) }
    var syncedGlossary by remember { mutableStateOf(settings.tickTickSyncedGlossary) }
    var weeklySyncEnabled by remember { mutableStateOf(settings.tickTickGlossaryWeeklyEnabled) }
    var primaryGlossaryUrl by remember { mutableStateOf(settings.tickTickGlossaryPrimaryUrl) }
    var fallbackGlossaryUrl by remember { mutableStateOf(settings.tickTickGlossaryFallbackUrl) }
    var glossaryAuthToken by remember { mutableStateOf(settings.tickTickGlossaryAuthToken) }
    var showGlossaryToken by remember { mutableStateOf(false) }
    var showSyncUrls by remember { mutableStateOf(false) }
    var lastSyncAt by remember { mutableLongStateOf(settings.tickTickGlossaryLastSyncAt) }
    var lastSyncStatus by remember { mutableStateOf(settings.tickTickGlossaryLastStatus) }
    var isSyncingGlossary by remember { mutableStateOf(false) }
    var accessToken by remember { mutableStateOf(settings.tickTickAccessToken) }
    var showToken by remember { mutableStateOf(false) }

    var statusBanner by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var isHoldTestRecording by remember { mutableStateOf(false) }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
    }

    var accessibilityRunning by remember { mutableStateOf(AgentVoiceAccessibilityService.isRunning()) }
    val powerManager = context.getSystemService(PowerManager::class.java)
    var batteryUnrestricted by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var fullScreenIntentGranted by remember {
        mutableStateOf(TickTickLockCaptureActivity.canUseFullScreenIntent(context))
    }
    var screenOffVolumeGranted by remember {
        mutableStateOf(ScreenOffVolumeKeyBridge.hasPermission(context))
    }
    var showAdbGuide by remember { mutableStateOf(false) }
    var keyDiagnostic by remember { mutableStateOf(AgentVoiceAccessibilityService.lastVolumeDiagnostic) }
    var captureDiagnostic by remember {
        mutableStateOf(
            settings.tickTickLastCaptureDiagnostic.takeIf { it != "No capture yet" }
                ?: TickTickVoiceCaptureService.lastCaptureDiagnostic,
        )
    }
    val gemmaDownloadState by Gemma4ModelManager.downloadState.collectAsState()
    var gemmaLoadedInRam by remember { mutableStateOf(Gemma4AudioTranscriber.isLoaded()) }

    LaunchedEffect(Unit) {
        Gemma4ModelManager.refreshState(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityRunning = AgentVoiceAccessibilityService.isRunning()
                batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                overlayGranted = Settings.canDrawOverlays(context)
                fullScreenIntentGranted = TickTickLockCaptureActivity.canUseFullScreenIntent(context)
                screenOffVolumeGranted = ScreenOffVolumeKeyBridge.hasPermission(context)
                keyDiagnostic = AgentVoiceAccessibilityService.lastVolumeDiagnostic
                captureDiagnostic = settings.tickTickLastCaptureDiagnostic.takeIf { it != "No capture yet" }
                    ?: TickTickVoiceCaptureService.lastCaptureDiagnostic
                Gemma4ModelManager.refreshState(context)
                gemmaLoadedInRam = Gemma4AudioTranscriber.isLoaded()
                ScreenOffVolumeKeyBridge.syncRegistration(context, force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pendingTasks by repository.observePendingTasks().collectAsState(initial = emptyList())

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Readiness Checklist
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Setup Checklist (100% Offline)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                ChecklistRow(
                    label = "TickTick Access Token",
                    ok = accessToken.isNotBlank(),
                    actionLabel = null,
                    onAction = null,
                )
                ChecklistRow(
                    label = "Microphone Permission",
                    ok = micGranted,
                    actionLabel = if (micGranted) null else "Grant",
                    onAction = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
                ChecklistRow(
                    label = "Accessibility Service (Volume Keys)",
                    ok = accessibilityRunning,
                    actionLabel = if (accessibilityRunning) null else "Open Settings",
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                ChecklistRow(
                    label = "Unrestricted Battery (Screen-Off)",
                    ok = batteryUnrestricted,
                    actionLabel = if (batteryUnrestricted) null else "Allow",
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
                ChecklistRow(
                    label = "Full-Screen Lock Alert (Screen-Off Mic)",
                    ok = fullScreenIntentGranted,
                    actionLabel = if (fullScreenIntentGranted) "Check" else "Allow",
                    onAction = {
                        fullScreenIntentGranted = TickTickLockCaptureActivity.canUseFullScreenIntent(context)
                        if (!fullScreenIntentGranted && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                )
                ChecklistRow(
                    label = "Display Over Other Apps (Screen-Off Mic)",
                    ok = overlayGranted,
                    actionLabel = if (overlayGranted) "Check" else "Allow",
                    onAction = {
                        overlayGranted = Settings.canDrawOverlays(context)
                        if (!overlayGranted) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                )
                ChecklistRow(
                    label = "Screen-Off Volume Key (ADB / Pixel)",
                    ok = screenOffVolumeGranted,
                    actionLabel = if (screenOffVolumeGranted) "Active" else if (showAdbGuide) "Hide" else "Setup",
                    onAction = {
                        screenOffVolumeGranted = ScreenOffVolumeKeyBridge.hasPermission(context)
                        if (screenOffVolumeGranted) {
                            ScreenOffVolumeKeyBridge.syncRegistration(context)
                            statusBanner = "Screen-off volume listener active."
                        } else {
                            showAdbGuide = !showAdbGuide
                        }
                    },
                )
                if (showAdbGuide && !screenOffVolumeGranted) {
                    val adbCmd = remember { ScreenOffVolumeKeyBridge.adbGrantCommand(context) }
                    HorizontalDivider()
                    Text(
                        "Pixel/Android 14+ blocks Accessibility volume keys when the screen is off. Run once from Mac USB debugging (or Shizuku aShell):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        adbCmd,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(adbCmd))
                                statusBanner = "Copied ADB command to clipboard."
                            },
                        ) {
                            Text("Copy ADB Command")
                        }
                        OutlinedButton(
                            onClick = {
                                val ok = ScreenOffVolumeKeyBridge.hasPermission(context)
                                screenOffVolumeGranted = ok
                                if (ok) {
                                    ScreenOffVolumeKeyBridge.syncRegistration(context)
                                    showAdbGuide = false
                                    statusBanner = "Screen-off volume permission detected!"
                                } else {
                                    statusBanner = "Permission not granted yet."
                                }
                            },
                        ) {
                            Text("Re-check")
                        }
                    }
                    Text(
                        "Zero-ADB alternative: add the 'TickTick Capture' tile to Quick Settings for 1-tap lock-screen dictation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Hardware Trigger & Dictation Config
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Volume-Hold Quick Capture", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Hold button to dictate directly into TickTick Inbox; short taps still change volume.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            settings.tickTickCaptureEnabled = it
                            ScreenOffVolumeKeyBridge.syncRegistration(context, force = true)
                        },
                    )
                }

                HorizontalDivider()

                Text("Trigger Button", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        SettingsRepository.TICKTICK_TRIGGER_VOLUME_DOWN to "Volume Down",
                        SettingsRepository.TICKTICK_TRIGGER_VOLUME_UP to "Volume Up",
                        SettingsRepository.TICKTICK_TRIGGER_EITHER to "Either Key",
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = triggerMode == value,
                            onClick = {
                                triggerMode = value
                                settings.tickTickVolumeTrigger = value
                                ScreenOffVolumeKeyBridge.syncRegistration(context, force = true)
                            },
                            label = { Text(label) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Last key event: $keyDiagnostic",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            keyDiagnostic = AgentVoiceAccessibilityService.lastVolumeDiagnostic
                            captureDiagnostic = settings.tickTickLastCaptureDiagnostic.takeIf { it != "No capture yet" }
                                ?: TickTickVoiceCaptureService.lastCaptureDiagnostic
                            ScreenOffVolumeKeyBridge.syncRegistration(context, force = true)
                        },
                    ) {
                        Text("Refresh")
                    }
                }
                Text(
                    text = "Last capture: $captureDiagnostic",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "Hold Threshold: ${holdThresholdMs.roundToLong()} ms",
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = holdThresholdMs,
                    onValueChange = { holdThresholdMs = it },
                    onValueChangeFinished = {
                        settings.tickTickHoldThresholdMs = holdThresholdMs.roundToLong()
                    },
                    valueRange = 250f..900f,
                    steps = 12,
                )

                HorizontalDivider()

                val onDeviceGoogleSupported = remember(context) {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                }
                Text("On-Device Speech Engine", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = speechEngine == SettingsRepository.TICKTICK_ENGINE_GEMMA4_AUDIO,
                        onClick = {
                            speechEngine = SettingsRepository.TICKTICK_ENGINE_GEMMA4_AUDIO
                            settings.tickTickSpeechEngine = SettingsRepository.TICKTICK_ENGINE_GEMMA4_AUDIO
                            Gemma4ModelManager.refreshState(context)
                        },
                        label = { Text("Gemma 4 E2B (Native Audio)") },
                    )
                    FilterChip(
                        selected = speechEngine == SettingsRepository.TICKTICK_ENGINE_ANDROID_ON_DEVICE,
                        enabled = onDeviceGoogleSupported,
                        onClick = {
                            speechEngine = SettingsRepository.TICKTICK_ENGINE_ANDROID_ON_DEVICE
                            settings.tickTickSpeechEngine = SettingsRepository.TICKTICK_ENGINE_ANDROID_ON_DEVICE
                        },
                        label = {
                            Text(
                                if (onDeviceGoogleSupported) {
                                    "Google On-Device (Best Accuracy)"
                                } else {
                                    "Google On-Device (Unavailable)"
                                }
                            )
                        },
                    )
                    FilterChip(
                        selected = speechEngine == SettingsRepository.TICKTICK_ENGINE_VOSK,
                        onClick = {
                            speechEngine = SettingsRepository.TICKTICK_ENGINE_VOSK
                            settings.tickTickSpeechEngine = SettingsRepository.TICKTICK_ENGINE_VOSK
                        },
                        label = { Text("Vosk Offline (Fallback)") },
                    )
                }

                if (speechEngine == SettingsRepository.TICKTICK_ENGINE_GEMMA4_AUDIO ||
                    gemmaDownloadState is Gemma4DownloadState.Downloading
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val statusLine = when (val st = gemmaDownloadState) {
                                is Gemma4DownloadState.Ready -> {
                                    val gb = String.format(Locale.US, "%.2f", st.sizeBytes / 1_000_000_000.0)
                                    "Model Ready ($gb GB)${if (gemmaLoadedInRam) " • Loaded in RAM" else ""}"
                                }
                                is Gemma4DownloadState.Downloading -> {
                                    val pct = if (st.totalBytes > 0L) ((st.downloadedBytes * 100L) / st.totalBytes).toInt() else 0
                                    val mbDone = st.downloadedBytes / 1_048_576L
                                    val mbTotal = (st.totalBytes.coerceAtLeast(1L)) / 1_048_576L
                                    "Downloading $pct% ($mbDone / $mbTotal MB)"
                                }
                                is Gemma4DownloadState.Error -> "Download Error: ${st.message}"
                                Gemma4DownloadState.NotDownloaded -> "Model Not Downloaded (~2.59 GB)"
                            }
                            Text(
                                text = statusLine,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            val dl = gemmaDownloadState as? Gemma4DownloadState.Downloading
                            if (dl != null) {
                                val fraction = if (dl.totalBytes > 0L) {
                                    (dl.downloadedBytes.toFloat() / dl.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(
                                text = "100% offline • 0% idle CPU • auto-unloads after 90s",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                when (gemmaDownloadState) {
                                    is Gemma4DownloadState.Downloading -> {
                                        OutlinedButton(onClick = { Gemma4ModelManager.cancelDownload() }) {
                                            Text("Cancel")
                                        }
                                    }
                                    is Gemma4DownloadState.Ready -> {
                                        if (gemmaLoadedInRam) {
                                            OutlinedButton(
                                                onClick = {
                                                    Gemma4AudioTranscriber.unloadNow()
                                                    gemmaLoadedInRam = false
                                                },
                                            ) {
                                                Text("Unload RAM")
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                Gemma4ModelManager.deleteModel(context)
                                                gemmaLoadedInRam = false
                                            },
                                        ) {
                                            Text("Delete Model")
                                        }
                                    }
                                    else -> {
                                        Button(onClick = { Gemma4ModelManager.startDownload(context) }) {
                                            Text("Download Model (~2.59 GB)")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (speechEngine == SettingsRepository.TICKTICK_ENGINE_ANDROID_ON_DEVICE && onDeviceGoogleSupported) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Multilingual Auto-Switch (DE / AR)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Keep OFF for highest English accuracy; turns off German/Arabic STT switching.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = multiLangSwitch,
                            onCheckedChange = {
                                multiLangSwitch = it
                                settings.tickTickMultiLangSwitch = it
                            },
                        )
                    }
                    if (multiLangSwitch) {
                        var langStatus by remember {
                            mutableStateOf(OnDeviceLanguageSupport.cachedStatus)
                        }
                        LaunchedEffect(Unit) {
                            langStatus = OnDeviceLanguageSupport.queryAndPrepare(
                                context,
                                triggerDownloads = true,
                            )
                        }
                        val statusText = remember(langStatus) {
                            val s = langStatus
                            if (s == null) {
                                "Checking EN / DE / AR on-device language packs..."
                            } else {
                                buildString {
                                    append("Active: ")
                                    append(s.installedDesired.joinToString(", ").ifBlank { "none" })
                                    if (s.missingSupported.isNotEmpty() || s.pendingDesired.isNotEmpty()) {
                                        val dl = (s.missingSupported + s.pendingDesired).distinct()
                                        append(" | Downloading: ${dl.joinToString(", ")}")
                                    }
                                    if (s.unsupportedFamilies.isNotEmpty()) {
                                        append(" | No offline AiAi pack: ${s.unsupportedFamilies.joinToString(", ")}")
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        langStatus = OnDeviceLanguageSupport.queryAndPrepare(
                                            context,
                                            triggerDownloads = true,
                                        )
                                    }
                                },
                            ) {
                                Text("Refresh Packs")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Haptic Confirmation", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = hapticFeedback,
                        onCheckedChange = {
                            hapticFeedback = it
                            settings.tickTickHapticFeedback = it
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Subtle Start/Stop Beep", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = audioCue,
                        onCheckedChange = {
                            audioCue = it
                            settings.tickTickAudioCue = it
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Status Notifications", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Post save/offline alerts to Notification Center",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = statusNotifications,
                        onCheckedChange = {
                            statusNotifications = it
                            settings.tickTickStatusNotifications = it
                        },
                    )
                }
            }
        }

        // Custom Vocabulary & Phonetic Aliases Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Project & Name Glossary",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "One entry per line (Name or Name = alias1, alias2). Spoken 'hashtag X' becomes #X.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            customVocabulary = TranscriptNormalizer.DEFAULT_RAW_VOCABULARY
                            settings.tickTickCustomVocabulary = TranscriptNormalizer.DEFAULT_RAW_VOCABULARY
                        },
                    ) {
                        Text("Reset")
                    }
                }
                OutlinedTextField(
                    value = customVocabulary,
                    onValueChange = {
                        customVocabulary = it
                        settings.tickTickCustomVocabulary = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    label = { Text("Projects, Relatives & Phonetic Aliases") },
                )

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Weekly Auto-Sync (Mac + GitHub)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Checks Mac Bridge first, then GitHub fallback. Waits for internet if offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = weeklySyncEnabled,
                        onCheckedChange = { checked ->
                            weeklySyncEnabled = checked
                            settings.tickTickGlossaryWeeklyEnabled = checked
                            if (checked) {
                                GlossarySyncScheduler.ensureScheduled(context)
                                GlossarySyncScheduler.enqueueImmediateCatchUpIfStale(context)
                            } else {
                                GlossarySyncScheduler.cancel(context)
                            }
                        },
                    )
                }

                val syncedCount = remember(syncedGlossary) {
                    syncedGlossary.lineSequence().count { it.isNotBlank() && !it.trimStart().startsWith("#") }
                }
                val formattedLastSync = remember(lastSyncAt) {
                    if (lastSyncAt <= 0L) {
                        "Never"
                    } else {
                        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastSyncAt))
                    }
                }
                Text(
                    text = "Synced entries: $syncedCount • Last sync: $formattedLastSync" +
                        if (lastSyncStatus.isNotBlank()) " • $lastSyncStatus" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        enabled = !isSyncingGlossary,
                        onClick = {
                            isSyncingGlossary = true
                            scope.launch {
                                when (val outcome = GlossarySyncClient().syncAndSave(context)) {
                                    is GlossarySyncOutcome.Success -> {
                                        syncedGlossary = settings.tickTickSyncedGlossary
                                        lastSyncAt = settings.tickTickGlossaryLastSyncAt
                                        lastSyncStatus = settings.tickTickGlossaryLastStatus
                                        statusBanner = "Synced ${outcome.count} glossary entries via ${outcome.sourceLabel}"
                                    }
                                    is GlossarySyncOutcome.Failure -> {
                                        lastSyncStatus = settings.tickTickGlossaryLastStatus
                                        statusBanner = "Glossary sync failed: ${outcome.reason}"
                                    }
                                }
                                isSyncingGlossary = false
                            }
                        },
                    ) {
                        if (isSyncingGlossary) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Syncing…")
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Now")
                        }
                    }

                    TextButton(onClick = { showSyncUrls = !showSyncUrls }) {
                        Text(if (showSyncUrls) "Hide Sync URLs" else "Sync URLs")
                    }
                }

                if (showSyncUrls) {
                    OutlinedTextField(
                        value = primaryGlossaryUrl,
                        onValueChange = {
                            primaryGlossaryUrl = it
                            settings.tickTickGlossaryPrimaryUrl = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Mac Bridge Glossary URL (Primary)") },
                    )
                    OutlinedTextField(
                        value = fallbackGlossaryUrl,
                        onValueChange = {
                            fallbackGlossaryUrl = it
                            settings.tickTickGlossaryFallbackUrl = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("GitHub Raw Fallback URL") },
                    )
                    OutlinedTextField(
                        value = glossaryAuthToken,
                        onValueChange = {
                            glossaryAuthToken = it
                            settings.tickTickGlossaryAuthToken = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("GitHub Token (Optional, for private repo)") },
                        visualTransformation = if (showGlossaryToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showGlossaryToken = !showGlossaryToken }) {
                                Icon(
                                    imageVector = if (showGlossaryToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle GitHub token visibility",
                                )
                            }
                        },
                    )
                }
            }
        }

        // TickTick Authentication Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "TickTick Credentials",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = accessToken,
                    onValueChange = {
                        accessToken = it
                        settings.tickTickAccessToken = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TickTick Access Token") },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            isBusy = true
                            statusBanner = "Opening TickTick sign-in…"
                            scope.launch {
                                val res = oauthHelper.authenticateInBrowser(
                                    context = context,
                                    existingClientId = settings.tickTickClientId,
                                    onClientIdCreated = { settings.tickTickClientId = it },
                                )
                                res.onSuccess { token ->
                                    accessToken = token
                                    settings.tickTickAccessToken = token
                                    statusBanner = "TickTick signed in and saved!"
                                }.onFailure { err ->
                                    statusBanner = "Sign-in failed: ${err.message}"
                                }
                                isBusy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Sign in (OAuth)")
                    }

                    OutlinedButton(
                        enabled = !isBusy && accessToken.isNotBlank(),
                        onClick = {
                            isBusy = true
                            statusBanner = "Testing TickTick API…"
                            scope.launch {
                                val res = apiClient.testConnection(accessToken)
                                statusBanner = res.fold(
                                    onSuccess = { count -> "Connected! Found $count project(s)." },
                                    onFailure = { err -> "Failed: ${err.message}" },
                                )
                                isBusy = false
                            }
                        },
                    ) {
                        Text("Test")
                    }
                }

                statusBanner?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Manual Test & Offline Queue Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Offline Queue (${pendingTasks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(
                        enabled = !isBusy && pendingTasks.isNotEmpty(),
                        onClick = {
                            isBusy = true
                            scope.launch {
                                val synced = repository.flushPending()
                                statusBanner = "Synced $synced queued task(s)."
                                isBusy = false
                            }
                        },
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retry Sync")
                    }
                }

                pendingTasks.take(5).forEach { task ->
                    Text(
                        "• [${task.status}] ${task.title}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        if (!isHoldTestRecording) {
                            isHoldTestRecording = true
                            TickTickVoiceCaptureService.startCapture(context)
                            scope.launch {
                                delay(4_000L)
                                if (isHoldTestRecording) {
                                    isHoldTestRecording = false
                                    TickTickVoiceCaptureService.stopCapture(context, cancel = false)
                                }
                            }
                        } else {
                            isHoldTestRecording = false
                            TickTickVoiceCaptureService.stopCapture(context, cancel = false)
                        }
                    },
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isHoldTestRecording) "Stop & Save Test Capture" else "Run 4s Voice Capture Test")
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    label: String,
    ok: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
