package com.foodinspector.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Ported from InspectMeta's GlassesVoiceManager: on-device SpeechRecognizer instead of
 *  record-to-file + server-side transcription, so listening ends on natural silence (VAD) with
 *  no manual "stop" step, and the recognized text is available immediately client-side. */
sealed class VoiceState {
    object Idle : VoiceState()
    data class Listening(val partialText: String = "") : VoiceState()
    data class Recognized(val text: String) : VoiceState()
    object Speaking : VoiceState()
    data class Error(val message: String) : VoiceState()
}

class GlassesVoiceManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val TAG = "FoodInspector_Voice"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    /** Local scope so startListening() can await the Bluetooth SCO link actually connecting
     *  before opening the recognizer, without forcing every caller up the chain to be suspend. */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _liveTranscribedText = MutableStateFlow("")
    val liveTranscribedText: StateFlow<String> = _liveTranscribedText.asStateFlow()

    private var onSpeechResultCallback: ((String) -> Unit)? = null
    private var onSpeechErrorCallback: ((String) -> Unit)? = null

    /** Per-utterance completion callbacks so callers can chain speak -> listen hands-free. */
    private val utteranceCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context, this)
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.d(TAG, "initSpeechRecognizer: recognition available, creating recognizer")
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer: Ready for speech")
                        _voiceState.value = VoiceState.Listening("")
                        _liveTranscribedText.value = ""
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                            else -> "Recognition error ($error)"
                        }
                        // BUSY means a previous session never released — cancel so the NEXT
                        // startListening (the voice-mode loop retries) can actually bind.
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            try { speechRecognizer?.cancel() } catch (_: Exception) {}
                        }
                        Log.e(TAG, "SpeechRecognizer error: $message")
                        _voiceState.value = VoiceState.Error(message)
                        _liveTranscribedText.value = ""
                        stopBluetoothSco()
                        onSpeechErrorCallback?.invoke(message)
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        Log.d(TAG, "SpeechRecognizer recognized final: $text")
                        if (text.isNotBlank()) {
                            _voiceState.value = VoiceState.Recognized(text)
                            _liveTranscribedText.value = text
                            onSpeechResultCallback?.invoke(text)
                        } else {
                            _voiceState.value = VoiceState.Idle
                            _liveTranscribedText.value = ""
                        }
                        stopBluetoothSco()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull() ?: ""
                        if (partial.isNotBlank()) {
                            _voiceState.value = VoiceState.Listening(partial)
                            _liveTranscribedText.value = partial
                            Log.d(TAG, "SpeechRecognizer partial: $partial")
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            // speechRecognizer stays null — every startListening() call below becomes a
            // silent no-op (no crash, no callback fires, ever). Most common cause on this
            // app's minSdk (30): a missing <queries> manifest declaration for
            // android.speech.RecognitionService hides the recognizer from PackageManager.
            Log.e(TAG, "initSpeechRecognizer: SpeechRecognizer.isRecognitionAvailable() returned false — no recognizer created")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            // Without this, TTS's AudioTrack defaults to USAGE_MEDIA/STREAM_MUSIC, which Android's
            // audio policy does NOT route over an active SCO link — only audio explicitly tagged
            // USAGE_VOICE_COMMUNICATION (STREAM_VOICE_CALL) is guaranteed to follow the SCO route.
            // Awaiting the SCO connection before speaking (see speak()) was necessary but not
            // sufficient by itself: the utterance was still playing out the phone's own speaker
            // the whole time, just correctly timed against a route it was never using.
            tts?.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _voiceState.value = VoiceState.Speaking
                }

                override fun onDone(utteranceId: String?) {
                    _voiceState.value = VoiceState.Idle
                    utteranceId?.let { utteranceCallbacks.remove(it)?.invoke() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _voiceState.value = VoiceState.Idle
                    utteranceId?.let { utteranceCallbacks.remove(it)?.invoke() }
                }
            })
            isTtsReady = true
        }
    }

    /** Start listening for voice input via the glasses' Bluetooth microphone.
     *
     *  Routing (`routeAudioToGlasses`) is awaited before the recognizer actually opens: on
     *  pre-API-31 devices `startBluetoothSco()` is asynchronous and typically takes several
     *  hundred ms to ~1-2s to actually connect. Starting the recognizer immediately after
     *  requesting it (the original bug here) opened the recognition window against dead air —
     *  it reliably reached "ready for speech" and then timed out with ERROR_NO_MATCH, because
     *  the SCO link wasn't actually up yet. */
    @SuppressLint("MissingPermission")
    fun startListening(onError: ((String) -> Unit)? = null, onResult: (String) -> Unit) {
        if (speechRecognizer == null) {
            Log.e(TAG, "startListening: speechRecognizer is null (recognition unavailable at init) — would silently hang forever, failing fast instead")
            _voiceState.value = VoiceState.Error("Speech recognition not available on this device")
            onError?.invoke("Speech recognition not available on this device")
            return
        }
        this.onSpeechResultCallback = onResult
        this.onSpeechErrorCallback = onError
        _liveTranscribedText.value = ""

        managerScope.launch {
            val routed = routeAudioToGlassesAndAwaitReady()
            Log.d(TAG, "startListening: audio route ready=$routed, opening recognizer")

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            try {
                // A stale session (screen rotation, prior error) leaves the recognizer BUSY;
                // cancel is a cheap no-op when idle and unwedges it when not.
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(intent)
                _voiceState.value = VoiceState.Listening("")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition", e)
                _voiceState.value = VoiceState.Error("Could not start speech recognition: ${e.message}")
            }
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            stopBluetoothSco()
            _voiceState.value = VoiceState.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition", e)
        }
    }

    /** Speak a response back into the glasses' speakers. `onDone` fires when the utterance
     *  finishes (or errors) — used to chain back into listening for the hands-free loop.
     *  Routing must be AWAITED before tts.speak() is called, not just fired off in the
     *  background: an AudioTrack binds to whatever output route is active when it starts, so
     *  if TTS begins while SCO is still connecting, the whole utterance keeps playing on the
     *  phone's default output for its entire duration even after SCO comes up moments later —
     *  this was silently swallowing every spoken reply. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isTtsReady) {
            onDone?.invoke()
            return
        }
        val utteranceId = "FoodLabelAnswer_${System.currentTimeMillis()}"
        if (onDone != null) utteranceCallbacks[utteranceId] = onDone
        managerScope.launch {
            val routed = routeAudioToGlassesAndAwaitReady()
            Log.d(TAG, "speak: audio route ready=$routed, speaking \"$text\"")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _voiceState.value = VoiceState.Idle
    }

    /** getAvailableCommunicationDevices()/setCommunicationDevice() are API 31+ only — this
     *  app's minSdk is 30, and calling them on an older device throws NoSuchMethodError at
     *  runtime (the app crashed on exactly this). Below API 31, fall back to the legacy
     *  startBluetoothSco()/isBluetoothScoOn API — which is asynchronous, so unlike the API 31+
     *  path this suspends until the SCO link actually reports connected (or a timeout), instead
     *  of just firing the request and hoping it's ready by the time we start listening. */
    private suspend fun routeAudioToGlassesAndAwaitReady(): Boolean {
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val glassesDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (glassesDevice != null) {
                    audioManager.setCommunicationDevice(glassesDevice)
                }
                true
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    true
                } else {
                    awaitBluetoothScoConnected()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error routing audio to glasses", e)
            false
        }
    }

    /** Requests the legacy Bluetooth SCO link and suspends until the system reports it actually
     *  connected (ACTION_SCO_AUDIO_STATE_UPDATED), instead of assuming it's ready immediately. */
    @Suppress("DEPRECATION")
    private suspend fun awaitBluetoothScoConnected(timeoutMs: Long = 3000): Boolean {
        val connected = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        Log.d(TAG, "awaitBluetoothScoConnected: state=$state")
                        when (state) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                                if (cont.isActive) cont.resume(true)
                            }
                            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    }
                }
                context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                cont.invokeOnCancellation { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
        if (connected == null) Log.w(TAG, "awaitBluetoothScoConnected: timed out after ${timeoutMs}ms")
        return connected ?: false
    }

    private fun stopBluetoothSco() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting audio routing", e)
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        stopBluetoothSco()
    }
}
