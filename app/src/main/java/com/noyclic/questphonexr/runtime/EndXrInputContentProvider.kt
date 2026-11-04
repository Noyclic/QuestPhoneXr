package com.noyclic.questphonexr.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Read-only IPC endpoint used by libendxr_openxr inside a game process.
 *
 * The provider returns one ephemeral row. It never accepts writes and does
 * not persist controller state. Values older than 300 ms are returned neutral.
 */
class EndXrInputContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.authority != AUTHORITY || uri.path != PATH) return null
        if (!selection.isNullOrBlank() || !selectionArgs.isNullOrEmpty() ||
            !sortOrder.isNullOrBlank()) {
            throw IllegalArgumentException("EndXR input snapshot does not support query filters.")
        }

        val snapshot = EndXrInputSnapshotStore.snapshotForIpc()
        val cursor = MatrixCursor(COLUMNS)
        cursor.addRow(
            arrayOf(
                snapshot.sequence,
                snapshot.updatedAtElapsedRealtimeNanos,
                snapshot.stale.asInt(),
                snapshot.left.active.asInt(),
                snapshot.left.trigger,
                snapshot.left.grip,
                snapshot.left.stickX,
                snapshot.left.stickY,
                snapshot.left.buttons,
                snapshot.right.active.asInt(),
                snapshot.right.trigger,
                snapshot.right.grip,
                snapshot.right.stickX,
                snapshot.right.stickY,
                snapshot.right.buttons,
                snapshot.headOrientation.active.asInt(),
                snapshot.headOrientationStale.asInt(),
                snapshot.headOrientationUpdatedAtElapsedRealtimeNanos,
                snapshot.headOrientation.x,
                snapshot.headOrientation.y,
                snapshot.headOrientation.z,
                snapshot.headOrientation.w
            )
        )

        if (projection.isNullOrEmpty()) return cursor
        val requested = projection.toSet()
        val unknown = requested - COLUMNS.toSet()
        if (unknown.isNotEmpty()) {
            cursor.close()
            throw IllegalArgumentException("Unknown EndXR input columns: ${unknown.joinToString()}")
        }
        return MatrixCursor(projection).also { projected ->
            if (cursor.moveToFirst()) {
                projected.addRow(projection.map { name ->
                    cursor.getColumnIndexOrThrow(name).let(cursor::getString)
                }.toTypedArray())
            }
            cursor.close()
        }
    }

    override fun getType(uri: Uri): String? =
        if (uri.authority == AUTHORITY && uri.path == PATH) {
            "vnd.android.cursor.item/vnd.com.noyclic.questphonexr.endxrinput"
        } else {
            null
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("EndXR input snapshot is read-only.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("EndXR input snapshot is read-only.")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("EndXR input snapshot is read-only.")

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    companion object {
        const val AUTHORITY = "com.noyclic.questphonexr.endxrinput"
        const val PATH = "/controller_state"
        const val CONTENT_URI = "content://$AUTHORITY$PATH"

        val COLUMNS = arrayOf(
            "sequence",
            "updated_at_elapsed_realtime_nanos",
            "stale",
            "left_active",
            "left_trigger",
            "left_grip",
            "left_stick_x",
            "left_stick_y",
            "left_buttons",
            "right_active",
            "right_trigger",
            "right_grip",
            "right_stick_x",
            "right_stick_y",
            "right_buttons",
            "head_orientation_active",
            "head_orientation_stale",
            "head_orientation_updated_at_elapsed_realtime_nanos",
            "head_orientation_x",
            "head_orientation_y",
            "head_orientation_z",
            "head_orientation_w"
        )
    }
}
