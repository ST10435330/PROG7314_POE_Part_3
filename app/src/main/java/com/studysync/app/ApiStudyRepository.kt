package com.studysync.app

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException

class ApiException(
    message: String,
    val statusCode: Int? = null,
    val retryable: Boolean = false
) : Exception(message)

fun apiErrorMessage(
    exception: Exception,
    fallback: String
): String {
    return when (exception) {
        is ApiException,
        is IllegalArgumentException -> exception.message ?: fallback

        else -> fallback
    }
}

class ApiStudyRepository(
    private val userId: String,
    private val api: StudyApi = StudyApiClient.service,
    private val currentUserId: () -> String? = {
        FirebaseAuth.getInstance().currentUser?.uid
    },
    private val tokenProvider: suspend (Boolean) -> String? = { forceRefresh ->
        FirebaseAuth.getInstance().currentUser
            ?.takeIf { it.uid == userId }
            ?.getIdToken(forceRefresh)
            ?.await()
            ?.token
    }
) : StudyRepository {
    private val gson = Gson()

    override suspend fun getSubjects(): List<Subject> {
        return body { api.getSubjects(it) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun createSubject(subject: Subject): Subject {
        return body {
            api.createSubject(it, subject.toRequest())
        }
    }

    override suspend fun updateSubject(subject: Subject): Subject {
        return body {
            api.updateSubject(
                it,
                subject.subjectId,
                subject.toRequest()
            )
        }
    }

    override suspend fun deleteSubject(subjectId: String) {
        execute {
            api.deleteSubject(it, subjectId)
        }
    }

    override suspend fun getTasks(): List<StudyTask> {
        return body { api.getTasks(it) }
    }

    override suspend fun createTask(task: StudyTask): StudyTask {
        return body {
            api.createTask(it, task.toRequest())
        }
    }

    override suspend fun updateTask(task: StudyTask): StudyTask {
        return body {
            api.updateTask(
                it,
                task.taskId,
                task.toRequest()
            )
        }
    }

    override suspend fun deleteTask(taskId: String) {
        execute {
            api.deleteTask(it, taskId)
        }
    }

    /**
     * Sends an immutable outbox operation.
     * The sync worker will acknowledge it after successful completion.
     */
    suspend fun upload(change: PendingStudyChange) {
        require(change.kind in listOf("subjects", "tasks")) {
            "Unknown record type."
        }

        require(change.method in listOf("PUT", "DELETE")) {
            "Unknown change method."
        }

        val validId = Regex("[A-Za-z0-9_-]{1,128}")

        require(
            change.recordId.matches(validId) &&
                    change.operationId.matches(validId)
        ) {
            "Invalid saved change ID."
        }

        if (change.method == "DELETE") {
            require(change.payload == null) {
                "A deletion cannot contain a payload."
            }

            execute { authorization ->
                if (change.kind == "subjects") {
                    api.deleteSubject(
                        authorization,
                        change.recordId,
                        change.operationId
                    )
                } else {
                    api.deleteTask(
                        authorization,
                        change.recordId,
                        change.operationId
                    )
                }
            }
        } else if (change.kind == "subjects") {
            val subject = gson.fromJson(
                checkNotNull(change.payload),
                Subject::class.java
            )

            require(subject.subjectId == change.recordId) {
                "Subject ID does not match."
            }

            val saved = body { authorization ->
                api.putSubject(
                    authorization,
                    change.recordId,
                    change.operationId,
                    subject.toRequest()
                )
            }

            if (saved.subjectId != change.recordId) {
                throw ApiException(
                    "The API returned a different subject ID.",
                    retryable = true
                )
            }
        } else {
            val task = gson.fromJson(
                checkNotNull(change.payload),
                StudyTask::class.java
            )

            require(task.taskId == change.recordId) {
                "Task ID does not match."
            }

            val saved = body { authorization ->
                api.putTask(
                    authorization,
                    change.recordId,
                    change.operationId,
                    task.toRequest()
                )
            }

            if (saved.taskId != change.recordId) {
                throw ApiException(
                    "The API returned a different task ID.",
                    retryable = true
                )
            }
        }
    }

    private suspend fun authorization(
        forceRefresh: Boolean
    ): String {
        if (currentUserId() != userId) {
            throw ApiException(
                "Please sign in again.",
                statusCode = 401
            )
        }

        val token = try {
            tokenProvider(forceRefresh)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: FirebaseNetworkException) {
            throw ApiException(
                "Could not refresh your session. Check your internet connection.",
                retryable = true
            )
        } catch (exception: IOException) {
            throw ApiException(
                "Your session could not be refreshed. Try again later.",
                retryable = true
            )
        } catch (exception: Exception) {
            throw ApiException(
                "Your session could not be verified. Sign out and sign in again.",
                statusCode = 401
            )
        }

        if (currentUserId() != userId || token.isNullOrBlank()) {
            throw ApiException(
                "Please sign in again.",
                statusCode = 401
            )
        }

        return "Bearer $token"
    }

    private suspend fun <T> body(
        request: suspend (String) -> Response<T>
    ): T {
        return execute(request).body()
            ?: throw ApiException(
                "The API returned an empty response.",
                retryable = true
            )
    }

    private suspend fun <T> execute(
        request: suspend (String) -> Response<T>
    ): Response<T> {
        try {
            var response = request(
                authorization(forceRefresh = false)
            )

            // A rejected token means the protected operation did not execute.
            if (response.code() == 401) {
                response.errorBody()?.close()

                response = request(
                    authorization(forceRefresh = true)
                )
            }

            if (currentUserId() != userId) {
                response.errorBody()?.close()

                throw ApiException(
                    "Your account changed. Please sign in again.",
                    statusCode = 401
                )
            }

            Log.d(
                "StudySync",
                "API response: ${response.code()}"
            )

            if (!response.isSuccessful) {
                throw ApiException(
                    responseMessage(response),
                    statusCode = response.code(),
                    retryable =
                        response.code() in listOf(408, 429) ||
                                response.code() in 500..599
                )
            }

            return response
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ApiException) {
            throw exception
        } catch (exception: JsonParseException) {
            throw ApiException(
                "The API returned an unexpected response.",
                retryable = true
            )
        } catch (exception: IOException) {
            throw ApiException(
                "Connection unavailable. Saved changes can be retried.",
                retryable = true
            )
        }
    }

    private fun responseMessage(
        response: Response<*>
    ): String {
        val fallback = when (response.code()) {
            401 ->
                "Your session has expired. Sign out and sign in again."

            403 ->
                "You do not have permission to perform this action."

            404 ->
                "This record is no longer available. Refresh the list."

            409 ->
                "This change conflicts with existing data."

            408 ->
                "The request timed out. Please try again."

            429 ->
                "Too many requests. Sync can try again shortly."

            in 500..599 ->
                "The server is unavailable. Please try again shortly."

            else ->
                "The request failed (${response.code()})."
        }

        val raw = response.errorBody()?.string()

        if (response.code() in listOf(400, 404, 409)) {
            val message = runCatching {
                JSONObject(raw ?: "{}")
                    .getJSONObject("error")
                    .getString("message")
            }.getOrNull()

            if (!message.isNullOrBlank() && message.length <= 250) {
                return message
            }
        }

        return fallback
    }

    private fun Subject.toRequest() = SubjectRequest(
        name = name.trim(),
        lecturerName = lecturerName.trim()
    )

    private fun StudyTask.toRequest() = TaskRequest(
        subjectId = subjectId,
        title = title.trim(),
        description = description.trim(),
        dueDate = dueDate.trim(),
        priority = priority,
        completed = completed
    )
}