package com.foodinspector.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodinspector.app.ui.theme.FoodInspectorTheme
import com.foodinspector.app.ui.theme.FoodIconColor
import com.foodinspector.app.ui.theme.GlassesIconColor
import com.foodinspector.app.ui.theme.LiveRed
import com.foodinspector.app.ui.theme.SessionIconColor
import com.foodinspector.app.ui.theme.VoiceIconColor
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val vm: FoodLabelViewModel by viewModels()

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()

    /** The file the system Camera app is asked to write a full-resolution photo into — set right
     *  before launching the camera, read back once it reports success. */
    private var pendingPhotoFile: File? = null

    private val androidPermissionsLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true &&
                result[Manifest.permission.BLUETOOTH_CONNECT] == true
            ) {
                // DAT camera permission is requested through Wearables.RequestPermissionContract.
            }
        }

    private val wearablesPermissionLauncher =
        registerForActivityResult(
            Wearables.RequestPermissionContract()
        ) { result ->
            permissionContinuation?.resume(result.getOrNull() ?: PermissionStatus.Denied)
            permissionContinuation = null
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchPhoneCamera()
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingPhotoFile
            pendingPhotoFile = null
            if (success && file != null) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) vm.analyzePhoneCameraPhoto(bitmap)
                file.delete()
            }
        }

    /** Serialized: only one glasses permission request can be in flight against the launcher
     *  at a time, since the launcher callback resumes whatever continuation is currently set. */
    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
        permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                wearablesPermissionLauncher.launch(permission)
            }
        }

    /** Entry point for the bottom-row camera icon: request the CAMERA runtime permission first
     *  if needed (declared in the manifest already, but still dangerous/runtime-gated), then
     *  hand off to launchPhoneCamera(). */
    private fun onCameraIconClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchPhoneCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchPhoneCamera() {
        val file = File.createTempFile("phone_capture_", ".jpg", cacheDir)
        pendingPhotoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePictureLauncher.launch(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        androidPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        )

        vm.initializeGlasses(::requestWearablesPermission)

        setContent {
            FoodInspectorTheme {
                val state by vm.ui.collectAsStateWithLifecycle()
                FoodLabelScreen(
                    state = state,
                    onRegister = { vm.register(this) },
                    onConnect = {
                        vm.connectGlasses(::requestWearablesPermission)
                    },
                    onDisconnect = vm::disconnectGlasses,
                    onPreviewSurfaceChanged = vm::setPreviewSurface,
                    onCaptureAnalyze = vm::captureAndAnalyze,
                    onToggleVoiceMode = vm::toggleVoiceMode,
                    onToggleCloudMode = vm::toggleCloudMode,
                    onSaveEvidence = vm::saveEvidence,
                    onClearSession = vm::clearSession,
                    onSendToSerio = vm::sendToSerio,
                    onClearError = vm::clearError,
                    onBackendUrlChange = vm::updateBackendUrl,
                    onLaunchPhoneCamera = ::onCameraIconClicked,
                )
            }
        }
    }
}

private enum class BottomTab { GLASSES, SESSION, FOOD, VOICE }
private enum class DrawerScreen { MAIN, SETTINGS, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodLabelScreen(
    state: AppUiState,
    onRegister: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPreviewSurfaceChanged: (Surface?) -> Unit,
    onCaptureAnalyze: () -> Unit,
    onToggleVoiceMode: () -> Unit,
    onToggleCloudMode: () -> Unit,
    onSaveEvidence: () -> Unit,
    onSendToSerio: () -> Unit,
    onClearSession: () -> Unit,
    onClearError: () -> Unit,
    onBackendUrlChange: (String) -> Unit,
    onLaunchPhoneCamera: () -> Unit,
) {
    var screen by remember { mutableStateOf(DrawerScreen.MAIN) }
    var selectedTab by remember { mutableStateOf<BottomTab?>(null) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // System/gesture back leaves Settings/About the same way the in-app back arrow does,
    // instead of exiting the app.
    BackHandler(enabled = screen != DrawerScreen.MAIN) { screen = DrawerScreen.MAIN }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.padding(top = 12.dp))
                Text("Food Inspector", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                Divider()
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = screen == DrawerScreen.SETTINGS,
                    onClick = {
                        screen = DrawerScreen.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                NavigationDrawerItem(
                    label = { Text("About") },
                    selected = screen == DrawerScreen.ABOUT,
                    onClick = {
                        screen = DrawerScreen.ABOUT
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Food Inspector",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { screen = DrawerScreen.MAIN }
                        )
                    },
                    navigationIcon = {
                        if (screen == DrawerScreen.MAIN) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        } else {
                            IconButton(onClick = { screen = DrawerScreen.MAIN }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            when (screen) {
                DrawerScreen.SETTINGS -> SettingsScreen(
                    modifier = Modifier.padding(padding),
                    backendUrl = state.backendUrl,
                    onBackendUrlChange = onBackendUrlChange,
                    cloudMode = state.cloudMode,
                    onToggleCloudMode = onToggleCloudMode,
                )
                DrawerScreen.ABOUT -> AboutScreen(modifier = Modifier.padding(padding))
                DrawerScreen.MAIN -> MainScreenContent(
                    modifier = Modifier.padding(padding),
                    state = state,
                    selectedTab = selectedTab,
                    onTabSelected = { tab -> selectedTab = if (selectedTab == tab) null else tab },
                    moreMenuExpanded = moreMenuExpanded,
                    onMoreMenuExpandedChange = { moreMenuExpanded = it },
                    onRegister = onRegister,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onPreviewSurfaceChanged = onPreviewSurfaceChanged,
                    onCaptureAnalyze = onCaptureAnalyze,
                    onToggleVoiceMode = onToggleVoiceMode,
                    onSaveEvidence = onSaveEvidence,
                    onSendToSerio = onSendToSerio,
                    onClearSession = onClearSession,
                    onClearError = onClearError,
                    onLaunchPhoneCamera = onLaunchPhoneCamera,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    backendUrl: String,
    onBackendUrlChange: (String) -> Unit,
    cloudMode: Boolean,
    onToggleCloudMode: () -> Unit,
) {
    var backend by remember(backendUrl) { mutableStateOf(backendUrl) }
    var applying by remember { mutableStateOf(false) }
    val isValid = isValidHttpUrl(backend)

    // Debounced auto-apply: restarts on every keystroke (LaunchedEffect keyed on `backend`
    // cancels the previous delay), so only a genuine pause in typing actually commits.
    LaunchedEffect(backend) {
        if (!isValid || backend == backendUrl) return@LaunchedEffect
        delay(600)
        applying = true
        onBackendUrlChange(backend)
        delay(400) // deliberate perceptible transition, not a real connectivity check
        applying = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = backend,
                onValueChange = { backend = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Local backend URL") },
                singleLine = true,
                isError = !isValid,
                supportingText = {
                    Text(
                        if (isValid) "Default: http://192.168.1.242:8443"
                        else "Enter a valid http(s) URL, e.g. http://192.168.1.242:8443"
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use Cloud LLM", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (cloudMode) "Ollama Cloud (see backend/cloud_server.py)" else "Local Ollama on this PC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cloudMode, onCheckedChange = { onToggleCloudMode() })
            }
        }

        if (applying) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
private fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("About Food Inspector", style = MaterialTheme.typography.titleLarge)
        Text(
            "Food Inspector connects to Meta smart glasses, captures a photo of a food " +
                "label, and analyzes it using either a local Ollama model on your PC or " +
                "Ollama Cloud (toggle in Settings). You can also capture with your phone's own " +
                "camera — it goes through the exact same analysis.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Turn on Voice Mode to talk hands-free: say things like \"take a picture\" to " +
                "capture and analyze, ask any question about the last analyzed label, or say " +
                "\"save it\" / \"keep evidence\" to save the photo, analysis, and conversation " +
                "to your device's Downloads folder.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "The ⋮ menu on the main screen also offers a manual Save action, and a " +
                "placeholder Send to SERIO action for a future integration.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MainScreenContent(
    modifier: Modifier = Modifier,
    state: AppUiState,
    selectedTab: BottomTab?,
    onTabSelected: (BottomTab) -> Unit,
    moreMenuExpanded: Boolean,
    onMoreMenuExpandedChange: (Boolean) -> Unit,
    onRegister: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPreviewSurfaceChanged: (Surface?) -> Unit,
    onCaptureAnalyze: () -> Unit,
    onToggleVoiceMode: () -> Unit,
    onSaveEvidence: () -> Unit,
    onSendToSerio: () -> Unit,
    onClearSession: () -> Unit,
    onClearError: () -> Unit,
    onLaunchPhoneCamera: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Live preview: mirrors the raw (uncompressed) glasses feed frame-by-frame so
        // streaming can be visually confirmed instead of only inferred from status text.
        // Big by design (weight fills available space) — matches the mockup.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            isOpaque = true
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                    onPreviewSurfaceChanged(Surface(st))
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    onPreviewSurfaceChanged(null)
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (state.glasses.streamState == "STREAMING") {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .background(LiveRed, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Text(
                        state.glasses.streamState,
                        modifier = Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        BottomIconRow(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            voiceModeActive = state.voiceModeActive,
            moreMenuExpanded = moreMenuExpanded,
            onMoreMenuExpandedChange = onMoreMenuExpandedChange,
            onSaveEvidence = onSaveEvidence,
            onSendToSerio = onSendToSerio,
            onClearSession = onClearSession,
            onLaunchPhoneCamera = onLaunchPhoneCamera,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selectedTab == BottomTab.GLASSES) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Glasses", style = MaterialTheme.typography.titleLarge)
                        Text("Registration: ${state.glasses.registration}")
                        Text("Devices: ${state.glasses.deviceCount}")
                        Text("Stream: ${state.glasses.streamState}")
                        Text(
                            state.glasses.captureSource?.let { "${state.glasses.status} (last: $it)" }
                                ?: state.glasses.status
                        )

                        // Vertically stacked, in this order: Connect, Capture, Register.
                        if (state.glasses.streamState == "STOPPED") {
                            OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                                Text("Connect")
                            }
                        } else {
                            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                                Text("Disconnect")
                            }
                        }

                        Button(
                            onClick = onCaptureAnalyze,
                            enabled = !state.busy && state.glasses.streamState == "STREAMING",
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Capture From Glasses & Analyze")
                        }

                        Button(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
                            Text("Register with Meta AI")
                        }
                    }
                }
            }

            if (selectedTab == BottomTab.SESSION) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Session Information", style = MaterialTheme.typography.titleLarge)
                        Text("Session: ${state.sessionId ?: "not created"}")
                        Text("Status: ${state.status}")
                        if (state.foodCount > 0) {
                            Text("Detected food products: ${state.foodCount}")
                        }
                    }
                }
            }

            if (selectedTab == BottomTab.FOOD && state.analysisJson != null) {
                var showRawJson by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Food Label Analysis", style = MaterialTheme.typography.titleLarge)
                            FilledTonalIconButton(
                                onClick = { showRawJson = !showRawJson },
                                colors = if (showRawJson) IconButtonDefaults.filledIconButtonColors() // solid = active
                                else IconButtonDefaults.filledTonalIconButtonColors() // tonal = inactive, still a clear button
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DataObject,
                                    contentDescription = if (showRawJson) "Show table view" else "Show raw JSON",
                                )
                            }
                        }
                        Spacer(Modifier.padding(top = 8.dp))
                        if (showRawJson) {
                            Text(
                                state.analysisJson,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            FoodAnalysisTable(state.analysisJson)
                        }
                    }
                }
            }

            if (selectedTab == BottomTab.VOICE) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Voice Conversation", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (state.voiceModeActive)
                                "Listening — say \"take a picture\" or ask a question. No need to tap anything; it keeps listening after each answer."
                            else
                                "Turn on voice mode, then just talk — it listens continuously and acts as soon as you pause."
                        )

                        Button(
                            onClick = onToggleVoiceMode,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (state.voiceModeActive) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null
                            )
                            Spacer(Modifier.padding(4.dp))
                            Text(if (state.voiceModeActive) "Stop Voice Mode" else "Start Voice Mode")
                        }

                        state.lastQuestion?.let {
                            Divider()
                            Text("You: $it")
                        }
                        state.lastAnswer?.let {
                            Text("Assistant: $it")
                        }
                    }
                }
            }

            state.error?.let {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Error", color = MaterialTheme.colorScheme.error)
                        Text(it)
                        OutlinedButton(onClick = onClearError) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomIconRow(
    selectedTab: BottomTab?,
    onTabSelected: (BottomTab) -> Unit,
    voiceModeActive: Boolean,
    moreMenuExpanded: Boolean,
    onMoreMenuExpandedChange: (Boolean) -> Unit,
    onSaveEvidence: () -> Unit,
    onSendToSerio: () -> Unit,
    onClearSession: () -> Unit,
    onLaunchPhoneCamera: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColoredTabIcon(
            emoji = "🕶", // sunglasses
            color = GlassesIconColor,
            selected = selectedTab == BottomTab.GLASSES,
            contentDescription = "Glasses",
            onClick = { onTabSelected(BottomTab.GLASSES) },
        )
        ColoredTabIconVector(
            icon = if (voiceModeActive) Icons.Default.MicOff else Icons.Default.Mic,
            color = VoiceIconColor,
            selected = selectedTab == BottomTab.VOICE,
            contentDescription = "Voice",
            onClick = { onTabSelected(BottomTab.VOICE) },
        )
        ColoredTabIcon(
            emoji = "🍔", // burger
            color = FoodIconColor,
            selected = selectedTab == BottomTab.FOOD,
            contentDescription = "Food Analysis",
            onClick = { onTabSelected(BottomTab.FOOD) },
        )
        ColoredTabIconVector(
            icon = Icons.Default.Info,
            color = SessionIconColor,
            selected = selectedTab == BottomTab.SESSION,
            contentDescription = "Session Information",
            onClick = { onTabSelected(BottomTab.SESSION) },
        )
        IconButton(onClick = onLaunchPhoneCamera) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Capture with phone camera")
        }
        Box {
            IconButton(onClick = { onMoreMenuExpandedChange(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(
                expanded = moreMenuExpanded,
                onDismissRequest = { onMoreMenuExpandedChange(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Save") },
                    onClick = {
                        onMoreMenuExpandedChange(false)
                        onSaveEvidence()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Send to SERIO") },
                    onClick = {
                        onMoreMenuExpandedChange(false)
                        onSendToSerio()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Clear Session") },
                    onClick = {
                        onMoreMenuExpandedChange(false)
                        onClearSession()
                    }
                )
            }
        }
    }
}

@Composable
private fun ColoredTabIcon(
    emoji: String,
    color: Color,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (selected) color else color.copy(alpha = 0.35f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ColoredTabIconVector(
    icon: ImageVector,
    color: Color,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (selected) color else color.copy(alpha = 0.35f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White)
        }
    }
}

/** One row of the human-readable analysis table: a field label and its display value. */
private data class AnalysisRow(val field: String, val value: String)

/** Renders the backend's `{"foods": [...]}` analysis JSON (see LabelAnalysis/FdaMatch in
 *  main_server.py/fda_matcher.py) as one 2-column (field/value) table per detected product,
 *  the human-readable alternative to the raw-JSON view toggled by the {} icon. Field order and
 *  labels are hand-picked to match the known backend schema rather than a generic/alphabetical
 *  JSON flatten, since a fixed field like "Ingredients" reads better than a derived key name. */
@Composable
private fun FoodAnalysisTable(analysisJson: String) {
    val foods = remember(analysisJson) { parseFoodsFromAnalysisJson(analysisJson) }

    if (foods.isEmpty()) {
        Text("No food products detected.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        foods.forEachIndexed { index, food ->
            Column {
                val heading = food.optString("product_name").ifBlank { null }
                Text(
                    "Product ${index + 1}" + (heading?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.padding(top = 4.dp))
                AnalysisRowsTable(buildAnalysisRows(food))
            }
        }
    }
}

@Composable
private fun AnalysisRowsTable(rows: List<AnalysisRow>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    row.field,
                    modifier = Modifier.weight(0.4f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    row.value,
                    modifier = Modifier.weight(0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (index != rows.lastIndex) Divider()
        }
    }
}

/** analysisJson is the backend's pretty-printed `analysis` object, `{"foods": [...]}`. */
private fun parseFoodsFromAnalysisJson(analysisJson: String): List<JSONObject> = try {
    val foods = JSONObject(analysisJson).optJSONArray("foods") ?: JSONArray()
    (0 until foods.length()).map { foods.getJSONObject(it) }
} catch (e: Exception) {
    emptyList()
}

/** A JSON scalar/array rendered for display: null/missing -> em dash, an array joined with
 *  commas (empty array included, since "no allergens listed" is meaningfully different from
 *  a missing field, but both currently render the same way, an em dash, matching how a user
 *  reads "nothing here" either way). */
private fun displayValue(value: Any?): String = when {
    value == null || value == JSONObject.NULL -> "—"
    value is JSONArray -> if (value.length() == 0) "—" else
        (0 until value.length()).joinToString(", ") { value.get(it).toString() }
    else -> value.toString().ifBlank { "—" }
}

private fun buildAnalysisRows(food: JSONObject): List<AnalysisRow> {
    val rows = mutableListOf<AnalysisRow>()
    val simpleFields = listOf(
        "product_name" to "Product Name",
        "brand_name" to "Brand Name",
        "lot_number" to "Lot Number",
        "expiration_date" to "Expiration Date",
        "manufacturer" to "Manufacturer",
        "ingredients" to "Ingredients",
        "allergens" to "Allergens",
        "nutrition_claims" to "Nutrition Claims",
        "visible_text" to "Visible Text",
    )
    for ((key, label) in simpleFields) {
        rows += AnalysisRow(label, displayValue(food.opt(key)))
    }

    food.optJSONObject("fda_match")?.let { fda ->
        rows += AnalysisRow("FDA Match", displayValue(fda.opt("selected_match")))
        val confidence = fda.opt("confidence")
        if (confidence is Number) {
            rows += AnalysisRow("FDA Match Confidence", "${(confidence.toDouble() * 100).toInt()}%")
        }
        rows += AnalysisRow("Other FDA Candidates", displayValue(fda.opt("other_matches")))
        rows += AnalysisRow(
            "FDA Verification Required",
            if (fda.optBoolean("verification_required", true)) "Yes" else "No"
        )
    }

    return rows
}
