package com.studysync.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable

data class CompletedFocusSession(
    val sessionId: String,
    val userId: String,
    val completedAt: Long,
    val durationMillis: Long,
    val subjectId: String?,
    val subjectName: String?
)

// Focus history is local only and never enters the study sync outbox.
class FocusSessionStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "studysync_focus.db", null, 1
), Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE focus_sessions (
                session_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                completed_at INTEGER NOT NULL,
                duration_millis INTEGER NOT NULL CHECK (duration_millis > 0),
                subject_id TEXT,
                subject_name TEXT,
                PRIMARY KEY (user_id, session_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX focus_history ON focus_sessions(user_id, completed_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("A focus history migration is required from $oldVersion to $newVersion")
    }

    fun saveCompleted(userId: String, timer: FocusTimer): Boolean {
        require(userId.isNotBlank())
        if (timer.state != FocusState.COMPLETED || timer.completedAt == null ||
            timer.remainingAtStart != 0L
        ) return false

        val values = ContentValues().apply {
            put("session_id", timer.sessionId)
            put("user_id", userId)
            put("completed_at", timer.completedAt)
            put("duration_millis", timer.durationMillis)
            put("subject_id", timer.subjectId)
            put("subject_name", timer.subjectName)
        }
        writableDatabase.insertWithOnConflict(
            "focus_sessions", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        // A repeated save is successful when the original completion is already present.
        return readableDatabase.rawQuery(
            "SELECT 1 FROM focus_sessions WHERE user_id=? AND session_id=?",
            arrayOf(userId, timer.sessionId)
        ).use { it.moveToFirst() }
    }

    fun history(userId: String): List<CompletedFocusSession> {
        require(userId.isNotBlank())
        return readableDatabase.query(
            "focus_sessions",
            arrayOf("session_id", "user_id", "completed_at", "duration_millis", "subject_id", "subject_name"),
            "user_id=?", arrayOf(userId), null, null, "completed_at DESC, session_id"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CompletedFocusSession(
                            sessionId = cursor.getString(0),
                            userId = cursor.getString(1),
                            completedAt = cursor.getLong(2),
                            durationMillis = cursor.getLong(3),
                            subjectId = if (cursor.isNull(4)) null else cursor.getString(4),
                            subjectName = if (cursor.isNull(5)) null else cursor.getString(5)
                        )
                    )
                }
            }
        }
    }
}
