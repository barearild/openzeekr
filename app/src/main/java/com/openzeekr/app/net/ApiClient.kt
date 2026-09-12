package com.openzeekr.app.net

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.openzeekr.app.config.ConfigStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the [TspApi]. The base URL is read from config at build time; if the
 * user changes it, call [rebuild]. Interceptor order:
 *   1. HeaderInterceptor  (adds x-api*/device/auth headers)
 *   2. SignInterceptor    (signs the fully-decorated request)
 *   3. logging            (last, so it prints the signed request)
 */
class ApiClient private constructor(private val store: ConfigStore) {

    @Volatile private var retrofit: Retrofit = build()
    @Volatile var api: TspApi = retrofit.create(TspApi::class.java)
        private set

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true }

    private fun build(): Retrofit {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }
        val ok = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor(store))
            .addInterceptor(SignInterceptor(store))
            .addInterceptor(logging)
            .build()

        val base = store.current().baseUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(base)
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    /** Re-create the client after the base URL (or algo) changes. */
    fun rebuild() {
        retrofit = build()
        api = retrofit.create(TspApi::class.java)
    }

    companion object {
        @Volatile private var INSTANCE: ApiClient? = null
        fun get(store: ConfigStore): ApiClient =
            INSTANCE ?: synchronized(this) { INSTANCE ?: ApiClient(store).also { INSTANCE = it } }
    }
}
