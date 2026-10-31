package com.studysync.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingStudyChange(
    val operationId: String,
    val kind: String,
    val recordId: String,
    val method: String,
    val payload: String?
)

/**
 * Stores one account's cache and immutable upload operations in a private database.
 * Repository code validates models and calls these methods on Dispatchers.IO.
 */
class LocalStudyStore private constructor(
    context: Context,
    userId: String
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName(userId),
    null,
    1
) {
    private val gson = Gson()
    private val changeCounter = MutableStateFlow(0L)

    val changes = changeCounter.asStateFlow()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cache (
                kind TEXT NOT NULL,
                record_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY (kind, record_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE outbox (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_id TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                record_id TEXT NOT NULL,
                method TEXT NOT NULL CHECK (method IN ('PUT', 'DELETE')),
                payload TEXT,
                CHECK ((method = 'PUT' AND payload IS NOT NULL) OR
                       (method = 'DELETE' AND payload IS NULL))
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX outbox_record ON outbox(kind, record_id)"
        )

        db.execSQL(
            """
            CREATE TABLE local_meta (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                version INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "INSERT INTO local_meta(id, version) VALUES(1, 0)"
        )

        Log.d("StudySync", "Local study database created")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Future versions must migrate pending changes rather than discard them.
        error(
            "A database migration is required from $oldVersion to $newVersion"
        )
    }

    fun getSubjects(): List<Subject> =
        readPayloads("subjects").map {
            gson.fromJson(it, Subject::class.java)
        }

    fun getTasks(): List<StudyTask> =
        readPayloads("tasks").map {
            gson.fromJson(it, StudyTask::class.java)
        }

    fun saveSubject(subject: Subject) = saveLocal(
        "subjects",
        subject.subjectId,
        gson.toJson(subject)
    )

    fun saveTask(task: StudyTask) = saveLocal(
        "tasks",
        task.taskId,
        gson.toJson(task)
    )

    fun deleteSubject(id: String) =
        saveLocal("subjects", id, null)

    fun deleteTask(id: String) =
        saveLocal("tasks", id, null)

    private fun saveLocal(
        kind: String,
        id: String,
        payload: String?
    ) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "Invalid record ID"
        }

        mutate { db ->
            if (payload == null) {
                db.delete(
                    "cache",
                    "kind=? AND record_id=?",
                    arrayOf(kind, id)
                )
            } else {
                putCache(db, kind, id, payload)
            }

            // Save the cache change and upload operation together.
            db.insertOrThrow(
                "outbox",
                null,
                ContentValues().apply {
                    put("operation_id", UUID.randomUUID().toString())
                    put("kind", kind)
                    put("record_id", id)
                    put("method", if (payload == null) "DELETE" else "PUT")

                    if (payload == null) {
                        putNull("payload")
                    } else {
                        put("payload", payload)
                    }
                }
            )

            bumpVersion(db)
        }
    }

    fun pendingChanges(
        limit: Int = 100
    ): List<PendingStudyChange> {
        require(limit in 1..1000)

        return writableDatabase.rawQuery(
            """
            SELECT operation_id, kind, record_id, method, payload
            FROM outbox
            ORDER BY sequence
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingStudyChange(
                            operationId = cursor.getString(0),
                            kind = cursor.getString(1),
                            recordId = cursor.getString(2),
                            method = cursor.getString(3),
                            payload = if (cursor.isNull(4)) {
                                null
                            } else {
                                cursor.getString(4)
                            }
                        )
                    )
                }
            }
        }
    }

    fun pendingCount(): Int =
        writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM outbox",
            null
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    // Acknowledge the exact request
    fun acknowledge(operationId: String) {
        mutate { db ->
            val deleted = db.delete(
                "outbox",
                "operation_id=?",
                arrayOf(operationId)
            )

            if (deleted > 0) {
                bumpVersion(db)
            }
        }
    }

    fun version(): Long =
        readVersion(writableDatabase)

    /** Capture version() before starting the network reads. */
    fun mergeRemote(
        expectedVersion: Long,
        subjects: List<Subject>,
        tasks: List<StudyTask>
    ): Boolean = mutate { db ->
        if (readVersion(db) != expectedVersion) {
            return@mutate false
        }

        mergeKind(
            db,
            "subjects",
            subjects.map { it.subjectId to gson.toJson(it) }
        )

        mergeKind(
            db,
            "tasks",
            tasks.map { it.taskId to gson.toJson(it) }
        )

        bumpVersion(db)
        true
    }

    private fun mergeKind(
        db: SQLiteDatabase,
        kind: String,
        records: List<Pair<String, String>>
    ) {
        val protectedIds = db.rawQuery(
            "SELECT DISTINCT record_id FROM outbox WHERE kind=?",
            arrayOf(kind)
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

        // Pending edits and deletions must survive a cloud refresh.
        db.delete(
            "cache",
            """
            kind=? AND record_id NOT IN (
                SELECT record_id FROM outbox WHERE kind=?
            )
            """.trimIndent(),
            arrayOf(kind, kind)
        )

        records
            .filterNot { it.first in protectedIds }
            .forEach { (id, payload) ->
                putCache(db, kind, id, payload)
            }
    }

    private fun readPayloads(kind: String): List<String> =
        writableDatabase.query(
            "cache",
            arrayOf("payload"),
            "kind=?",
            arrayOf(kind),
            null,
            null,
            "record_id"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private fun putCache(
        db: SQLiteDatabase,
        kind: String,
        id: String,
        payload: String
    ) {
        val result = db.insertWithOnConflict(
            "cache",
            null,
            ContentValues().apply {
                put("kind", kind)
                put("record_id", id)
                put("payload", payload)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )

        check(result != -1L)
    }

    private fun readVersion(db: SQLiteDatabase): Long =
        db.rawQuery(
            "SELECT version FROM local_meta WHERE id=1",
            null
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun bumpVersion(db: SQLiteDatabase) {
        db.execSQL(
            "UPDATE local_meta SET version=version+1 WHERE id=1"
        )
    }

    private fun <T> mutate(
        block: (SQLiteDatabase) -> T
    ): T {
        val db = writableDatabase
        var changed = false

        db.beginTransaction()

        val result = try {
            val before = readVersion(db)

            block(db).also {
                changed = readVersion(db) != before
                db.setTransactionSuccessful()
            }
        } finally {
            db.endTransaction()
        }

        if (changed) {
            changeCounter.update { it + 1 }
            Log.d("StudySync", "Local study transaction committed")
        }

        return result
    }

    companion object {
        private val instances =
            ConcurrentHashMap<String, LocalStudyStore>()

        // Share notifications between the UI repository and sync worker.
        fun get(
            context: Context,
            userId: String
        ): LocalStudyStore {
            require(userId.isNotBlank()) {
                "A signed-in account is required"
            }

            return instances.computeIfAbsent(userId) {
                LocalStudyStore(context, it)
            }
        }

        private fun databaseName(userId: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(userId.toByteArray(Charsets.UTF_8))
                .joinToString("") {
                    (it.toInt() and 255)
                        .toString(16)
                        .padStart(2, '0')
                }

            return "studysync_$hash.db"
        }
    }
}