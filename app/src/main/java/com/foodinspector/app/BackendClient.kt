package com.foodinspector.app

import android.graphics.Bitmap
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class BackendAnalysis(
    val rawJson: String,
    val foodCount: Int,
)

class BackendClient(private val baseUrl: String) {
    companion object {
        private const val TAG = "FoodInspector_Backend"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private fun url(path: String): String =
        baseUrl.trimEnd('/') + path

    fun createSession(): String {
        val request = Request.Builder()
            .url(url("/session"))
            .post(ByteArray(0).toRequestBody(null))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Session creation failed: ${response.code}: $body")
            return JSONObject(body).getString("session_id")
        }
    }

    fun analyzeImage(sessionId: String, bitmap: Bitmap, cloudMode: Boolean = false): BackendAnalysis {
        val temp = File.createTempFile("foodlabel_", ".jpg")
        try {
            FileOutputStream(temp).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "glasses_capture.jpg",
                    temp.asRequestBody("image/jpeg".toMediaType())
                )
                .addFormDataPart("backend_mode", if (cloudMode) "cloud" else "local")
                .build()

            val request = Request.Builder()
                .url(url("/session/$sessionId/image"))
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // The full body (often a huge raw_model_response dump on a 502) is only
                    // useful for debugging — logged here, but never surfaced to the UI/TTS,
                    // which should just say the capture failed, not read out a JSON schema.
                    Log.e(TAG, "Image analysis failed: ${response.code}: $text")
                    error("Image analysis failed")
                }
                val json = JSONObject(text)
                return BackendAnalysis(
                    rawJson = json.getJSONObject("analysis").toString(2),
                    foodCount = json.optInt("food_count", 0),
                )
            }
        } finally {
            temp.delete()
        }
    }

    /** Text-only counterpart to the old audio-upload voice endpoint — used with on-device
     *  speech recognition, which already has the transcript and doesn't need server-side
     *  Whisper transcription for every turn. */
    fun askQuestion(sessionId: String, question: String, cloudMode: Boolean = false): String {
        val json = JSONObject()
            .put("question", question)
            .put("backend_mode", if (cloudMode) "cloud" else "local")
            .toString()
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url("/session/$sessionId/ask"))
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "Ask request failed: ${response.code}: $text")
                error("Ask request failed")
            }
            return JSONObject(text).getString("response")
        }
    }
}
