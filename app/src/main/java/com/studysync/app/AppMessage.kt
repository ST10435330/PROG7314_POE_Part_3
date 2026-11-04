package com.studysync.app

import android.content.Context
import androidx.annotation.StringRes

// Store names rather than translated text so saved messages follow the language.
enum class AppMessage(@StringRes val resourceId: Int) {
    ACCOUNT_CHANGED(R.string.account_changed),
    RECORD_NOT_FOUND(R.string.record_not_found),
    REGISTER_DEVICE_FIRST(R.string.register_device_first),
    INVALID_CALENDAR_DATE(R.string.invalid_calendar_date),
    SERVER_SUBJECT_NAME_REQUIRED(R.string.server_subject_name_required),
    SERVER_TASK_TITLE_REQUIRED(R.string.server_task_title_required),
    SERVER_TASK_TITLE_TOO_LONG(R.string.server_task_title_too_long),
    SERVER_DESCRIPTION_TOO_LONG(R.string.server_description_too_long),
    SERVER_SUBJECT_NAME_TOO_LONG(R.string.server_subject_name_too_long),
    SERVER_LECTURER_NAME_TOO_LONG(R.string.server_lecturer_name_too_long),
    API_CONNECTION_ERROR(R.string.api_connection_error),
    API_EMPTY_RESPONSE(R.string.api_empty_response),
    API_SUBJECT_ID_ERROR(R.string.api_subject_id_error),
    API_TASK_ID_ERROR(R.string.api_task_id_error),
    API_UNEXPECTED_RESPONSE(R.string.api_unexpected_response),
    BIOMETRIC_ALREADY_OPEN(R.string.biometric_already_open),
    BIOMETRIC_CANCELLED(R.string.biometric_cancelled),
    BIOMETRIC_ENROL_REQUIRED(R.string.biometric_enrol_required),
    BIOMETRIC_FAILED(R.string.biometric_failed),
    BIOMETRIC_OPEN_ERROR(R.string.biometric_open_error),
    BIOMETRIC_SETUP_ERROR(R.string.biometric_setup_error),
    BIOMETRIC_UNAVAILABLE(R.string.biometric_unavailable),
    BIOMETRIC_UNSUPPORTED(R.string.biometric_unsupported),
    CHANGE_REJECTED(R.string.change_rejected),
    CONNECTION_ERROR(R.string.connection_error),
    DATA_CONFLICT(R.string.data_conflict),
    DELETED_ITEM(R.string.deleted_item),
    DESCRIPTION_TOO_LONG(R.string.description_too_long),
    DEVICE_REGISTRATION_EXPIRED(R.string.device_registration_expired),
    EXISTING_SUBJECT_REQUIRED(R.string.existing_subject_required),
    GOOGLE_ACCOUNT_UNAVAILABLE(R.string.google_account_unavailable),
    GOOGLE_SIGN_IN_ERROR(R.string.google_sign_in_error),
    INVALID_DEFAULT_PRIORITY(R.string.invalid_default_priority),
    INVALID_DUE_DATE(R.string.invalid_due_date),
    INVALID_LANGUAGE(R.string.invalid_language),
    INVALID_PRIORITY(R.string.invalid_priority),
    INVALID_SUBJECT(R.string.invalid_subject),
    INVALID_TASK(R.string.invalid_task),
    LECTURER_NAME_TOO_LONG(R.string.lecturer_name_too_long),
    NOTIFICATION_SETTINGS_ERROR(R.string.notification_settings_error),
    NOTIFICATION_TEST_ERROR(R.string.notification_test_error),
    NOTIFICATION_TEST_QUEUED(R.string.notification_test_queued),
    NOTIFICATION_TEST_SENT(R.string.notification_test_sent),
    NOTIFICATIONS_ALLOW_SETTINGS(R.string.notifications_allow_settings),
    NOTIFICATIONS_CONNECT_ERROR(R.string.notifications_connect_error),
    NOTIFICATIONS_CONNECTED(R.string.notifications_connected),
    NOTIFICATIONS_CONNECTING(R.string.notifications_connecting),
    NOTIFICATIONS_DENIED(R.string.notifications_denied),
    NOTIFICATIONS_OFF(R.string.notifications_off),
    NOTIFICATIONS_SAVE_ERROR(R.string.notifications_save_error),
    NOTIFICATIONS_SCHEDULE_ERROR(R.string.notifications_schedule_error),
    NOTIFICATIONS_SYSTEM_BLOCKED(R.string.notifications_system_blocked),
    OPERATION_FAILED(R.string.operation_failed),
    PERMISSION_ERROR(R.string.permission_error),
    RECORD_MISSING(R.string.record_missing),
    REQUEST_RATE_LIMIT(R.string.request_rate_limit),
    REQUEST_TIMEOUT(R.string.request_timeout),
    SERVER_UNAVAILABLE(R.string.server_unavailable),
    SESSION_EXPIRED(R.string.session_expired),
    SESSION_NETWORK_ERROR(R.string.session_network_error),
    SESSION_REFRESH_ERROR(R.string.session_refresh_error),
    SESSION_VERIFICATION_ERROR(R.string.session_verification_error),
    SETTINGS_LOAD_ERROR(R.string.settings_load_error),
    SETTINGS_SAVE_ERROR(R.string.settings_save_error),
    SETTINGS_SAVED(R.string.settings_saved),
    SETTINGS_STORAGE_ERROR(R.string.settings_storage_error),
    SIGN_IN_AGAIN(R.string.sign_in_again),
    SUBJECT_DELETE_ERROR(R.string.subject_delete_error),
    SUBJECT_DUPLICATE(R.string.subject_duplicate),
    SUBJECT_HAS_TASKS(R.string.subject_has_tasks),
    SUBJECT_MISSING(R.string.subject_missing),
    SUBJECT_NAME_REQUIRED(R.string.subject_name_required),
    SUBJECT_NAME_TOO_LONG(R.string.subject_name_too_long),
    SUBJECT_REQUIRED(R.string.subject_required),
    SUBJECT_SAVE_ERROR(R.string.subject_save_error),
    SUBJECTS_LOAD_ERROR(R.string.subjects_load_error),
    SYNC_CHANGES_SAVED(R.string.sync_changes_saved),
    SYNC_COMPLETED(R.string.sync_completed),
    SYNC_DISCARDED(R.string.sync_discarded),
    SYNC_FAILED(R.string.sync_failed),
    SYNC_FAILED_SAVED(R.string.sync_failed_saved),
    SYNC_MORE_PENDING(R.string.sync_more_pending),
    SYNC_NEEDS_ATTENTION(R.string.sync_needs_attention),
    SYNC_REQUESTED(R.string.sync_requested),
    SYNC_START_ERROR(R.string.sync_start_error),
    SYNC_STARTED(R.string.sync_started),
    SYNC_STATUS_ERROR(R.string.sync_status_error),
    SYNC_UPDATE_ERROR(R.string.sync_update_error),
    TASK_DELETE_ERROR(R.string.task_delete_error),
    TASK_MISSING(R.string.task_missing),
    TASK_SAVE_ERROR(R.string.task_save_error),
    TASK_TITLE_REQUIRED(R.string.task_title_required),
    TASK_TITLE_TOO_LONG(R.string.task_title_too_long),
    TASK_UPDATE_ERROR(R.string.task_update_error),
    TASKS_LOAD_ERROR(R.string.tasks_load_error);

    companion object {
        fun find(context: Context, value: String): AppMessage? {
            entries.find { it.name == value }?.let { return it }
            // Older installs and API responses can still contain English messages.
            val english = AppLanguage.context(context, "en")
            return entries.find { english.getString(it.resourceId) == value.replace("...", "…") }
        }
    }
}

fun messageText(context: Context, value: String): String {
    if (value.isBlank()) return ""
    val message = AppMessage.find(context, value)
    return context.getString(message?.resourceId ?: R.string.operation_failed)
}

fun rejectionText(context: Context, value: String): String {
    val separator = value.lastIndexOf(": ")
    if (separator < 0) return messageText(context, value)
    val label = value.substring(0, separator)
    val translatedLabel = if (
        label == AppMessage.DELETED_ITEM.name ||
        label == AppLanguage.context(context, "en").getString(R.string.deleted_item)
    ) context.getString(R.string.deleted_item) else label
    return context.getString(
        R.string.rejection_message,
        translatedLabel,
        messageText(context, value.substring(separator + 2))
    )
}
