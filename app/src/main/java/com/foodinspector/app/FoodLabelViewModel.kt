package com.foodinspector.app

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** Spoken phrases that trigger a capture instead of an LLM question — the closest thing to a
 *  hands-free shutter this SDK allows, since third-party apps can't hook "Hey Meta" or the
 *  glasses' hardware button (there is no such API in DAT 0.9.0). Matched case-insensitively
 *  against the transcript. */
private val CAPTURE_VOICE_TRIGGERS = listOf(
    "take a picture",
    "take a photo",
    "take a snapshot",
    "analyze this",
    "capture",
)

/** Spoken phrases that save the current photo + analysis + conversation to a local file —
 *  checked before the ask-the-LLM fallback so none of these get sent to the backend as a
 *  question. Matched case-insensitively against the transcript. */
private val SAVE_VOICE_TRIGGERS = listOf(
    "save it",
    "save",
    "store",
    "copy",
    "keep evidence",
)

/** Spoken phrases that turn voice mode off — checked before the ask-the-LLM fallback. Handled
 *  the same way the manual "Stop Voice Mode" button is (stopVoiceMode()), just triggered by
 *  speech instead of a tap. */
private val STOP_VOICE_TRIGGERS = listOf(
    "stop voice mode",
    "stop voice",
    "turn off voice mode",
    "turn off voice",
    "pause voice",
)

/** Spoken phrases that disconnect the glasses / stop the live feed, without touching voice
 *  mode — the hands-free loop keeps listening afterward so you can still reconnect by voice. */
private val STOP_GLASSES_TRIGGERS = listOf(
    "stop glasses",
    "turn off glasses",
    "turn off glass",
    "disconnect glasses",
    "disconnect",
    "pause video",
    "pause image",
)

/** Spoken phrases that (re)connect the glasses and start streaming — the counterpart to
 *  STOP_GLASSES_TRIGGERS, so a spoken "disconnect" can be followed by a spoken way back in
 *  without touching the phone. Checked AFTER isStopGlassesCommand in handleVoiceCommand: since
 *  "disconnect" contains "connect" as a substring, checking stop first is what keeps a spoken
 *  "disconnect" from also matching this list. */
private val CONNECT_GLASSES_TRIGGERS = listOf(
    "start glasses",
    "turn on glasses",
    "turn on glass",
    "connect glasses",
    "connect",
    "start video",
    "start image",
    "start streaming",
)

data class ConversationTurn(val question: String, val answer: String)

/** Strips common inline Markdown emphasis (**bold**, *italic*, _underscore_, `code`) and
 *  leading '#' headers from LLM answer text before it's shown in the voice-conversation UI or
 *  spoken aloud — small local models often wrap numbers/words in markdown (e.g. "**95**
 *  calories") even when not asked to, and literal asterisks read poorly in both places. Keeps
 *  the actual words/numbers, just removes the formatting markers around them. */
private val MARKDOWN_EMPHASIS_RE = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|__(.+?)__|_(.+?)_|`(.+?)`""")
private val MARKDOWN_HEADER_RE = Regex("""(?m)^#{1,6}\s+""")

fun stripMarkdown(text: String): String {
    val withoutEmphasis = MARKDOWN_EMPHASIS_RE.replace(text) { match ->
        match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: match.value
    }
    return MARKDOWN_HEADER_RE.replace(withoutEmphasis, "")
}

/** Shared by the ViewModel (to decide whether to actually apply a typed backend URL) and the
 *  Settings screen (to show an inline validation message before that debounced apply fires). */
fun isValidHttpUrl(candidate: String): Boolean = try {
    val uri = URI(candidate)
    (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
} catch (e: Exception) {
    false
}

data class AppUiState(
    val backendUrl: String = BuildConfig.DEFAULT_BACKEND,
    val sessionId: String? = null,
    val glasses: GlassesUiState = GlassesUiState(),
    val busy: Boolean = false,
    val voiceModeActive: Boolean = false,
    val cloudMode: Boolean = false,
    val status: String = "Ready",
    val analysisJson: String? = null,
    val foodCount: Int = 0,
    val lastQuestion: String? = null,
    val lastAnswer: String? = null,
    val conversationHistory: List<ConversationTurn> = emptyList(),
    val error: String? = null,
    // The most recently analyzed photo, regardless of source (glasses or phone camera) — used
    // by Save. Distinct from GlassesUiState.lastPhoto, which only ever reflects a glasses
    // capture and was the cause of a bug where Save kept re-saving a stale glasses photo after
    // a phone-camera capture (which never touched GlassesManager's own state at all).
    val lastPhoto: Bitmap? = null,
)

class FoodLabelViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "FoodInspector_VM"
    }

    private val _ui = MutableStateFlow(AppUiState())
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    private val glasses = GlassesManager(application, viewModelScope)
    private val voiceManager = GlassesVoiceManager(application)

    /** Guards the listen -> handle -> speak -> listen loop; distinct from GlassesVoiceManager's
     *  own VoiceState, which tracks a single listen/speak turn, not whether the mode is on. */
    private var voiceModeOn = false
    private var glassesInitialized = false

    /** Cached from initializeGlasses()/connectGlasses() so the "connect glasses" voice command
     *  can (re)request permission and reconnect without needing an Activity reference of its
     *  own — handleVoiceCommand only ever runs after MainActivity has already wired one up. */
    private var permissionRequester: (suspend (Permission) -> PermissionStatus)? = null

    fun initializeGlasses(requestPermission: suspend (Permission) -> PermissionStatus) {
        permissionRequester = requestPermission
        if (glassesInitialized) return
        glassesInitialized = true

        glasses.initialize()
        viewModelScope.launch {
            glasses.state.collect { value ->
                _ui.update { it.copy(glasses = value) }
            }
        }
    }

    fun register(activity: Activity) = glasses.register(activity)

    fun connectGlasses(request: suspend (Permission) -> PermissionStatus) {
        permissionRequester = request
        viewModelScope.launch { performConnectGlasses(request) }
    }

    /** Shared by the manual Connect button (connectGlasses()) and the "connect glasses" voice
     *  command in handleVoiceCommand: requests camera permission if needed, then starts the DAT
     *  stream. Returns whether it actually connected so each caller can give its own feedback
     *  (UI-only vs. a spoken confirmation). */
    private suspend fun performConnectGlasses(request: suspend (Permission) -> PermissionStatus): Boolean {
        _ui.update { it.copy(busy = true, error = null, status = "Checking glasses permission...") }
        return try {
            if (glasses.ensureCameraPermission(request)) {
                glasses.startStream()
                true
            } else {
                _ui.update { it.copy(error = "Glasses camera permission denied") }
                false
            }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    fun disconnectGlasses() {
        glasses.stopStream()
    }

    fun toggleCloudMode() {
        _ui.update { it.copy(cloudMode = !it.cloudMode) }
    }

    /** Applies a new backend URL — previously this field was purely cosmetic (never wired to
     *  actual requests). Rejects anything that isn't a well-formed http(s) URL, silently keeping
     *  the last-valid one. A session id (and everything derived from it) from the old backend
     *  is meaningless against a different one, so switching resets that state too. */
    fun updateBackendUrl(url: String) {
        if (!isValidHttpUrl(url) || url == _ui.value.backendUrl) return
        _ui.update {
            it.copy(
                backendUrl = url,
                sessionId = null,
                analysisJson = null,
                foodCount = 0,
                lastQuestion = null,
                lastAnswer = null,
                conversationHistory = emptyList(),
            )
        }
    }

    fun setPreviewSurface(surface: android.view.Surface?) {
        glasses.setPreviewSurface(surface)
    }

    fun captureAndAnalyze() {
        viewModelScope.launch {
            performCaptureAndAnalyze()
            // Manual capture button: not part of the voice-mode listen loop, so speak once
            // and don't chain into listening — this only reads the result back, same as the
            // voice-triggered capture path already does.
            val state = _ui.value
            val summary = state.error?.let { "Capture failed: $it" }
                ?: "Found ${state.foodCount} food product${if (state.foodCount == 1) "" else "s"}."
            voiceManager.speak(summary)
        }
    }

    /** Does the actual capture + upload + analyze; awaitable so the voice-command loop can run
     *  it, inspect the resulting state, and speak a result before listening again. */
    private suspend fun performCaptureAndAnalyze() {
        _ui.update { it.copy(busy = true, error = null, status = "Capturing from glasses...") }

        val sid = try {
            ensureSession()
        } catch (e: Exception) {
            _ui.update { it.copy(busy = false, error = e.message ?: "Unable to create backend session") }
            return
        }

        val photoResult = glasses.capturePhoto()
        val bitmap = photoResult.getOrElse { e ->
            _ui.update {
                it.copy(busy = false, error = e.message ?: "Capture failed", status = "Capture failed")
            }
            return
        }

        val source = glasses.state.value.captureSource ?: "unknown source"
        uploadAndAnalyze(bitmap, sid, "glasses photo ($source)")
    }

    /** Phone-camera capture: same analyze pipeline as a glasses capture (same session, same
     *  BackendClient.analyzeImage call, same resulting state), just a different photo source —
     *  the phone's own camera app instead of glasses.capturePhoto(). */
    fun analyzePhoneCameraPhoto(bitmap: Bitmap) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null, status = "Capturing from phone camera...") }
            val sid = try {
                ensureSession()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, error = e.message ?: "Unable to create backend session") }
                return@launch
            }
            uploadAndAnalyze(bitmap, sid, "phone camera photo")
        }
    }

    /** Shared by the glasses-capture and phone-camera paths: uploads the photo for analysis and
     *  applies the resulting state exactly the same way regardless of where the photo came from. */
    private suspend fun uploadAndAnalyze(bitmap: Bitmap, sessionId: String, source: String) {
        val destination = if (_ui.value.cloudMode) "Ollama Cloud" else "local LLM"
        // Recorded here (before the upload, regardless of outcome) so Save always has the
        // actual most-recent capture, not whatever GlassesManager's own state last held.
        _ui.update { it.copy(lastPhoto = bitmap, status = "Sending $source to $destination...") }

        try {
            val result = withContext(Dispatchers.IO) {
                BackendClient(_ui.value.backendUrl).analyzeImage(sessionId, bitmap, _ui.value.cloudMode)
            }
            _ui.update {
                it.copy(
                    busy = false,
                    analysisJson = result.rawJson,
                    foodCount = result.foodCount,
                    status = "Analysis complete: ${result.foodCount} food product(s)",
                    // New photo = new product context, same as the backend resetting its own
                    // conversation on a fresh image — old Q&A no longer applies to what's saved.
                    conversationHistory = emptyList(),
                )
            }
        } catch (e: Exception) {
            _ui.update {
                it.copy(busy = false, error = e.message ?: "Image analysis failed", status = "Analysis failed")
            }
        }
    }

    private suspend fun ensureSession(): String {
        _ui.value.sessionId?.let { return it }
        val sid = withContext(Dispatchers.IO) {
            BackendClient(_ui.value.backendUrl).createSession()
        }
        _ui.update { it.copy(sessionId = sid) }
        return sid
    }

    // =====================================================================================
    // Hands-free voice mode: a continuous listen -> route -> act -> speak -> listen loop,
    // ported from InspectMeta's GlassesVoiceManager/inspection-mode pattern. No manual
    // start/stop-recording button — SpeechRecognizer's own silence detection ends each
    // utterance, and the recognized command runs immediately.
    // =====================================================================================

    fun toggleVoiceMode() {
        Log.d(TAG, "toggleVoiceMode: currently ${if (voiceModeOn) "ON -> turning off" else "OFF -> turning on"}")
        if (voiceModeOn) stopVoiceMode() else startVoiceMode()
    }

    private fun startVoiceMode() {
        voiceModeOn = true
        _ui.update { it.copy(voiceModeActive = true) }
        speakThenListen("Voice mode on. Say take a picture, or ask a question.")
    }

    fun stopVoiceMode() {
        voiceModeOn = false
        _ui.update { it.copy(voiceModeActive = false) }
        voiceManager.stopListening()
        voiceManager.speak("Voice mode off.")
    }

    /** Speak into the glasses, then (if voice mode is still on) reopen the microphone. */
    private fun speakThenListen(text: String) {
        Log.d(TAG, "speakThenListen: \"$text\"")
        voiceManager.speak(text) {
            Log.d(TAG, "speakThenListen: TTS onDone, voiceModeOn=$voiceModeOn")
            viewModelScope.launch {
                delay(250) // let SCO settle between TTS playback and mic capture
                if (voiceModeOn) listenOnce()
            }
        }
    }

    private fun listenOnce() {
        if (!voiceModeOn) {
            Log.d(TAG, "listenOnce: voice mode is off, not listening")
            return
        }
        Log.d(TAG, "listenOnce: starting SpeechRecognizer")
        voiceManager.startListening(
            onError = { message ->
                // No speech / timeout is normal between commands — reopen the mic after a beat
                // instead of dying silently. Backed off to avoid a tight loop.
                Log.w(TAG, "listenOnce: onError \"$message\", voiceModeOn=$voiceModeOn")
                viewModelScope.launch {
                    delay(1500)
                    if (voiceModeOn && voiceManager.voiceState.value !is VoiceState.Speaking) listenOnce()
                }
            }
        ) { transcript ->
            Log.d(TAG, "listenOnce: onResult transcript=\"$transcript\"")
            viewModelScope.launch {
                // The loop must survive ANY failure in command handling — a dead mic with no
                // feedback is the worst state to leave the user in. Report, speak, keep listening.
                try {
                    handleVoiceCommand(transcript)
                } catch (e: Exception) {
                    Log.e(TAG, "handleVoiceCommand threw", e)
                    _ui.update { it.copy(error = e.message ?: "Voice command failed") }
                    if (voiceModeOn) speakThenListen("Something went wrong. Try again.")
                }
            }
        }
    }

    private suspend fun handleVoiceCommand(transcript: String) {
        val isCaptureCommand = CAPTURE_VOICE_TRIGGERS.any { transcript.contains(it, ignoreCase = true) }
        val isSaveCommand = SAVE_VOICE_TRIGGERS.any { transcript.contains(it, ignoreCase = true) }
        val isStopVoiceCommand = STOP_VOICE_TRIGGERS.any { transcript.contains(it, ignoreCase = true) }
        val isStopGlassesCommand = STOP_GLASSES_TRIGGERS.any { transcript.contains(it, ignoreCase = true) }
        val isConnectGlassesCommand = CONNECT_GLASSES_TRIGGERS.any { transcript.contains(it, ignoreCase = true) }
        Log.d(TAG, "handleVoiceCommand: transcript=\"$transcript\" isCaptureCommand=$isCaptureCommand isSaveCommand=$isSaveCommand isStopVoiceCommand=$isStopVoiceCommand isStopGlassesCommand=$isStopGlassesCommand isConnectGlassesCommand=$isConnectGlassesCommand")

        if (isCaptureCommand) {
            _ui.update { it.copy(lastQuestion = transcript) }
            performCaptureAndAnalyze()
            val state = _ui.value
            val summary = state.error?.let { "Capture failed: $it" }
                ?: "Found ${state.foodCount} food product${if (state.foodCount == 1) "" else "s"}."
            Log.d(TAG, "handleVoiceCommand: capture result: $summary")
            if (voiceModeOn) speakThenListen(summary)
            return
        }

        if (isSaveCommand) {
            handleSaveCommand()
            return
        }

        if (isStopVoiceCommand) {
            Log.d(TAG, "handleVoiceCommand: stopping voice mode by voice command")
            stopVoiceMode()
            return
        }

        if (isStopGlassesCommand) {
            Log.d(TAG, "handleVoiceCommand: disconnecting glasses by voice command")
            disconnectGlasses()
            if (voiceModeOn) speakThenListen("Glasses disconnected.")
            return
        }

        if (isConnectGlassesCommand) {
            val request = permissionRequester
            if (request == null) {
                Log.w(TAG, "handleVoiceCommand: connect-glasses command but no permission requester registered yet")
                if (voiceModeOn) speakThenListen("Glasses aren't set up yet. Please connect from the app first.")
                return
            }
            Log.d(TAG, "handleVoiceCommand: connecting glasses by voice command")
            val connected = performConnectGlasses(request)
            if (voiceModeOn) speakThenListen(if (connected) "Glasses connected." else "Couldn't connect to the glasses.")
            return
        }

        try {
            val sid = ensureSession()
            val rawAnswer = withContext(Dispatchers.IO) {
                BackendClient(_ui.value.backendUrl).askQuestion(sid, transcript, _ui.value.cloudMode)
            }
            val answer = stripMarkdown(rawAnswer)
            Log.d(TAG, "handleVoiceCommand: askQuestion answer=\"$answer\"")
            _ui.update {
                it.copy(
                    lastQuestion = transcript,
                    lastAnswer = answer,
                    conversationHistory = it.conversationHistory + ConversationTurn(transcript, answer),
                    status = "Ready for the next question",
                )
            }
            if (voiceModeOn) speakThenListen(answer)
        } catch (e: Exception) {
            Log.e(TAG, "handleVoiceCommand: askQuestion failed", e)
            _ui.update { it.copy(error = e.message ?: "Voice request failed", status = "Voice request failed") }
            if (voiceModeOn) speakThenListen("Sorry, that request failed.")
        }
    }

    /** Voice-triggered local save ("save it", "keep evidence", etc.) — speaks the result on top
     *  of the shared save logic below, since this path runs inside the hands-free voice loop. */
    private suspend fun handleSaveCommand() {
        val outcome = performSave()
        if (!voiceModeOn) return
        when (outcome) {
            SaveOutcome.NOTHING_TO_SAVE -> speakThenListen("Nothing to save yet.")
            SaveOutcome.SUCCESS -> speakThenListen("Saved.")
            SaveOutcome.FAILURE -> speakThenListen("Sorry, saving failed.")
        }
    }

    /** Manual "Save" menu action — same save logic as the voice command, but no TTS since this
     *  is a direct UI tap, not part of the hands-free loop. Result is visible via `status`/`error`. */
    fun saveEvidence() {
        viewModelScope.launch { performSave() }
    }

    /** Placeholder for a future REST call to an external "SERIO" app/service — not implemented
     *  yet, deliberately just a status message and no network call. */
    fun sendToSerio() {
        _ui.update { it.copy(status = "Send to SERIO isn't implemented yet.") }
    }

    /** "Clear Session" menu action — resets everything back to how the app looks on a fresh
     *  launch: disconnects the glasses (a fresh connect will start a clean stream/session on
     *  the device side too), stops voice mode if it's running, and wipes every piece of
     *  session/analysis state. Deliberately keeps backendUrl/cloudMode, since those are user
     *  configuration, not session data. */
    fun clearSession() {
        if (voiceModeOn) stopVoiceMode()
        glasses.stopStream()
        _ui.update {
            it.copy(
                sessionId = null,
                busy = false,
                analysisJson = null,
                foodCount = 0,
                lastQuestion = null,
                lastAnswer = null,
                conversationHistory = emptyList(),
                lastPhoto = null,
                error = null,
                status = "Ready",
            )
        }
    }

    private enum class SaveOutcome { NOTHING_TO_SAVE, SUCCESS, FAILURE }

    /** Writes the current photo, analysis, and conversation so far to app-private storage.
     *  Entirely offline; no backend call, no manifest permission needed (see EvidenceSaver). */
    private suspend fun performSave(): SaveOutcome {
        val state = _ui.value
        val bitmap = state.lastPhoto
        if (bitmap == null && state.analysisJson == null) {
            Log.d(TAG, "performSave: nothing to save yet")
            return SaveOutcome.NOTHING_TO_SAVE
        }

        return try {
            val relativeFolder = withContext(Dispatchers.IO) {
                EvidenceSaver.save(getApplication(), bitmap, state.analysisJson, state.conversationHistory)
            }
            Log.d(TAG, "performSave: saved to $relativeFolder")
            _ui.update { it.copy(status = "Saved to $relativeFolder") }
            SaveOutcome.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "performSave: save failed", e)
            _ui.update { it.copy(error = e.message ?: "Save failed", status = "Save failed") }
            SaveOutcome.FAILURE
        }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    override fun onCleared() {
        glasses.stopStream()
        voiceManager.destroy()
        super.onCleared()
    }
}
