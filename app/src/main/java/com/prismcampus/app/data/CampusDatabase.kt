package com.prismcampus.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Robust on-device SQLite database engine for PrismCampus.
 * Manages facilities issue reports, mesh sync flags, and student assignments.
 */
class CampusDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val _issuesFlow = MutableStateFlow<List<CampusIssueReport>>(emptyList())
    val issuesFlow: StateFlow<List<CampusIssueReport>> = _issuesFlow.asStateFlow()

    private val _assignmentsFlow = MutableStateFlow<List<AssignmentTask>>(emptyList())
    val assignmentsFlow: StateFlow<List<AssignmentTask>> = _assignmentsFlow.asStateFlow()

    init {
        // Initial load
        refreshCache()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ISSUES (
                id TEXT PRIMARY KEY,
                category TEXT NOT NULL,
                building TEXT NOT NULL,
                floor INTEGER NOT NULL,
                room TEXT NOT NULL,
                description TEXT NOT NULL,
                urgency TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                is_synced INTEGER NOT NULL DEFAULT 0,
                sync_timestamp INTEGER
                ,campus_id TEXT NOT NULL DEFAULT 'MSRIT'
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_ASSIGNMENTS (
                id TEXT PRIMARY KEY,
                subject TEXT NOT NULL,
                title TEXT NOT NULL,
                due_date TEXT NOT NULL,
                priority TEXT NOT NULL,
                is_completed INTEGER NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL,
                campus_id TEXT NOT NULL DEFAULT 'MSRIT'
            )
            """.trimIndent()
        )

        // Seed initial seed data for MSRIT campus demonstration
        seedInitialData(db)
    }

    private fun seedInitialData(db: SQLiteDatabase) {
        // Sample prior maintenance issues for pre-emption dashboard
        val seedIssues = listOf(
            ContentValues().apply {
                put("id", "issue_seed_1")
                put("category", "Hardware/AC")
                put("building", "Engineering Sciences Block")
                put("floor", 4)
                put("room", "Room 402")
                put("description", "CAD workstation #12 fan is vibrating loudly and overheating")
                put("urgency", "Medium")
                put("timestamp", System.currentTimeMillis() - 86400000L * 2)
                put("is_synced", 1)
                put("sync_timestamp", System.currentTimeMillis() - 86400000L)
            },
            ContentValues().apply {
                put("id", "issue_seed_2")
                put("category", "Electrical")
                put("building", "Engineering Sciences Block")
                put("floor", 4)
                put("room", "Room 402")
                put("description", "Ceiling fan regulator sparking in CAD lab")
                put("urgency", "Critical/Safety Hazard")
                put("timestamp", System.currentTimeMillis() - 3600000L * 18)
                put("is_synced", 0)
            },
            ContentValues().apply {
                put("id", "issue_seed_3")
                put("category", "Plumbing")
                put("building", "Apex Block")
                put("floor", 3)
                put("room", "AB-501")
                put("description", "Water dispenser tap leaking near CSE corridor")
                put("urgency", "Low")
                put("timestamp", System.currentTimeMillis() - 3600000L * 6)
                put("is_synced", 0)
            }
        )
        for (item in seedIssues) {
            db.insert(TABLE_ISSUES, null, item)
        }

        // Seed sample student assignments
        val seedAssignments = listOf(
            ContentValues().apply {
                put("id", "assign_seed_1")
                put("subject", "MAT11")
                put("title", "Engineering Mathematics Tutorial Sheet 3: Partial Derivatives")
                put("due_date", "Tomorrow, 5:00 PM")
                put("priority", "High")
                put("is_completed", 0)
                put("timestamp", System.currentTimeMillis())
            },
            ContentValues().apply {
                put("id", "assign_seed_2")
                put("subject", "PYC12")
                put("title", "Physics Lab Observation write-up (Newton's Rings)")
                put("due_date", "Friday, 2:00 PM")
                put("priority", "Medium")
                put("is_completed", 0)
                put("timestamp", System.currentTimeMillis())
            },
            ContentValues().apply {
                put("id", "assign_seed_3")
                put("subject", "PSCLCS18")
                put("title", "C Programming: Pointers & Dynamic Arrays exercise")
                put("due_date", "Saturday, 11:59 PM")
                put("priority", "High")
                put("is_completed", 0)
                put("timestamp", System.currentTimeMillis())
            }
        )
        for (item in seedAssignments) {
            db.insert(TABLE_ASSIGNMENTS, null, item)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE $TABLE_ISSUES ADD COLUMN campus_id TEXT NOT NULL DEFAULT 'MSRIT'")
        if (oldVersion < 3) db.execSQL("ALTER TABLE $TABLE_ASSIGNMENTS ADD COLUMN campus_id TEXT NOT NULL DEFAULT 'MSRIT'")
    }

    fun refreshCache() {
        _issuesFlow.value = getAllIssuesSync()
        _assignmentsFlow.value = getAllAssignmentsSync()
    }

    suspend fun insertIssue(issue: CampusIssueReport) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", issue.id)
            put("campus_id", issue.campusId)
            put("category", issue.category)
            put("building", issue.building)
            put("floor", issue.floor)
            put("room", issue.room)
            put("description", issue.description)
            put("urgency", issue.urgency)
            put("timestamp", issue.timestamp)
            put("is_synced", if (issue.isSynced) 1 else 0)
            put("sync_timestamp", issue.syncTimestamp)
        }
        writableDatabase.insertWithOnConflict(TABLE_ISSUES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshCache()
    }

    suspend fun markIssuesSynced(ids: List<String>, syncTime: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                val cv = ContentValues().apply {
                    put("is_synced", 1)
                    put("sync_timestamp", syncTime)
                }
                db.update(TABLE_ISSUES, cv, "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshCache()
    }

    suspend fun insertAssignment(assignment: AssignmentTask) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("id", assignment.id)
            put("campus_id", assignment.campusId)
            put("subject", assignment.subject)
            put("title", assignment.title)
            put("due_date", assignment.dueDate)
            put("priority", assignment.priority)
            put("is_completed", if (assignment.isCompleted) 1 else 0)
            put("timestamp", assignment.timestamp)
        }
        writableDatabase.insertWithOnConflict(TABLE_ASSIGNMENTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        refreshCache()
    }

    suspend fun toggleAssignmentCompletion(id: String, completed: Boolean) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("is_completed", if (completed) 1 else 0)
        }
        writableDatabase.update(TABLE_ASSIGNMENTS, cv, "id = ?", arrayOf(id))
        refreshCache()
    }

    suspend fun deleteAssignment(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_ASSIGNMENTS, "id = ?", arrayOf(id))
        refreshCache()
    }

    private fun getAllIssuesSync(): List<CampusIssueReport> {
        val list = mutableListOf<CampusIssueReport>()
        val cursor: Cursor = readableDatabase.query(
            TABLE_ISSUES,
            null,
            null,
            null,
            null,
            null,
            "timestamp DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    CampusIssueReport(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        campusId = c.getString(c.getColumnIndexOrThrow("campus_id")),
                        category = c.getString(c.getColumnIndexOrThrow("category")),
                        building = c.getString(c.getColumnIndexOrThrow("building")),
                        floor = c.getInt(c.getColumnIndexOrThrow("floor")),
                        room = c.getString(c.getColumnIndexOrThrow("room")),
                        description = c.getString(c.getColumnIndexOrThrow("description")),
                        urgency = c.getString(c.getColumnIndexOrThrow("urgency")),
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                        isSynced = c.getInt(c.getColumnIndexOrThrow("is_synced")) == 1,
                        syncTimestamp = if (c.isNull(c.getColumnIndexOrThrow("sync_timestamp"))) null else c.getLong(c.getColumnIndexOrThrow("sync_timestamp"))
                    )
                )
            }
        }
        return list
    }

    private fun getAllAssignmentsSync(): List<AssignmentTask> {
        val list = mutableListOf<AssignmentTask>()
        val cursor: Cursor = readableDatabase.query(
            TABLE_ASSIGNMENTS,
            null,
            null,
            null,
            null,
            null,
            "is_completed ASC, timestamp DESC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    AssignmentTask(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        campusId = c.getString(c.getColumnIndexOrThrow("campus_id")),
                        subject = c.getString(c.getColumnIndexOrThrow("subject")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        dueDate = c.getString(c.getColumnIndexOrThrow("due_date")),
                        priority = c.getString(c.getColumnIndexOrThrow("priority")),
                        isCompleted = c.getInt(c.getColumnIndexOrThrow("is_completed")) == 1,
                        timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"))
                    )
                )
            }
        }
        return list
    }

    companion object {
        const val DATABASE_NAME = "prism_campus.db"
        const val DATABASE_VERSION = 3

        const val TABLE_ISSUES = "issue_reports_table"
        const val TABLE_ASSIGNMENTS = "assignments_table"
    }
}

data class CampusIssueReport(
    val id: String = java.util.UUID.randomUUID().toString(),
    val category: String, // Electrical, Plumbing, Hardware/AC, Furniture, Lab Equipment
    val building: String,
    val floor: Int,
    val room: String,
    val description: String,
    val urgency: String, // Low, Medium, Critical/Safety Hazard
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val syncTimestamp: Long? = null,
    val campusId: String = "MSRIT"
)

data class AssignmentTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val subject: String,
    val title: String,
    val dueDate: String,
    val priority: String = "Medium", // Low, Medium, High
    val isCompleted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val campusId: String = "MSRIT"
)
