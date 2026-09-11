package com.foodinspector.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Voice-triggered local save ("save it", "keep evidence", etc.) — writes the current capture
 *  through MediaStore into the shared Downloads collection, entirely offline. Unlike
 *  getExternalFilesDir() (app-private, invisible to file managers under scoped storage),
 *  MediaStore.Downloads entries land in the visible Downloads folder and need no runtime
 *  storage permission on this app's minSdk (30) — inserting your own app's entries via the
 *  provider is always allowed, unlike writing directly to shared storage paths. */
object EvidenceSaver {
    private const val TAG = "FoodInspector_Evidence"
    private val FOLDER_NAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Returns the Downloads-relative folder path the files were saved under, e.g.
     *  "Download/FoodInspector/evidence_20260909_143012", for status/logging display. */
    fun save(
        context: Context,
        bitmap: Bitmap?,
        analysisJson: String?,
        history: List<ConversationTurn>,
    ): String {
        val relativeFolder = "Download/FoodInspector/evidence_${FOLDER_NAME_FORMAT.format(Date())}"

        if (bitmap != null) {
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray()
            }
            insertFile(context, relativeFolder, "photo.jpg", "image/jpeg", bytes)
        }

        val conversation = JSONArray().apply {
            history.forEach { turn ->
                put(JSONObject().put("question", turn.question).put("answer", turn.answer))
            }
        }
        val data = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("analysis", analysisJson?.let { JSONObject(it) })
            .put("conversation", conversation)
        insertFile(context, relativeFolder, "data.json", "application/json", data.toString(2).toByteArray())

        Log.d(TAG, "Saved evidence to $relativeFolder")
        return relativeFolder
    }

    private fun insertFile(context: Context, relativeFolder: String, name: String, mimeType: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed for $name")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IOException("Could not open output stream for $name")
    }
}
