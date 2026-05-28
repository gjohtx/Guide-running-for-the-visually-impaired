package com.example.guiderunningfortheblind.data.repository

import android.util.Log
import com.example.guiderunningfortheblind.data.local.dao.RunningSessionDao
import com.example.guiderunningfortheblind.data.local.dao.SafetyEventDao
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.local.entity.SafetyEventEntity
import com.example.guiderunningfortheblind.data.remote.api.RunningApiService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import retrofit2.HttpException

class RunningSessionRepository(
    private val sessionDao: RunningSessionDao,
    private val safetyEventDao: SafetyEventDao,
    private val api: RunningApiService,
    // 外部传入的协程作用域，避免 GlobalScope
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    companion object {
        private const val TAG = "RunningSessionRepo"
    }

    val allSessions: Flow<List<RunningSessionEntity>> = sessionDao.getAllSessions()

    suspend fun getSessionById(sessionId: Long): RunningSessionEntity? {
        return sessionDao.getSessionById(sessionId)
    }

    suspend fun refreshSessions() {
        if (AppConfig.IS_OFFLINE_MODE) {
            Log.d(TAG, "离线模式：跳过远程会话同步")
            return
        }
        try {
            val remoteSessions = api.fetchSessions()
            remoteSessions.forEach { sessionDao.insertSession(it) }
            Log.d(TAG, "成功同步 ${remoteSessions.size} 条远程会话")
        } catch (e: Exception) {
            Log.w(TAG, "远程会话同步失败（可能无网络或后端未启动）: ${e.localizedMessage}")
        }
    }

    suspend fun saveSession(session: RunningSessionEntity): Long {
        val localId = sessionDao.insertSession(session)
        Log.d(TAG, "会话已保存到本地，ID=$localId")

        // 离线模式直接返回，不请求网络
        if (AppConfig.IS_OFFLINE_MODE) {
            Log.d(TAG, "离线模式：跳过上传")
            return localId
        }

        // 使用外部传入的 scope，避免 GlobalScope 内存泄漏
        externalScope.launch {
            try {
                retryWithBackoff(times = 3) {
                    api.uploadSession(session.copy(sessionId = localId))
                }
                Log.d(TAG, "会话上传成功，本地ID=$localId")
            } catch (e: HttpException) {
                val code = e.code()
                val errorBody = e.response()?.errorBody()?.string()
                Log.e(TAG, "会话上传失败 HTTP $code: ${e.message()}, errorBody=$errorBody")
                if (code == 404) {
                    Log.e(TAG, ">>> 请检查 Retrofit baseUrl 和 @POST 路径是否匹配后端接口 <<<")
                }
            } catch (e: Exception) {
                Log.e(TAG, "会话上传失败（非HTTP）: ${e.javaClass.simpleName}: ${e.localizedMessage}")
            }
        }
        return localId
    }

    suspend fun logSafetyEvent(event: SafetyEventEntity) {
        val localId = safetyEventDao.insertEvent(event)
        Log.d(TAG, "安全事件已保存到本地，ID=$localId")

        if (AppConfig.IS_OFFLINE_MODE) return

        externalScope.launch {
            try {
                retryWithBackoff(times = 3) {
                    api.uploadSafetyEvent(event.copy(eventId = localId))
                }
                Log.d(TAG, "安全事件上传成功，本地ID=$localId")
            } catch (e: HttpException) {
                Log.e(TAG, "安全事件上传失败 HTTP ${e.code()}: ${e.message()}")
            } catch (e: Exception) {
                Log.e(TAG, "安全事件上传失败: ${e.javaClass.simpleName}: ${e.localizedMessage}")
            }
        }
    }

    fun getSafetyEvents(sessionId: Long): Flow<List<SafetyEventEntity>> {
        return safetyEventDao.getEventsForSession(sessionId)
    }

    suspend fun refreshSafetyEvents() {
        if (AppConfig.IS_OFFLINE_MODE) return
        try {
            val remoteEvents = api.fetchSafetyEvents()
            remoteEvents.forEach { safetyEventDao.insertEvent(it) }
        } catch (e: Exception) {
            Log.w(TAG, "远程安全事件同步失败: ${e.localizedMessage}")
        }
    }

    private suspend fun <T> retryWithBackoff(times: Int, block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < times - 1) {
                    val delayMs = 1000L * (attempt + 1)
                    Log.d(TAG, "第 ${attempt + 1} 次请求失败，${delayMs}ms 后重试...")
                    delay(delayMs)
                }
            }
        }
        throw lastException ?: IllegalStateException("All retries failed")
    }

    suspend fun deleteSession(sessionId: Long) {
        try {
            sessionDao.deleteSessionById(sessionId)
            Log.d(TAG, "会话已删除，ID=$sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "删除会话失败", e)
        }
    }

    fun shutdown() {
        externalScope.cancel()
        Log.d(TAG, "Repository scope 已释放")
    }
}