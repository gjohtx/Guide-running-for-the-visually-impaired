package com.example.guiderunningfortheblind.di

import android.content.Context
import com.example.guiderunningfortheblind.BuildConfig
import com.example.guiderunningfortheblind.MainApplication
import com.example.guiderunningfortheblind.ai.dashscope.DashScopeService
import com.example.guiderunningfortheblind.data.local.AppDatabase
import com.example.guiderunningfortheblind.data.local.dao.RunningPlanDao
import com.example.guiderunningfortheblind.data.local.dao.RunningSessionDao
import com.example.guiderunningfortheblind.data.local.dao.SafetyEventDao
import com.example.guiderunningfortheblind.data.local.dao.UserProfileDao
import com.example.guiderunningfortheblind.data.remote.api.RunningApiService
import com.example.guiderunningfortheblind.data.repository.RunningSessionRepository
import com.example.guiderunningfortheblind.data.repository.RunningRepository
import com.example.guiderunningfortheblind.data.repository.UserProfileRepository
import com.example.guiderunningfortheblind.location.LocationManager
import com.example.guiderunningfortheblind.navigation.NavigationManager
import com.example.guiderunningfortheblind.speech.*
import android.os.Vibrator
import com.example.guiderunningfortheblind.camera.ObstacleDetector
import com.example.guiderunningfortheblind.health.HealthConnectManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.reflect.Type
import javax.inject.Singleton
import javax.inject.Named

/**
 * Hilt 依赖注入模块
 *
 * 【迁移说明】2026-05-27：AI 服务从 Gemini 迁移至通义千问 DashScope
 * - 统一使用 OpenAI 兼容端点 compatible-mode/v1/chat/completions
 * - 自定义 Gson 序列化器确保 content: Any 正确序列化
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getDatabase(context)

    @Provides @Singleton
    fun provideRunningPlanDao(database: AppDatabase): RunningPlanDao =
        database.runningPlanDao()

    @Provides @Singleton
    fun provideRunningSessionDao(database: AppDatabase): RunningSessionDao =
        database.runningSessionDao()

    @Provides @Singleton
    fun provideSafetyEventDao(database: AppDatabase): SafetyEventDao =
        database.safetyEventDao()

    @Provides @Singleton
    fun provideUserProfileDao(database: AppDatabase): UserProfileDao =
        database.userProfileDao()

    @Provides @Singleton
    fun provideRetrofit(): Retrofit = Retrofit.Builder()
        .baseUrl("https://jsonplaceholder.typicode.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides @Singleton
    fun provideRunningApiService(retrofit: Retrofit): RunningApiService =
        retrofit.create(RunningApiService::class.java)

    @Provides @Singleton
    fun provideRunningSessionRepository(
        sessionDao: RunningSessionDao,
        safetyEventDao: SafetyEventDao,
        apiService: RunningApiService
    ): RunningSessionRepository = RunningSessionRepository(sessionDao, safetyEventDao, apiService)


    @Provides @Singleton
    fun provideRunningRepository(dao: RunningPlanDao, api: RunningApiService): RunningRepository =
        RunningRepository(dao, api)

    @Provides @Singleton
    fun provideUserProfileRepository(dao: UserProfileDao, api: RunningApiService): UserProfileRepository =
        UserProfileRepository(dao, api)

    @Provides @Singleton
    fun provideLocationManager(@ApplicationContext context: Context): LocationManager =
        LocationManager(context)

    // ── 语音相关：全部委托给 MainApplication 统一实例，保证单例唯一 ──
    @Provides @Singleton
    fun provideVoiceQueueManager(@ApplicationContext context: Context): VoiceQueueManager =
        (context as MainApplication).voiceQueueManager

    @Provides @Singleton
    fun provideVoiceCommandManager(@ApplicationContext context: Context): VoiceCommandManager =
        (context as MainApplication).voiceCommandManager

    @Provides @Singleton
    fun provideVoiceCoordinator(@ApplicationContext context: Context): VoiceCoordinator =
        (context as MainApplication).voiceCoordinator

    @Provides @Singleton
    fun provideNavigationAnnouncer(@ApplicationContext context: Context): NavigationAnnouncer =
        (context as MainApplication).navigationAnnouncer

    @Provides @Singleton
    fun provideNavigationManager(@ApplicationContext context: Context): NavigationManager =
        (context as MainApplication).navigationManager

    @Provides @Singleton
    fun provideNavigationVoiceCommandHandler(@ApplicationContext context: Context): NavigationVoiceCommandHandler =
        (context as MainApplication).navigationVoiceCommandHandler

    @Provides @Singleton
    fun provideHealthConnectManager(@ApplicationContext context: Context): HealthConnectManager =
        HealthConnectManager(context)

    @Provides @Singleton
    fun provideObstacleDetector(@ApplicationContext context: Context): ObstacleDetector =
        ObstacleDetector(context)

    @Provides @Singleton
    fun provideVibrator(@ApplicationContext context: Context): Vibrator =
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    // ═══════════════════════════════════════════════════════════
    //  【迁移】通义千问 DashScope 配置
    // ═══════════════════════════════════════════════════════════

    /**
     * DashScope 专用 Retrofit 实例
     *
     * Base URL: https://dashscope.aliyuncs.com/
     * 统一端点：compatible-mode/v1/chat/completions
     *
     * 自定义 Gson 序列化器确保 content: Any 正确序列化：
     * - String -> JSON 字符串
     * - List<ContentPart> -> JSON 数组
     */
    @Provides
    @Singleton
    @Named("dashscope")
    fun provideDashScopeRetrofit(): Retrofit {
        val contentType = object : TypeToken<Any>() {}.type

        val gson: Gson = GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(contentType, object : JsonSerializer<Any> {
                override fun serialize(
                    src: Any?,
                    typeOfSrc: Type,
                    context: JsonSerializationContext
                ): JsonElement {
                    return when (src) {
                        is String -> com.google.gson.JsonPrimitive(src)
                        is List<*> -> {
                            val array = com.google.gson.JsonArray()
                            src.forEach { item ->
                                if (item != null) {
                                    array.add(context.serialize(item))
                                }
                            }
                            array
                        }
                        else -> context.serialize(src)
                    }
                }
            })
            .create()

        return Retrofit.Builder()
            .baseUrl("https://dashscope.aliyuncs.com/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideDashScopeService(
        @Named("dashscope") retrofit: Retrofit
    ): DashScopeService = retrofit.create(DashScopeService::class.java)

    // ═══════════════════════════════════════════════════════════
    //  【新增】AI 跑步教练依赖
    // ═══════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideRunningCoachRepository(
        runningPlanDao: RunningPlanDao,
        userProfileDao: UserProfileDao,
        runningSessionDao: RunningSessionDao
    ): com.example.guiderunningfortheblind.data.repository.RunningCoachRepository =
        com.example.guiderunningfortheblind.data.repository.RunningCoachRepository(
            runningPlanDao, userProfileDao, runningSessionDao
        )

    /**
     * DashScope API Key
     *
     * 在 local.properties 中添加：
     *   dashscope_api_key=sk-xxxxxxxxxxxxxxxx
     *
     * Key 获取：https://bailian.console.aliyun.com/
     */
    @Provides
    @Singleton
    @Named("dashscope_api_key")
    fun provideDashScopeApiKey(): String {
        val apiKey = BuildConfig.DASHSCOPE_API_KEY
        if (apiKey.isBlank() || apiKey.startsWith("YOUR_")) {
            android.util.Log.e(
                "AppModule",
                "DASHSCOPE_API_KEY 未配置或无效，AI 功能将不可用。" +
                        "请在 local.properties 中配置 dashscope_api_key=sk-xxxx"
            )
        }
        return apiKey
    }
}
