package com.studysync.app

data class Subject(
    val subjectId: String,
    val name: String,
    val lecturerName: String = ""
)

object SubjectValidator {

    fun validate(
        name: String,
        lecturerName: String,
        existingSubjects: List<Subject>,
        editingSubjectId: String? = null
    ): String? {
        val cleanName = name.trim()

        if (cleanName.isEmpty()) {
            return AppMessage.SUBJECT_NAME_REQUIRED.name
        }

        if (cleanName.length > 80) {
            return AppMessage.SUBJECT_NAME_TOO_LONG.name
        }

        if (lecturerName.trim().length > 80) {
            return AppMessage.LECTURER_NAME_TOO_LONG.name
        }

        val duplicateExists = existingSubjects.any {
            it.subjectId != editingSubjectId &&
                    it.name.equals(cleanName, ignoreCase = true)
        }

        if (duplicateExists) {
            return AppMessage.SUBJECT_DUPLICATE.name
        }

        return null
    }
}
