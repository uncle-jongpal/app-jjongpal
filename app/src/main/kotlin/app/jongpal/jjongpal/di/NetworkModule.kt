package app.jongpal.jjongpal.di

import app.jongpal.jjongpal.BuildConfig
import app.jongpal.jjongpal.auth.AuthApi
import app.jongpal.jjongpal.auth.AuthInterceptor
import app.jongpal.jjongpal.data.remote.PcApi
import app.jongpal.jjongpal.data.remote.UploadApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Provides @Singleton
    fun provideHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()

    @Provides @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PC_BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides @Singleton
    fun providePcApi(retrofit: Retrofit): PcApi = retrofit.create(PcApi::class.java)

    /**
     * 업로드 전용 통신 설정 — 큰 통화 녹음(수십 메가) 대비 제한시간을 길게.
     * 기존 5분 제한으로는 83MB 녹음이 시간 초과로 실패했다 (2026-07-30 사례).
     */
    @Provides @Singleton @Named("upload")
    fun provideUploadHttpClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.MINUTES)
            .callTimeout(45, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .build()

    @Provides @Singleton
    fun provideUploadApi(@Named("upload") client: OkHttpClient, moshi: Moshi): UploadApi =
        Retrofit.Builder()
            .baseUrl(BuildConfig.PC_BASE_URL.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(UploadApi::class.java)
}
