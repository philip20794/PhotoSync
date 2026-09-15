package de.photosync.data.remote

import de.photosync.data.local.SecureCredentialStore
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitFactory {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun create(baseUrl: String, credentials: SecureCredentialStore? = null): PhotoSyncApi {
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.SECONDS)
            .apply {
                if (credentials != null) addInterceptor(DeviceTokenInterceptor(credentials))
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PhotoSyncApi::class.java)
    }
}

private class DeviceTokenInterceptor(private val credentials: SecureCredentialStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(
        chain.request().newBuilder().apply {
            if (chain.request().header("Authorization") == null) {
                credentials.readAccessToken()?.let { header("Authorization", "Bearer $it") }
            }
        }.build(),
    )
}
