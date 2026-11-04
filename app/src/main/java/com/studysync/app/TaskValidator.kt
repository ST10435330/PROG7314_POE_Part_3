package com.studysync.app

import java.time.LocalDate
import java.time.format.DateTimeParseException

object TaskValidator {

    // A null result means the task passed validation.
    fun validate(task: StudyTask): String? {
        if (task.title.isBlank()) {
            return AppMessage.TASK_TITLE_REQUIRED.name
        }

        if (task.title.trim().length > 120) {
            return AppMessage.TASK_TITLE_TOO_LONG.name
        }

        if (task.subjectId.isBlank()) {
            return AppMessage.SUBJECT_REQUIRED.name
        }

        if (task.description.trim().length > 2000) {
            return AppMessage.DESCRIPTION_TOO_LONG.name
        }

        if (!isValidDate(task.dueDate)) {
            return AppMessage.INVALID_DUE_DATE.name
        }

        if (task.priority !in listOf("HIGH", "MEDIUM", "LOW")) {
            return AppMessage.INVALID_PRIORITY.name
        }

        return null
    }

    private fun isValidDate(value: String): Boolean {
        if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            return false
        }

        return try {
            val date = LocalDate.parse(value)
            date.year in 1900..2100
        } catch (exception: DateTimeParseException) {
            false
        }
    }
}
