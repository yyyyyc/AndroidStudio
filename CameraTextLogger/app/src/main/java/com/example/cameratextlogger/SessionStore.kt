package com.example.cameratextlogger

import android.graphics.Bitmap
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val TAG = "CameraTextLogger"

fun formatTimestamp(timeMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))

/**
 * Keeps each session in its own folder: session.json for its options, log.txt for its text,
 * and images.txt listing the frames saved in images/.
 */
class SessionStore(private val root: File) {

    private fun dir(id: String) = File(root, id)
    private fun logFile(id: String) = File(dir(id), "log.txt")
    private fun imageLogFile(id: String) = File(dir(id), "images.txt")
    private fun imagesDir(id: String) = File(dir(id), "images")

    fun imageFile(session: Session, entry: ImageEntry) = File(imagesDir(session.id), entry.fileName)

    /** Creates the session, or updates its options if it exists. */
    fun save(session: Session) {
        dir(session.id).mkdirs()
        File(dir(session.id), "session.json").writeText(toJson(session).toString())
    }

    /** Erases the text log and the image log. */
    fun clearLog(session: Session) {
        logFile(session.id).delete()
        imageLogFile(session.id).delete()
        imagesDir(session.id).deleteRecursively()
    }

    fun saveImage(session: Session, frame: Bitmap, text: String, nowMs: Long) {
        val entry = ImageEntry(formatTimestamp(nowMs), "$nowMs.jpg", text)
        val file = imageFile(session, entry)
        file.parentFile?.mkdirs()
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        imageLogFile(session.id).appendText(formatImageLine(entry) + "\n")
    }

    fun readImages(session: Session): List<ImageEntry> {
        val file = imageLogFile(session.id)
        return if (file.exists()) file.readLines().mapNotNull(::parseImageLine) else emptyList()
    }

    fun delete(session: Session) {
        dir(session.id).deleteRecursively()
    }

    fun append(session: Session, text: String, nowMs: Long): LogEntry {
        val entry = LogEntry(formatTimestamp(nowMs), text)
        Log.i(TAG, text)
        logFile(session.id).appendText(formatLogLine(entry) + "\n")
        return entry
    }

    fun readLog(session: Session): List<LogEntry> {
        val file = logFile(session.id)
        return if (file.exists()) file.readLines().map(::parseLogLine) else emptyList()
    }

    /** All sessions, newest first. */
    fun list(): List<SessionSummary> =
        root.listFiles().orEmpty()
            .mapNotNull { read(it) }
            .sortedByDescending { it.startedAtMs }
            .map { session ->
                val entries = readLog(session)
                SessionSummary(
                    session,
                    entries.size,
                    entries.count { session.findPattern.matches(it.text) },
                    readImages(session).size,
                )
            }

    private fun read(sessionDir: File): Session? = try {
        fromJson(JSONObject(File(sessionDir, "session.json").readText()))
    } catch (e: IOException) {
        Log.w(TAG, "Skipping unreadable session ${sessionDir.name}", e)
        null
    } catch (e: JSONException) {
        Log.w(TAG, "Skipping unreadable session ${sessionDir.name}", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Skipping unreadable session ${sessionDir.name}", e)
        null
    }

    private fun toJson(session: Session) = JSONObject()
        .put("id", session.id)
        .put("name", session.name)
        .put("startedAtMs", session.startedAtMs)
        .put("findText", session.findText)
        .put("notifyWhenFound", session.notifyWhenFound)
        .put("scanRate", session.scanRate.name)
        .put("saveMode", session.saveMode.name)
        .put("saveMinCharacters", session.saveMinCharacters)
        .put("saveContains", session.saveContains)
        .put("maxLogEntries", session.maxLogEntries)
        .put("language", session.language.name)
        .put("beepWhenFound", session.beepWhenFound)
        .put("captureImageWhenFound", session.captureImageWhenFound)
        .put("readBy", session.readBy.name)
        .put("source", session.source.name)
        .put("highlightFound", session.highlightFound)
        .put("bothCameras", session.bothCameras)

    private fun fromJson(json: JSONObject) = Session(
        id = json.getString("id"),
        name = json.getString("name"),
        startedAtMs = json.getLong("startedAtMs"),
        findText = json.getString("findText"),
        notifyWhenFound = json.getBoolean("notifyWhenFound"),
        scanRate = ScanRate.valueOf(json.getString("scanRate")),
        saveMode = SaveMode.valueOf(json.getString("saveMode")),
        saveMinCharacters = json.getInt("saveMinCharacters"),
        saveContains = json.getString("saveContains"),
        maxLogEntries = if (json.has("maxLogEntries")) json.getInt("maxLogEntries") else null,
        // Sessions from before the language option were all English. Hebrew-only was removed.
        language = RecognitionLanguage.valueOf(
            json.optString("language", RecognitionLanguage.ENGLISH.name)
                .replace(Regex("^HEBREW$"), RecognitionLanguage.HEBREW_AND_ENGLISH.name)
        ),
        beepWhenFound = json.optBoolean("beepWhenFound", false),
        captureImageWhenFound = json.optBoolean("captureImageWhenFound", false),
        readBy = ReadBy.valueOf(json.optString("readBy", ReadBy.LINES.name)),
        source = SessionSource.valueOf(json.optString("source", SessionSource.CAMERA.name)),
        // Called highlightOnScreen when only screen sessions could highlight.
        highlightFound = json.optBoolean("highlightFound", json.optBoolean("highlightOnScreen", false)),
        bothCameras = json.optBoolean("bothCameras", false),
    )
}
