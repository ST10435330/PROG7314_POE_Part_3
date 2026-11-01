package com.studysync.app

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

data class SubjectRequest(
    val name: String,
    val lecturerName: String
)

data class TaskRequest(
    val subjectId: String,
    val title: String,
    val description: String,
    val dueDate: String,
    val priority: String,
    val completed: Boolean
)

interface StudyApi {
    @GET("subjects")
    suspend fun getSubjects(
        @Header("Authorization") authorization: String
    ): Response<List<Subject>>

    @POST("subjects")
    suspend fun createSubject(
        @Header("Authorization") authorization: String,
        @Body subject: SubjectRequest
    ): Response<Subject>

    @PATCH("subjects/{id}")
    suspend fun updateSubject(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body subject: SubjectRequest
    ): Response<Subject>

    @DELETE("subjects/{id}")
    suspend fun deleteSubject(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Header("X-Operation-Id") operationId: String? = null
    ): Response<Void>

    @GET("tasks")
    suspend fun getTasks(
        @Header("Authorization") authorization: String
    ): Response<List<StudyTask>>

    @POST("tasks")
    suspend fun createTask(
        @Header("Authorization") authorization: String,
        @Body task: TaskRequest
    ): Response<StudyTask>

    @PATCH("tasks/{id}")
    suspend fun updateTask(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body task: TaskRequest
    ): Response<StudyTask>

    @DELETE("tasks/{id}")
    suspend fun deleteTask(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Header("X-Operation-Id") operationId: String? = null
    ): Response<Void>

    @PUT("subjects/{id}")
    suspend fun putSubject(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Header("X-Operation-Id") operationId: String,
        @Body subject: SubjectRequest
    ): Response<Subject>

    @PUT("tasks/{id}")
    suspend fun putTask(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Header("X-Operation-Id") operationId: String,
        @Body task: TaskRequest
    ): Response<StudyTask>
}

object StudyApiClient {
    private const val BASE_URL =
        "https://prog7314-poe-part-2.onrender.com/api/"

    val service: StudyApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StudyApi::class.java)
    }
}