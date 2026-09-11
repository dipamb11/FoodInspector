package com.foodinspector.app

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.view.Surface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

data class GlassesUiState(
    val registration: String = "Unknown",
    val deviceCount: Int = 0,
    val streamState: String = "STOPPED",
    val isCapturing: Boolean = false,
    val lastPhoto: Bitmap? = null,
    val captureSource: String? = null,
    val status: String = "Glasses not initialized",
)

private data class CachedFrame(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val atMs: Long,
)

/** Plain class, not an AndroidViewModel: FoodLabelViewModel owns its lifecycle via viewModelScope
 *  so stopStream() actually runs when the owning ViewModel is cleared. */
class GlassesManager(
    private val app: Application,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "FoodInspector_Glasses"
        private const val CAPTURE_TIMEOUT_MS = 12_000L
        private const val FRAME_FRESHNESS_MS = 3_000L

        /** Caps how often a raw video frame gets JPEG-compressed for the on-screen preview.
         *  YuvImage.compressToJpeg logs an "OnFlyCompress" line per call, and doing it at the
         *  full 24fps stream rate flooded logcat; the preview looks identical to the eye at
         *  ~12fps, and every frame is still cached for capturePhoto()'s fallback regardless. */
        private const val PREVIEW_RENDER_INTERVAL_MS = 80L
    }

    private val _state = MutableStateFlow(GlassesUiState())
    val state: StateFlow<GlassesUiState> = _state.asStateFlow()

    private var initialized = false
    private var session: DeviceSession? = null
    private var camera: Camera? = null

    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var videoJob: Job? = null

    @Volatile private var cachedFrame: CachedFrame? = null
    @Volatile private var lastPreviewRenderMs: Long = 0L

    /** DeviceSessionState alone never explains why a session stopped (Meta's own lifecycle docs
     *  say so explicitly) — the reason only shows up on DeviceSession.errors, a separate stream. */
    @Volatile private var lastSessionError: String? = null

    private val surfaceLock = Any()
    private var previewSurface: Surface? = null

    /** Serializes capture with itself. */
    private val captureMutex = Mutex()

    /** UI-thread TextureView surface to mirror the live (uncompressed) glasses feed onto, purely
     *  for on-screen confirmation that frames are actually arriving — capture itself doesn't
     *  depend on this surface being set. */
    fun setPreviewSurface(surface: Surface?) {
        synchronized(surfaceLock) { previewSurface = surface }
    }

    fun initialize() {
        if (initialized) return
        initialized = true

        try {
            WearablesInit.ensure(app)
        } catch (e: Exception) {
            _state.update { it.copy(status = "DAT initialization failed: ${e.message}") }
            return
        }

        scope.launch {
            Wearables.registrationState.collect { registration ->
                _state.update { it.copy(registration = registration.toString()) }
            }
        }

        scope.launch {
            Wearables.devices.collect { devices ->
                _state.update {
                    it.copy(
                        deviceCount = devices.size,
                        status = if (devices.isNotEmpty())
                            "Glasses device available"
                        else
                            "Waiting for glasses",
                    )
                }
            }
        }
    }

    fun register(activity: Activity) {
        try {
            WearablesInit.ensure(activity)
            Wearables.startRegistration(activity)
            _state.update { it.copy(status = "Registration flow started") }
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
            _state.update { it.copy(status = "Registration failed: ${e.message}") }
        }
    }

    suspend fun ensureCameraPermission(request: suspend (Permission) -> PermissionStatus): Boolean {
        val current = Wearables.checkPermissionStatus(Permission.CAMERA)
            .onFailure { error ->
                _state.update { it.copy(status = "Permission check failed: $error") }
            }
            .getOrNull()

        if (current == PermissionStatus.Granted) return true

        return request(Permission.CAMERA) == PermissionStatus.Granted
    }

    fun startStream() {
        val existing = session
        if (existing != null) {
            when (existing.state.value) {
                DeviceSessionState.STARTED -> {
                    if (camera == null) attachCamera(existing)
                    return
                }
                DeviceSessionState.PAUSED -> {
                    // Lifecycle docs: never restart a paused session — the device resumes it on
                    // its own when donned / unfolded.
                    _state.update { it.copy(status = "Glasses paused — put them on / unfold temples to resume") }
                    return
                }
                DeviceSessionState.STARTING -> return
                else -> {
                    // STOPPED / STOPPING / IDLE: the session died under us — drop it and recreate.
                    session = null
                    camera = null
                }
            }
        }

        // A brand-new session is about to be created: cancel every collector from whatever
        // session/camera generation came before, so nothing stale can keep writing to shared
        // fields (camera, cachedFrame) alongside the new generation's own writes.
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        streamStateJob?.cancel()
        streamErrorJob?.cancel()
        videoJob?.cancel()
        cachedFrame = null

        lastSessionError = null
        _state.update { it.copy(streamState = "STARTING", status = "Connecting to glasses camera...") }

        Wearables.createSession(AutoDeviceSelector()).onSuccess { newSession ->
            session = newSession

            // Collectors attached before start() per docs — the error stream is the only place
            // the *reason* for a STOPPED/IDLE transition ever shows up (e.g. glasses firmware
            // needing an update, the DAT companion app on the glasses being unreachable).
            sessionErrorJob?.cancel()
            sessionErrorJob = scope.launch {
                newSession.errors.collect { error ->
                    Log.e(TAG, "DeviceSession error: $error (${error.description})")
                    lastSessionError = error.description
                    _state.update { it.copy(status = "Session error: ${error.description}") }
                }
            }

            sessionStateJob?.cancel()
            sessionStateJob = scope.launch {
                newSession.state.collect { sessionState ->
                    Log.d(TAG, "DeviceSession state: $sessionState")
                    when (sessionState) {
                        DeviceSessionState.STARTED -> {
                            lastSessionError = null
                            attachCamera(newSession)
                        }
                        DeviceSessionState.PAUSED -> {
                            _state.update { it.copy(status = "Glasses paused (case / temples folded)") }
                        }
                        DeviceSessionState.STOPPING, DeviceSessionState.STOPPED, DeviceSessionState.IDLE -> {
                            camera = null
                            cachedFrame = null
                            val reason = lastSessionError
                            _state.update {
                                it.copy(
                                    streamState = "STOPPED",
                                    status = if (reason != null) "Glasses disconnected: $reason" else "Glasses disconnected",
                                )
                            }
                        }
                        DeviceSessionState.STARTING -> {
                            _state.update { it.copy(status = "Glasses connecting...") }
                        }
                    }
                }
            }

            newSession.start()
        }.onFailure { error ->
            Log.e(TAG, "createSession error: $error")
            val message = when {
                error.toString().contains("already exists", ignoreCase = true) ->
                    "Glasses are releasing a previous session — retry in ~10s"
                error.toString().contains("No eligible device", ignoreCase = true) ->
                    "Glasses sleeping. Put glasses on or unfold temples."
                else -> "Session error: $error"
            }
            _state.update { it.copy(streamState = "STOPPED", status = message) }
        }
    }

    private fun attachCamera(session: DeviceSession) {
        if (camera != null) return

        val config = StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)
        Log.d(TAG, "addCamera: requesting stream (videoQuality=MEDIUM, frameRate=24, compressVideo=false)")
        session.addCamera(config).onSuccess { cam ->
            Log.d(TAG, "addCamera: succeeded, camera added")
            camera = cam

            streamStateJob?.cancel()
            streamStateJob = scope.launch {
                // Collectors are attached before cam.stream.start() per docs, so the very first
                // value this Flow delivers is the stream's pre-start resting state — almost
                // certainly STOPPED, since it hasn't started yet. Only treat STOPPED/CLOSED as a
                // real teardown signal once the stream has actually reached an active state at
                // least once; otherwise the very first (pre-start) STOPPED emission would tear
                // the session down before start() ever got a chance to run.
                var reachedActive = false
                cam.stream.state.collect { streamState ->
                    Log.d(TAG, "Camera stream state: $streamState")
                    _state.update { it.copy(streamState = streamState.toString(), status = "Camera: $streamState") }
                    if (streamState != StreamState.STOPPED && streamState != StreamState.CLOSED) {
                        reachedActive = true
                    } else if (reachedActive) {
                        // Full teardown, not just nulling `camera` — matches the official Android
                        // guide's Step 7 (`if (state == StreamState.STOPPED) { stopStream() }`).
                        // A partial reset previously left `videoJob` alive bound to this same
                        // `cam` object; if the stream transiently reported STOPPED and then
                        // self-healed back to STREAMING on the same object (a Bluetooth blip, a
                        // quality renegotiation), that zombie job kept feeding frames to the
                        // preview while `camera` stayed null forever — the preview looked fine
                        // but capturePhoto() always failed with "not streaming". Self-cancellation
                        // (this coroutine IS streamStateJob, one of the jobs stopStream() cancels)
                        // is safe here for the same reason it is in the session-state collector.
                        stopStream()
                    }
                }
            }

            streamErrorJob?.cancel()
            streamErrorJob = scope.launch {
                cam.stream.errorStream.collect { streamErr ->
                    Log.e(TAG, "Camera stream error: $streamErr (${streamErr.description})")
                    _state.update { it.copy(status = "Camera: ${streamErr.description}") }
                }
            }

            // Collectors attached before start() per docs. Plain collect (not collectLatest) so
            // a frame arriving mid-copy isn't dropped.
            videoJob?.cancel()
            videoJob = scope.launch(Dispatchers.Default) {
                var loggedFirstFrame = false
                cam.stream.videoStream.collect { frame ->
                    if (!loggedFirstFrame) {
                        loggedFirstFrame = true
                        Log.d(TAG, "First video frame: isCompressed=${frame.isCompressed}, ${frame.width}x${frame.height}")
                    }
                    if (!frame.isCompressed && frame.width > 0 && frame.height > 0) {
                        val buffer = frame.buffer
                        val bytes = ByteArray(buffer.remaining())
                        val position = buffer.position()
                        buffer.get(bytes)
                        buffer.position(position)
                        val now = System.currentTimeMillis()
                        cachedFrame = CachedFrame(bytes, frame.width, frame.height, now)
                        if (now - lastPreviewRenderMs >= PREVIEW_RENDER_INTERVAL_MS) {
                            lastPreviewRenderMs = now
                            renderToPreviewSurface(bytes, frame.width, frame.height)
                        }
                    }
                }
            }

            val startResult = cam.stream.start()
            Log.d(TAG, "cam.stream.start() result: $startResult")
        }.onFailure { error ->
            Log.e(TAG, "addCamera error: $error")
            _state.update { it.copy(status = "Camera error: $error") }
        }
    }

    /** Draws a raw YUV frame onto the live preview surface, letterboxed to fit. Best-effort:
     *  a missing/invalid surface (no TextureView attached right now) just skips the frame. */
    private fun renderToPreviewSurface(nv21: ByteArray, width: Int, height: Int) {
        val surface = synchronized(surfaceLock) { previewSurface } ?: return
        if (!surface.isValid) return
        try {
            val bitmap = nv21ToBitmap(nv21, width, height) ?: return
            val canvas = surface.lockCanvas(null) ?: return
            try {
                val scale = minOf(canvas.width.toFloat() / width, canvas.height.toFloat() / height)
                val dx = (canvas.width - width * scale) / 2f
                val dy = (canvas.height - height * scale) / 2f
                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postTranslate(dx, dy)
                }
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, matrix, null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to render preview frame", e)
        }
    }

    /**
     * Captures a fresh photo from the glasses. Follows the official DAT Android guide's Step 8
     * directly: call `stream.capturePhoto()` and branch on the result — no home-grown "is it
     * streaming yet" pre-check. An earlier version tried to predict readiness itself via a
     * hand-maintained state field, which desynced from the SDK's own state in practice; the SDK's
     * own `CaptureError.NotStreaming` (surfaced below via `errorOrNull()?.description`) is a more
     * reliable readiness signal than anything we can compute ourselves. Falls back to the latest
     * cached uncompressed preview frame if the hardware shutter fails (a fallback that only
     * exists because `StreamConfiguration` here uses `compressVideo = false`, i.e. raw YUV frames
     * rather than HEVC — a compressed stream has no such cache to fall back to).
     */
    suspend fun capturePhoto(): Result<Bitmap> = captureMutex.withLock {
        val cam = camera
        if (cam == null) {
            Log.w(TAG, "capturePhoto: no active camera")
            return@withLock Result.failure(
                IllegalStateException("Glasses camera not streaming — connect first.")
            )
        }

        _state.update { it.copy(isCapturing = true, status = "Capturing photo from glasses...") }
        try {
            Log.d(TAG, "capturePhoto: invoking cam.stream.capturePhoto() (up to ${CAPTURE_TIMEOUT_MS}ms)")
            val photoResult = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { cam.stream.capturePhoto() }
            var hardwareFailure = "timed out after ${CAPTURE_TIMEOUT_MS}ms"
            if (photoResult != null && photoResult.isSuccess) {
                val bitmap = withContext(Dispatchers.Default) { decodePhotoData(photoResult.getOrNull()) }
                if (bitmap != null && !isLikelyBlank(bitmap)) {
                    Log.d(TAG, "capturePhoto: hardware photo decoded (${bitmap.width}x${bitmap.height})")
                    _state.update {
                        it.copy(
                            isCapturing = false,
                            lastPhoto = bitmap,
                            captureSource = "hardware photo",
                            status = "Photo captured from glasses",
                        )
                    }
                    return@withLock Result.success(bitmap)
                }
                hardwareFailure = if (bitmap == null)
                    "returned success but decoded to null (photoData=${photoResult.getOrNull()})"
                else
                    "decoded but looked blank/near-uniform"
                Log.w(TAG, "capturePhoto: hardware $hardwareFailure")
            } else if (photoResult != null) {
                hardwareFailure = "failed: ${photoResult.errorOrNull()?.description ?: "unknown error"}"
                Log.w(TAG, "capturePhoto: hardware $hardwareFailure")
            } else {
                Log.w(TAG, "capturePhoto: hardware $hardwareFailure")
            }

            val frame = cachedFrame
            val fallbackFailure: String
            if (frame == null) {
                fallbackFailure = "no cached preview frame arrived yet"
            } else {
                val ageMs = System.currentTimeMillis() - frame.atMs
                if (ageMs >= FRAME_FRESHNESS_MS) {
                    fallbackFailure = "cached frame is stale (${ageMs}ms old, limit ${FRAME_FRESHNESS_MS}ms)"
                } else {
                    val fallback = withContext(Dispatchers.Default) { nv21ToBitmap(frame.nv21, frame.width, frame.height) }
                    if (fallback == null) {
                        fallbackFailure = "YUV-to-JPEG conversion of the cached frame failed"
                    } else if (isLikelyBlank(fallback)) {
                        fallbackFailure = "cached frame looked blank/near-uniform"
                    } else {
                        Log.d(TAG, "capturePhoto: preview-frame fallback used (${fallback.width}x${fallback.height}, ${ageMs}ms old)")
                        _state.update {
                            it.copy(
                                isCapturing = false,
                                lastPhoto = fallback,
                                captureSource = "preview frame",
                                status = "Captured from preview frame (hardware shutter timed out)",
                            )
                        }
                        return@withLock Result.success(fallback)
                    }
                }
            }

            Log.e(TAG, "capturePhoto: both paths failed — hardware: $hardwareFailure; fallback: $fallbackFailure")
            _state.update { it.copy(isCapturing = false, status = "Photo capture failed") }
            Result.failure(Exception("Hardware capture $hardwareFailure. Preview fallback: $fallbackFailure."))
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture exception", e)
            _state.update { it.copy(isCapturing = false, status = "Photo capture exception: ${e.message}") }
            Result.failure(e)
        }
    }

    private fun decodePhotoData(photoData: PhotoData?): Bitmap? = when (photoData) {
        is PhotoData.Bitmap -> photoData.bitmap
        is PhotoData.HEIC -> {
            val bytes = ByteArray(photoData.data.remaining())
            photoData.data.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        else -> null
    }

    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? = try {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val jpegBytes = out.toByteArray()
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to convert cached preview frame", e)
        null
    }

    /** Cheap blank-image detector: a near-black or near-uniform "capture" fed to the vision
     *  model makes it hallucinate a scene, which is worse than an honest failure. */
    private fun isLikelyBlank(bitmap: Bitmap): Boolean {
        if (bitmap.width < 8 || bitmap.height < 8) return true
        val steps = 8
        var sumLuma = 0L
        var minLuma = 255
        var maxLuma = 0
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = (bitmap.width - 1) * i / (steps - 1)
                val y = (bitmap.height - 1) * j / (steps - 1)
                val p = bitmap.getPixel(x, y)
                val luma = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                sumLuma += luma
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma
            }
        }
        val avg = sumLuma / (steps * steps)
        val spread = maxLuma - minLuma
        return avg < 10 || spread < 6
    }

    fun stopStream() {
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        streamStateJob?.cancel()
        streamErrorJob?.cancel()
        videoJob?.cancel()
        sessionStateJob = null
        sessionErrorJob = null
        streamStateJob = null
        streamErrorJob = null
        videoJob = null
        lastSessionError = null

        camera?.stop()
        session?.stop()
        camera = null
        session = null
        cachedFrame = null

        _state.update { it.copy(streamState = "STOPPED", status = "Glasses disconnected") }
    }
}
