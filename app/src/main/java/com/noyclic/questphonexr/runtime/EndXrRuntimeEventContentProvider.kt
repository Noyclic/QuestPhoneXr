package com.noyclic.questphonexr.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.os.SystemClock

/**
 * Write-only-from-authorized-game telemetry endpoint for native EndXR lifecycle events.
 *
 * The app grants WRITE access only to the exact runtime_event URI and only after
 * explicit user approval. Reads are restricted to the NextVRera app UID.
 */
class EndXrRuntimeEventContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        requireRuntimeEventUri(uri)
        val context = requireNotNull(context)
        val callingPackage = getCallingPackage()
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid() ||
            (callingPackage != null && callingPackage != context.packageName)
        ) {
            throw SecurityException("Runtime telemetry can only be read by NextVRera.")
        }
        if (callingPackage == null) {
            val ownPackages = context.packageManager.getPackagesForUid(callingUid).orEmpty()
            if (ownPackages.size > 1 && ownPackages.any { it != context.packageName }) {
                throw SecurityException("Runtime telemetry is private to the NextVRera package.")
            }
        }
        if (!selection.isNullOrBlank() || !selectionArgs.isNullOrEmpty() ||
            !sortOrder.isNullOrBlank()) {
            throw IllegalArgumentException("Runtime telemetry does not support query filters.")
        }

        val prefs = context.getSharedPreferences(PREFERENCES_NAME, 0)
        // Keep the complete row coherent with one reducer transition. An XR game
        // may submit an event from another process while NextVRera is reading.
        val row = synchronized(LOCK) {
            val sequence = prefs.getLong(KEY_SEQUENCE, 0L)
            if (sequence <= 0L) {
                null
            } else {
                arrayOf<Any?>(
                    sequence,
                    prefs.getString(KEY_EVENT, null),
                    prefs.getLong(KEY_EVENT_TIME_NS, 0L),
                    prefs.getString(KEY_CALLER_PACKAGE, null),
                    prefs.getInt(KEY_RUNTIME_PID, -1),
                    prefs.getString(KEY_INSTANCE_ID, null),
                    prefs.getString(KEY_SESSION_ID, null),
                    prefs.getInt(KEY_INSTANCE_CREATED, 0),
                    prefs.getInt(KEY_SESSION_CREATED, 0),
                    prefs.getInt(KEY_SESSION_RUNNING, 0),
                    prefs.getInt(KEY_INSTANCE_SEEN, 0),
                    prefs.getInt(KEY_SESSION_SEEN, 0),
                    prefs.getInt(KEY_SESSION_EVER_RUNNING, 0),
                    prefs.getLong(KEY_FRAME_COUNT, 0L)
                )
            }
        }

        val requested = if (projection.isNullOrEmpty()) COLUMNS.toList() else projection.toList()
        val unknown = requested.toSet() - COLUMNS.toSet()
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown runtime telemetry columns: ${unknown.joinToString()}"
            )
        }
        val cursor = MatrixCursor(requested.toTypedArray())
        if (row != null) {
            val values = COLUMNS.indices.associate { COLUMNS[it] to row[it] }
            cursor.addRow(requested.map { values[it] }.toTypedArray())
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        requireRuntimeEventUri(uri)
        val context = requireNotNull(context)
        val callingPid = Binder.getCallingPid()
        val callingUid = Binder.getCallingUid()
        val uriPermission = context.checkUriPermission(
            uri,
            callingPid,
            callingUid,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (uriPermission != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("The calling app has no explicit EndXR telemetry write grant.")
        }

        val event = values?.getAsString(COLUMN_EVENT)
            ?.takeIf { it in ALLOWED_EVENTS }
            ?: throw IllegalArgumentException("Missing or unsupported EndXR runtime event.")
        val instanceId = values.getAsString(COLUMN_INSTANCE_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?: throw IllegalArgumentException("Missing or invalid runtime instance id.")
        val sessionId = values.getAsString(COLUMN_SESSION_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 80 }
        val frameCount = (values.getAsLong(COLUMN_FRAME_COUNT) ?: 0L).coerceAtLeast(0L)
        val callerPackage = getCallingPackage() ?: run {
            val packages = context.packageManager.getPackagesForUid(callingUid).orEmpty()
            packages.singleOrNull() ?: throw SecurityException(
                "Cannot securely attribute telemetry from a shared or unknown UID."
            )
        }
        if (callerPackage == context.packageName) {
            throw SecurityException("NextVRera cannot submit game runtime telemetry.")
        }
        val accessManager = EndXrInputAccessManager(context)
        if (!accessManager.isPackageAuthorized(
                callerPackage,
                callingUid,
                requireRuntimeTelemetry = true
            )
        ) {
            throw SecurityException(
                "The calling package and signing certificate have no EndXR runtime telemetry consent."
            )
        }

        val prefs = context.getSharedPreferences(PREFERENCES_NAME, 0)
        synchronized(LOCK) {
            val previous = readState(prefs)
            val reduction = previous.reduce(event, instanceId, sessionId, frameCount)
                ?: throw IllegalArgumentException(
                    "Invalid EndXR runtime event order: event=$event instance=$instanceId session=$sessionId"
                )
            if (reduction.changed) {
                val state = reduction.state
                val editor = prefs.edit()
                    .putLong(KEY_SEQUENCE, prefs.getLong(KEY_SEQUENCE, 0L) + 1L)
                    .putString(KEY_EVENT, event)
                    .putLong(KEY_EVENT_TIME_NS, SystemClock.elapsedRealtimeNanos())
                    .putString(KEY_CALLER_PACKAGE, callerPackage)
                    .putInt(KEY_RUNTIME_PID, callingPid)
                    .putString(KEY_INSTANCE_ID, state.instanceId)
                    .putString(KEY_SESSION_ID, state.sessionId)
                    .putInt(KEY_INSTANCE_CREATED, state.instanceCreated.asInt())
                    .putInt(KEY_SESSION_CREATED, state.sessionCreated.asInt())
                    .putInt(KEY_SESSION_RUNNING, state.sessionRunning.asInt())
                    .putInt(KEY_INSTANCE_SEEN, state.instanceSeen.asInt())
                    .putInt(KEY_SESSION_SEEN, state.sessionSeen.asInt())
                    .putInt(KEY_SESSION_EVER_RUNNING, state.sessionEverRunning.asInt())
                    .putLong(KEY_FRAME_COUNT, state.frameCount)
                    .putLong(KEY_SESSION_FRAME_COUNT, state.sessionFrameCount)
                check(editor.commit()) { "Could not persist EndXR runtime telemetry." }
                context.contentResolver.notifyChange(uri, null)
            }
        }
        return Uri.withAppendedPath(uri, "latest")
    }

    override fun getType(uri: Uri): String? =
        if (uri.authority == AUTHORITY && uri.path == PATH) {
            "vnd.android.cursor.item/vnd.com.noyclic.questphonexr.endxr-runtime-event"
        } else null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("EndXR runtime telemetry cannot be deleted by clients.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("EndXR runtime telemetry is append-only.")

    private fun readState(prefs: android.content.SharedPreferences): EndXrRuntimeEventState =
        EndXrRuntimeEventState(
            instanceId = prefs.getString(KEY_INSTANCE_ID, null),
            sessionId = prefs.getString(KEY_SESSION_ID, null),
            instanceCreated = prefs.getInt(KEY_INSTANCE_CREATED, 0) != 0,
            sessionCreated = prefs.getInt(KEY_SESSION_CREATED, 0) != 0,
            sessionRunning = prefs.getInt(KEY_SESSION_RUNNING, 0) != 0,
            instanceSeen = prefs.getInt(KEY_INSTANCE_SEEN, 0) != 0,
            sessionSeen = prefs.getInt(KEY_SESSION_SEEN, 0) != 0,
            sessionEverRunning = prefs.getInt(KEY_SESSION_EVER_RUNNING, 0) != 0,
            frameCount = prefs.getLong(KEY_FRAME_COUNT, 0L),
            sessionFrameCount = prefs.getLong(KEY_SESSION_FRAME_COUNT, 0L)
        )

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private fun requireRuntimeEventUri(uri: Uri) {
        if (uri.authority != AUTHORITY || uri.path != PATH) {
            throw IllegalArgumentException("Unknown EndXR runtime telemetry URI.")
        }
    }

    companion object {
        const val AUTHORITY = "com.noyclic.questphonexr.endxrtelemetry"
        const val PATH = "/runtime_event"
        const val CONTENT_URI = "content://$AUTHORITY$PATH"

        const val COLUMN_EVENT = "event"
        const val COLUMN_INSTANCE_ID = "instance_id"
        const val COLUMN_SESSION_ID = "session_id"
        const val COLUMN_FRAME_COUNT = "frame_count"

        const val EVENT_CREATE_INSTANCE = "xr_create_instance"
        const val EVENT_CREATE_SESSION = "xr_create_session"
        const val EVENT_BEGIN_SESSION = "xr_begin_session"
        const val EVENT_END_SESSION = "xr_end_session"
        const val EVENT_END_FRAME = "xr_end_frame"
        const val EVENT_DESTROY_SESSION = "xr_destroy_session"
        const val EVENT_DESTROY_INSTANCE = "xr_destroy_instance"

        private val ALLOWED_EVENTS = setOf(
            EVENT_CREATE_INSTANCE,
            EVENT_CREATE_SESSION,
            EVENT_BEGIN_SESSION,
            EVENT_END_SESSION,
            EVENT_END_FRAME,
            EVENT_DESTROY_SESSION,
            EVENT_DESTROY_INSTANCE
        )
        private val COLUMNS = arrayOf(
            "sequence",
            "event_name",
            "event_elapsed_realtime_nanos",
            "caller_package",
            "runtime_pid",
            "instance_id",
            "session_id",
            "instance_created",
            "session_created",
            "session_running",
            "instance_seen",
            "session_seen",
            "session_ever_running",
            "frame_count"
        )

        private const val PREFERENCES_NAME = "endxr_runtime_events"
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_EVENT = "event"
        private const val KEY_EVENT_TIME_NS = "event_time_ns"
        private const val KEY_CALLER_PACKAGE = "caller_package"
        private const val KEY_RUNTIME_PID = "runtime_pid"
        private const val KEY_INSTANCE_ID = "instance_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_INSTANCE_CREATED = "instance_created"
        private const val KEY_SESSION_CREATED = "session_created"
        private const val KEY_SESSION_RUNNING = "session_running"
        private const val KEY_INSTANCE_SEEN = "instance_seen"
        private const val KEY_SESSION_SEEN = "session_seen"
        private const val KEY_SESSION_EVER_RUNNING = "session_ever_running"
        private const val KEY_FRAME_COUNT = "frame_count"
        private const val KEY_SESSION_FRAME_COUNT = "session_frame_count"
        private val LOCK = Any()
    }
}
