package com.studysync.app

import androidx.annotation.StringRes
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToInt

enum class TaskFilter(@StringRes val label: Int) {
    ALL(R.string.all_tasks),
    PENDING(R.string.pending),
    COMPLETED(R.string.completed),
    OVERDUE(R.string.overdue)
}

enum class TaskSort(@StringRes val label: Int) {
    DUE_DATE(R.string.sort_due_date),
    PRIORITY(R.string.sort_priority),
    TITLE(R.string.sort_title)
}

data class TaskProgress(
    val completed: Int,
    val total: Int
) {
    val fraction: Float
        get() = if (total == 0) 0f else completed.toFloat() / total

    val percentage: Int
        get() = (fraction * 100).roundToInt()
}

object TaskLogic {
    fun isOverdue(task: StudyTask, today: LocalDate): Boolean {
        if (task.completed) return false

        return try {
            LocalDate.parse(task.dueDate).isBefore(today)
        } catch (exception: DateTimeParseException) {
            false
        }
    }

    fun progress(
        tasks: List<StudyTask>,
        subjectId: String = ""
    ): TaskProgress {
        val selected = if (subjectId.isBlank()) {
            tasks
        } else {
            tasks.filter { it.subjectId == subjectId }
        }

        return TaskProgress(
            completed = selected.count { it.completed },
            total = selected.size
        )
    }

    fun select(
        tasks: List<StudyTask>,
        filter: TaskFilter,
        sort: TaskSort,
        subjectId: String,
        today: LocalDate
    ): List<StudyTask> {
        val filtered = tasks.filter { task ->
            val matchesSubject =
                subjectId.isBlank() || task.subjectId == subjectId

            val matchesStatus = when (filter) {
                TaskFilter.ALL -> true
                TaskFilter.PENDING -> !task.completed
                TaskFilter.COMPLETED -> task.completed
                TaskFilter.OVERDUE -> isOverdue(task, today)
            }

            matchesSubject && matchesStatus
        }

        val ordering: Comparator<StudyTask> = when (sort) {
            TaskSort.DUE_DATE ->
                compareBy<StudyTask> { it.dueDate }
                    .thenBy { it.taskId }

            TaskSort.PRIORITY ->
                compareBy<StudyTask> { priorityRank(it.priority) }
                    .thenBy { it.dueDate }
                    .thenBy { it.taskId }

            TaskSort.TITLE ->
                compareBy<StudyTask> { it.title.lowercase(Locale.ROOT) }
                    .thenBy { it.taskId }
        }

        return filtered.sortedWith(ordering)
    }

    private fun priorityRank(priority: String): Int = when (priority) {
        "HIGH" -> 0
        "MEDIUM" -> 1
        "LOW" -> 2
        else -> 3
    }
}