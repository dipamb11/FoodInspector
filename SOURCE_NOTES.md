# Source-grounding notes

Backend facts used:

- Vision model: `ministral-3:3b`
- Text model: `minicpm-v4.6:1b`
- Whisper: `small`, CPU, int8
- Session endpoint: `/session`
- Image endpoint: `/session/{session_id}/image`
- Voice endpoint: `/session/{session_id}/voice`
- Image multipart field: `file`
- Voice multipart field: `audio`
- Multi-food response shape: `{ "foods": [...] }`
- Voice response fields: `transcription` and `response`
- 2026-09-03: `backend/main_server.py`'s `/session/{session_id}/voice` originally
  hard-rejected any request (400) until an image had been analyzed for that
  session, which blocked the Android app's spoken capture-trigger phrases
  (`CAPTURE_VOICE_TRIGGERS` in `FoodLabelViewModel.kt`) from ever being the
  first action — the request failed before transcription even ran, so the
  phrase could never be checked. Since `build_system_message(None)` already
  produces a graceful "no product image has been analyzed yet" system prompt,
  the gate was more a UX choice than a technical requirement. Fixed by seeding
  `session["messages"]` with that system message at `create_session()` time
  (rather than only after `/session/{id}/image` runs) and removing the 400
  check in `session_voice_command`. The browser UI at `/` is unaffected — its
  own `recordButton` stays client-side disabled until an image is analyzed,
  independent of this backend-level change.

DAT facts used from the supplied Android app, updated for DAT SDK **0.9.0** (the
0.4.0 `StreamSession` / `startStreamSession` API was removed upstream; verified
against `InspectMeta/android`'s compiling `MetaGlassesManager.kt` and against the
decompiled `mwdat-core`/`mwdat-camera` 0.9.0 AARs):

- `Wearables.initialize(context.applicationContext)` -> `DatResult<Unit, WearablesError>`
- `Wearables.startRegistration(activity)`
- `Wearables.RequestPermissionContract()` -> `DatResult<PermissionStatus, PermissionError>`
- `Wearables.checkPermissionStatus(Permission.CAMERA)` (suspend) -> `DatResult<PermissionStatus, PermissionError>`
- `AutoDeviceSelector()`
- `Wearables.createSession(selector)` -> `DatResult<DeviceSession, DeviceSessionError>`
- `DeviceSession.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24))`
  -> `DatResult<Camera, DeviceSessionError>` (extension function; `compressVideo`
  defaults to `false`, i.e. raw YUV frames, which is what makes the preview-frame
  capture fallback possible)
- `Camera.stream.start()`, `Camera.stream.state`, `Camera.stream.errorStream`,
  `Camera.stream.videoStream`
- `Stream.capturePhoto()` (suspend) -> `DatResult<PhotoData, CaptureError>` — the
  **only** capture path available to third-party apps. As of this SDK preview,
  apps cannot intercept "Hey Meta" voice commands or the hardware capture
  button/gesture at all (confirmed against Meta's own DAT repo discussions) —
  those events stay entirely in Meta AI's first-party domain. A capture can
  only ever be app-initiated (tapping the in-app Capture button).
- `PhotoData.Bitmap`
- `PhotoData.HEIC`
- `DatResult` usage proven by the compiling 0.9.0 code: `.onSuccess { }`,
  `.onFailure { error -> }` (single-arg overload binds `error: Throwable`, not
  the typed `E` — the typed error surfaces separately, e.g. `StreamError.description`
  on `errorStream` items), `.isSuccess`, `.getOrNull()`.
- DAT dependency coordinates/version from the supplied Gradle catalog:
  `com.meta.wearable:mwdat-core:0.9.0`
  `com.meta.wearable:mwdat-camera:0.9.0`
  `com.meta.wearable:mwdat-mockdevice:0.9.0` (debug-only)
- `startStream()` cancels every collector job from the previous generation before
  creating a new session, matching `InspectMeta`'s `MetaGlassesManager.restartSession()`
  -> `disconnect()`-before-recreate pattern — an earlier bug let a stale job from a
  prior generation keep writing to shared fields (`camera`, `cachedFrame`) alongside a
  newer generation's own writes.
  2026-09-02: attempted consolidating all `camera`/`session` nulling into `stopStream()`
  alone (mirroring `CameraAccessAndroid`'s `StreamViewModel`), on the theory that a
  transient `StreamState.STOPPED` blip was incorrectly tearing down a still-live camera.
  That change broke Connect entirely and was reverted.
- `capturePhoto()` was rewritten to match the official Android integration guide's
  Step 8 (`wearables.developer.meta.com/docs/develop/dat/build-integration-android`)
  exactly: call `stream.capturePhoto()` directly and branch on the `DatResult`, with
  no pre-flight "is it streaming yet" check. An earlier version tried to predict
  readiness itself (first via a hand-maintained `currentStreamState` field that
  desynced from the SDK's own state on real hardware, then via a `camera?.stream
  ?.state?.value` live-read wrapped in a polling `awaitStreaming()` loop) — both were
  unnecessary complexity the docs don't have: `CaptureError.NotStreaming` (surfaced via
  `errorOrNull()?.description`) is a more reliable readiness signal than anything
  computed independently, since the SDK is the actual source of truth. Also per the
  same guide's Step 7 snippet (`if (state == StreamState.STOPPED) { stopStream() }`),
  `StreamState.STOPPED` is confirmed as a terminal signal, not a transient one — so the
  session-state collector's STOPPED/STOPPING/IDLE branch still nulls `camera`/
  `cachedFrame` locally, and the stream-state collector's STOPPED/CLOSED branch now
  calls the *full* `stopStream()` rather than nulling `camera` alone.
  2026-09-02, root cause finally confirmed live-traced: the stream-state collector was
  nulling only `camera`/`cachedFrame` on STOPPED/CLOSED, never cancelling `videoJob` —
  which closes over the local `cam` value, not the `camera` field. If the stream
  transiently reported STOPPED and then self-healed back to STREAMING on the *same*
  underlying `Camera`/`Stream` object (a Bluetooth blip, a quality renegotiation),
  the zombie `videoJob` kept feeding real frames to the live preview from that still-
  alive object the whole time, while `camera` never got set back to non-null (nothing
  ever did) — so the preview looked perfectly live while `capturePhoto()` permanently
  hard-failed with "not streaming — connect first". Calling the full `stopStream()` on
  STOPPED/CLOSED (matching the doc's Step 7 exactly) cancels `videoJob` too, so no
  zombie collector can survive a stream-level stop.
  Immediate regression from that fix: collectors are attached to `cam.stream.state`
  *before* `cam.stream.start()` (per docs), so the very first value the StateFlow
  delivers is its pre-start resting value — STOPPED, since it hasn't started yet.
  Calling `stopStream()` unconditionally on that first STOPPED tore the whole session
  down before `start()` ever ran, breaking Connect outright. Fixed by only treating
  STOPPED/CLOSED as a real teardown signal once the stream has reached a non-stopped
  state at least once (a local `reachedActive` flag in the collector) — the pre-start
  default is now ignored, later genuine stops are not.
- A live preview (`GlassesManager.setPreviewSurface` + `MainActivity`'s `TextureView`)
  renders the raw YUV frames directly (no HEVC decoder needed, since `compressVideo =
  false` already hands the app decoded frames) — added both for on-screen confirmation
  that frames are flowing and because it was the one piece of `InspectMeta`'s flow
  (its `GlassesStreamRenderer`) genuinely worth porting; neither app can react to "Hey
  Meta" voice commands or the hardware capture button, confirmed unavailable to
  third-party DAT apps in this SDK preview.
- Confirmed a second time (2026-09-03) directly against the downloaded
  `facebook/meta-wearables-dat-android` source, and a third time against the
  live official docs the user linked directly
  (`docs/reference/android/dat/0.9`, `build-integration-android.md`): only 4
  public modules exist (`mwdat-core`, `mwdat-camera`, `mwdat-display`,
  `mwdat-mockdevice`), the `Permission` enum has exactly one real value
  (`CAMERA`), and "captouch" only exists inside `MockDeviceKit` as a test
  simulation that flips a *mock* `StreamState` between paused/resumed — none
  of it is a real-device input hook. Since a hands-free trigger genuinely
  isn't available from the SDK, a spoken capture command was added on top of
  a voice pipeline instead: if the transcript matches `CAPTURE_VOICE_TRIGGERS`
  (e.g. "take a picture"), `FoodLabelViewModel.handleVoiceCommand()` calls
  `captureAndAnalyze()` instead of treating the transcript as an LLM question.
- 2026-09-08: replaced the original record-button-driven voice flow (tap Start
  Recording -> tap Stop & Ask -> upload audio -> server-side Whisper) with a
  continuous hands-free loop, ported from `InspectMeta`'s
  `com.openglasses.ai.audio.GlassesVoiceManager` + its inspection-mode pattern
  in `MainViewModel.kt`. New `GlassesVoiceManager.kt` uses Android's on-device
  `SpeechRecognizer` (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`) instead of
  `MediaRecorder` — it ends each utterance on natural silence (VAD) with no
  manual stop step, and delivers recognized text directly, so the old
  `VoiceRecorder.kt` (record-to-file for server-side transcription) is deleted
  as unused. `FoodLabelViewModel` now drives a continuous
  `listenOnce() -> handleVoiceCommand() -> speakThenListen() -> listenOnce()`
  loop while `voiceModeOn`, matching `InspectMeta`'s
  `listenOnce()`/`handleVoiceCommand()`/`speakThenListen()` naming and
  structure exactly. Since recognition now happens on-device, the backend no
  longer needs to see raw audio for a normal question: added
  `POST /session/{id}/ask` (JSON `{"question": ...}`), a text-only counterpart
  to `/voice` sharing the same `run_llm_chat()` helper, and `BackendClient
  .askQuestion()` replaces the removed `sendVoice()`/`BackendVoiceResponse`.
  `/voice` (audio upload) is left in place for the `/glasses/voice` bridge
  endpoint and any other client that still wants server-side transcription.
  This also required replacing the old hard requirement for an existing
  `sessionId` before any voice request with the same `ensureSession()` helper
  `captureAndAnalyze()` already uses (both in the ask endpoint's caller and,
  earlier, in `/voice` itself via seeding `session["messages"]` at
  `create_session()` time) — otherwise "take a picture" could never be the
  very first action, since the backend would 400 before transcription/routing
  ever ran.
  Live-crashed on first real-device test: `GlassesVoiceManager.routeAudioToGlasses()`
  (ported verbatim from `InspectMeta`) called `AudioManager
  .getAvailableCommunicationDevices()`/`.setCommunicationDevice()`, which are
  API 31+ only — `@SuppressLint("NewApi")` silences the lint warning but adds
  no runtime guard, and this app's `minSdk` is 30, so it threw
  `NoSuchMethodError` on any pre-Android-12 device (fatal, killed the process
  the instant "Start Voice Mode" was tapped). `InspectMeta` likely never hit
  this because it was only ever run on an API 31+ test device. Fixed with an
  actual `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` check, falling back
  to the legacy `startBluetoothSco()`/`isBluetoothScoOn` API below API 31
  (`routeAudioToGlasses()`/`stopBluetoothSco()`) instead of just skipping
  routing on older devices.
  Second real-device issue after that fix: voice mode no longer crashed, but
  never responded to anything — no `onReadyForSpeech`, no `onError`, no
  `onResults`, ever, for either "take a picture" or a plain question.
  Diagnostic logging traced it to `speechRecognizer` staying `null`: `AndroidManifest.xml`
  had no `<queries>` element, and on this app's `minSdk` (30, i.e. Android
  11+), package-visibility filtering hides the `android.speech
  .RecognitionService` from `PackageManager` unless the manifest explicitly
  declares it can see that intent action — so `SpeechRecognizer
  .isRecognitionAvailable(context)` returned `false` at `GlassesVoiceManager`
  init, and every subsequent `startListening()` call on a null recognizer was
  a total no-op (no crash, no callback, no log — the silence that made this
  hard to diagnose without adding logging first). Fixed by adding
  `<queries><intent><action android:name="android.speech.RecognitionService" />
  </intent></queries>` to the manifest, plus `GlassesVoiceManager` now logs
  and fails fast (fires `onError` immediately with a clear message) instead
  of silently hanging if the recognizer is ever null when `startListening()`
  is called, so this exact failure mode can never be silent again.
- Manifest attestation meta-data, confirmed against `facebook/meta-wearables-dat-android`'s
  README/AGENTS.md (not just the supplied sample, which only carried
  `APPLICATION_ID`): both `com.meta.wearable.mwdat.APPLICATION_ID` and
  `com.meta.wearable.mwdat.CLIENT_TOKEN` are required, and `"0"`/`"0"` is the
  documented Developer Mode pair (attestation is skipped when both are `0`).
  Developer Mode must additionally be enabled on the glasses in the Meta AI
  app for the `0`/`0` pair to attest at all; without it, registration reports
  `UNAVAILABLE` indefinitely and `Wearables.devices` stays empty.

Audio implementation:

Originally native `MediaRecorder` produced an AAC/MPEG-4 file uploaded to the
backend's `/voice` endpoint for server-side Whisper transcription. As of
2026-09-08 the primary voice path (see above) instead uses Android's
on-device `SpeechRecognizer`, so `MediaRecorder`/`VoiceRecorder.kt` is no
longer used by the app; `/voice`'s audio-upload contract is preserved
backend-side for the `/glasses/voice` bridge endpoint.

No Gemini SDK or Gemini API is included.

## 2026-09-08 onward: speed, cloud mode, FDA matching, local save, UI redesign, phone camera

**CPU-latency work (backend).** The original single-call vision pipeline (image straight to
`ministral-3:3b`, full structured JSON out) took 2-3 minutes per analysis on this GPU-less
machine. Three independent mitigations were added rather than one silver bullet: (1)
`OLLAMA_KEEP_ALIVE = "30m"` on every `chat()` call, since Ollama's ~5min default unload meant
most requests paid a full weight-load cost; (2) a `@app.on_event("startup")` warm-up that fires
one throwaway call per model the active pipeline uses, moving that cold-load cost to server
boot instead of the user's first real request; (3) a new `PIPELINE_MODE = "ocr_first"` option —
a dedicated OCR model (`glm-ocr:bf16`) transcribes text first, then a small text model
structures it, since image tokens (not text tokens) were most of the CPU cost. `ocr_first`
introduced its own failure mode: a real glasses photo (blur/glare/off-angle) can leave OCR with
nothing to transcribe, silently producing a validly-shaped-but-empty result. Fixed by falling
back to `vision_direct` automatically when OCR text is under 15 characters. `PIPELINE_MODE` was
later left at `"vision_direct"` (the default as of this writing) after further testing; both
remain available.

**Ollama Cloud integration (`cloud_server.py`, new file).** Added a `backend_mode` field
(`"local"`/`"cloud"`) threaded through `/session/{id}/image`, `/ask`, and `/voice`, an Android
`cloudMode` toggle (now in Settings), and `cloud_server.py` — same `ollama` Python client and
`/api/chat` shape as the local calls, just pointed at `https://ollama.com` with an API key
(`OLLAMA_CLOUD_API_KEY`, or the `OLLAMA_API_KEY` env var, which takes precedence). Real-device
testing against a live cloud model (`gemma4:31b`) surfaced three separate JSON-shape bugs the
local grammar-constrained models never hit:
1. The model wrapped its JSON reply in a ` ```json ... ``` ` markdown fence despite the
   `format` schema constraint — fixed with `strip_json_code_fence()`.
2. Even after that, some responses still failed validation because the model added prose
   before/after the JSON ("Here's the analysis:\n{...}\nLet me know...") — fixed more generally
   with `extract_json_payload()`, a string-aware bracket-depth scanner that extracts exactly
   the top-level JSON value regardless of what surrounds it (handles braces embedded inside
   quoted ingredient text correctly, unlike a naive brace-counter).
3. The model sometimes returned a bare JSON array (`[ {...}, {...} ]`) instead of the schema's
   required `{"foods": [...]}` object — same valid per-item data, wrong envelope. Fixed with
   `normalize_multi_food_json()`, which re-wraps a bare array before validation instead of
   rejecting an otherwise-usable response.
All three are applied in that order to every raw model response, regardless of pipeline, since
a cloud model can misbehave in any of these ways independent of which local pipeline mode is
configured. Cloud mode was also switched to upload the **original, uncompressed** image
(`original_path`) rather than the resized/recompressed `processed_path` used for local models —
that downscaling exists purely to keep CPU cost down and doesn't apply to a hosted GPU, and was
losing label detail the cloud model could otherwise read.

Separately, error responses were tightened: a 502 from a JSON-validation failure used to return
the entire `raw_model_response` in the HTTP detail, which the Android client then displayed/spoke
verbatim (a phone screen or TTS reading out a full JSON schema dump is not useful). Fixed on
both ends — backend logs the full response server-side but returns only
`{"message": "Image analysis failed.", "pipeline_mode": ...}`, and `BackendClient.kt` logs the
full body via `Log.e` but throws a short exception message.

**FDA product-code matching (`fda_matcher.py`, `fda_food_codes.json`, new files).** Requested as
a "grounded candidate-generation, never invention" feature per a user-supplied usage guide.
Research into an authoritative FDA industry-code list came up short — the real Product Code
Builder is an interactive web tool with no fetchable full table — so the reference table
(`fda_food_codes.json`) was seeded only with entries directly quoted from real FDA Import Alert
detention-list pages (99-19, 99-39, 45-02), not from search-engine-synthesized guesses, and the
architecture was built to degrade safely (empty/missing table = safe no-op, never a fabricated
code) so real entries can be added incrementally.
Architecture: a **post-processing step** over the already-structured `analysis_dict`, not a
change to the vision/OCR extraction prompts — `find_candidates()` does deterministic
alias-matching (no LLM, instant), and only if candidates are found does `rank_candidates()` make
one small LLM call to pick among them (or decline). Two bugs found during testing:
1. Using the full result shape (nested arrays, several types: `candidate_codes`, `evidence`,
   `missing_evidence`, `needs_verification`) directly as the ranking LLM's requested schema
   broke down with the local `TEXT_MODEL` (`minicpm-v4.6:1b`) — it reliably garbled the JSON,
   stuffing most of its answer into the first string field instead of separate keys, even after
   removing markdown/prose from the prompt. Root cause was model-specific, not prompt-specific:
   the exact same prompt/schema worked cleanly against `VISION_MODEL` (`ministral-3:3b`). Fixed
   by (a) using a much smaller internal `_RankingResult` schema (`selected_code`, `confidence`,
   one `reasoning` string) for the LLM call, assembling the richer public `FdaMatch` in code
   from that plus the already-known candidate list, and (b) switching the ranking model to
   `FDA_RANKING_MODEL = VISION_MODEL` instead of `TEXT_MODEL`, confirmed reliable via direct
   side-by-side testing against real Ollama calls.
2. Naive substring matching in `find_candidates()` let short aliases spuriously match inside
   unrelated words — e.g. the alias "cola" is a literal substring of "ch**ocola**te", so a
   product merely called "chocolate" spuriously candidate-matched the cola entry. Fixed with
   word-boundary regex matching (`\bcola\b`) instead of plain `in` containment; verified this
   removes the false positive while preserving legitimate single-word alias matches.
Per a later request, the public `FdaMatch` shape was simplified to exactly 4 fields
(`selected_match`, `confidence`, `other_matches`, `verification_required`) and aliases were
expanded with more single-word/phrase permutations per category so a lone matching word (e.g.
just "nuts") reliably surfaces a candidate, without adding any new (unverified) product codes.

**Local save / "evidence" (`EvidenceSaver.kt`, new file; voice triggers in
`FoodLabelViewModel.kt`).** Voice phrases ("save it", "store", "copy", "keep evidence", plain
"save") and a menu action both call one shared `performSave()`, which writes `photo.jpg` +
`data.json` (timestamp, analysis, full Q&A history since the last capture) via
`MediaStore.Downloads`. First implementation used `context.getExternalFilesDir()` (app-private)
and the user reported not being able to find the files in a file manager — app-private storage
under scoped storage is invisible to normal file managers by design. Switched to
`MediaStore.Downloads.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = "Download/FoodInspector/..."`,
which needs no runtime storage permission (inserting your own app's MediaStore entries is
always allowed) and is visible in any file manager / the Downloads app.
**Regression found during testing**: after the phone-camera capture feature (below) was added,
Save kept re-saving a stale glasses photo instead of the just-taken phone-camera photo. Root
cause: `performSave()` read `state.glasses.lastPhoto`, a field only ever written by
`GlassesManager.capturePhoto()` — the new phone-camera path never touched `GlassesManager` at
all, so that field simply never reflected a phone-camera capture. Fixed by adding a
source-agnostic `AppUiState.lastPhoto`, written in the shared `uploadAndAnalyze()` (used by
both capture sources) right before upload, and pointing `performSave()` at that instead.

**Phone-camera capture (`MainActivity.kt`, `AndroidManifest.xml`, `res/xml/file_paths.xml`, new
files/entries).** Launches the **system Camera app** via
`ActivityResultContracts.TakePicture()` (full resolution — chosen over the simpler
`TakePicturePreview()`, which returns a low-res thumbnail unsuitable for label OCR/analysis, and
over an embedded CameraX preview, which was explicitly out of scope). Requires a `FileProvider`
(new `<provider>` manifest entry + `res/xml/file_paths.xml` cache-path) since `TakePicture()`
needs a `content://` Uri, not a raw file path, on this app's API level. `CAMERA` permission was
already declared in the manifest but needed a runtime request (`ActivityResultContracts
.RequestPermission()`) since it's a dangerous permission. The resulting Bitmap is decoded and
handed to a new `FoodLabelViewModel.analyzePhoneCameraPhoto()`, which was implemented by
splitting the old `performCaptureAndAnalyze()` into the glasses-specific capture step followed
by a new shared `uploadAndAnalyze(bitmap, sessionId, source)` — so both capture sources produce
identical resulting app state through one code path, not two parallel near-duplicates.

**Voice mode stop/disconnect commands.** Added `STOP_VOICE_TRIGGERS` (routes to the existing
`stopVoiceMode()`, same as the manual button) and `STOP_GLASSES_TRIGGERS` (routes to the
existing `disconnectGlasses()`, then speaks a confirmation and **keeps voice mode listening** —
disconnecting the glasses is independent of whether you still want hands-free control, e.g. to
reconnect by voice afterward). Both checked in `handleVoiceCommand()` before the ask-the-LLM
fallback, same pattern as the existing capture/save trigger lists.

**Markdown leaking into voice replies.** Users saw literal `**95**`-style text in the Voice tab
and heard asterisks-adjacent artifacts in TTS. Two-pronged fix: (1) `build_system_message()`
now explicitly tells the model its output is shown as plain text, not rendered markdown, and to
avoid bold/code/headers/tables/JSON unless explicitly asked; (2) a client-side
`stripMarkdown()` (regex-based, strips `**bold**`/`*italic*`/`` `code` ``/`# headers` while
keeping the actual text) applied to every LLM answer before it's stored or spoken, as a
guaranteed fallback regardless of whether a given model actually complies with the prompt.

**UI redesign (two rounds), `MainActivity.kt` + new `ui/theme/` package.** Round 1: rebuilt the
single scrolling-column layout into a hamburger-drawer (Settings/About) + big live preview + a
5-icon bottom row that shows/hides one card at a time, plus a custom Material3 color scheme
(the app had been running on Compose's undecorated default palette the whole time — confirmed
by grep, no `ui/theme` package or custom `MaterialTheme(colorScheme=...)` existed before this).
Renamed the app's user-visible name to "Food Inspector" (manifest `android:label` + in-app
title) — deliberately scoped to visible text only, not the package name/class names/file names,
since renaming those is a structural change with real build risk, not a cosmetic one. Round 2
(after real-device testing): discovered the backend-URL field had *never* actually been wired to
`AppUiState.backendUrl` (a pre-existing gap, not introduced by round 1) — fixed with
`updateBackendUrl()` (validates via `java.net.URI`, resets session state on a real change) and a
debounced (~600ms) auto-apply with inline validation and a brief grey-out transition in
`SettingsScreen`. Added a real in-app back arrow (`BackHandler` for system/gesture back too,
since Settings/About previously had no way back except backgrounding the app), a clickable
"Food Inspector" logo-style title, reordered the Glasses card's buttons (Connect, Capture,
Register — was Register+Connect side by side, then Capture separately), swapped the AI-session
icon for an info icon with the card retitled "Session Information", and added the phone-camera
icon plus a "Clear Session" menu action (see above).

## 2026-09-10: full package/applicationId rename to com.foodinspector.app

The in-app display name had been "Food Inspector" since the round-1 UI redesign, but the
underlying package (`com.foodlabelai.v2`), applicationId, log tags, callback URI scheme, and
Gradle project name were all still the original "FoodLabelAI_v2" identifiers. Per explicit
request, these were renamed too — scoped, after confirming with the user, to: the package/
applicationId/build config (yes), the shorter `FoodLabelViewModel.kt`/`FoodLabelScreen` class
names (left as-is — the request was specifically about "foodlabelai_v2", not the separate
"FoodLabel" naming convention), and the project's root folder on disk (left as `FoodLabelAI_v2`
— renaming the directory this whole repo lives in was explicitly declined as out of scope).

Changes:
- Every `.kt` file physically moved from `app/src/main/java/com/foodlabelai/v2/` to
  `app/src/main/java/com/foodinspector/app/` (including the `ui/theme` subpackage), with
  `package`/import declarations updated to match.
- `app/build.gradle.kts`: `namespace`/`applicationId` `com.foodlabelai.v2` -> `com.foodinspector.app`.
- `settings.gradle.kts`: `rootProject.name` `"FoodLabelAI_v2"` -> `"FoodInspector"` (a Gradle
  build identifier only — doesn't touch the actual directory name).
- `AndroidManifest.xml`: the `foodlabelai://` callback URI scheme (used by the Meta AI
  companion app's return intent-filter) -> `foodinspector://`; both `@style/Theme.FoodLabelAI`
  references -> `@style/Theme.FoodInspector`.
- `res/values/themes.xml`: style renamed to match. `res/values/strings.xml`: `app_name`
  resource updated to "Food Inspector" (the manifest's `android:label` already used a literal
  "Food Inspector" string from the round-1 rename, so this was previously an unused-but-stale
  resource, not something actively wrong).
- Log tags renamed throughout: `FoodLabelAI_Glasses` -> `FoodInspector_Glasses`,
  `FoodLabelAI_Voice` -> `FoodInspector_Voice`, `FoodLabelAI_VM` -> `FoodInspector_VM`,
  `FoodLabelAI_Backend` -> `FoodInspector_Backend`, `FoodLabelAI_Evidence` ->
  `FoodInspector_Evidence`.
- `EvidenceSaver.kt`'s save path changed from `Download/FoodLabelAI/evidence_...` to
  `Download/FoodInspector/evidence_...` — this incidentally fixes a stale mismatch in this
  README (an earlier revision already described the save folder as "FoodInspector" when the
  code still actually wrote "FoodLabelAI").
- `README.md` updated throughout to reference the new package/log tags/folder path.

**Important — this is not a drop-in update.** Changing `applicationId` means Android treats
this as a completely different app from whatever was previously installed for testing
(different package = different app identity) — any existing test install must be uninstalled
and reinstalled fresh, not upgraded in place. The DAT companion-app registration (Developer
Mode `"0"`/`"0"` placeholders) is unaffected since it isn't tied to the package name.

Verified with a full `assembleDebug` (not just `compileDebugKotlin`) after the rename, since a
package move is exactly the kind of change a Kotlin-only compile can silently paper over
(manifest merging, resource linking) — build succeeded end to end.

Not touched: `.idea/workspace.xml` (IDE-generated editor/run-config state — Android Studio
regenerates this on its own when the project is reopened; hand-editing it isn't meaningful).
