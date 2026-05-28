package com.example.guiderunningfortheblind.ui.running

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guiderunningfortheblind.ai.AiRunningCoach
import com.example.guiderunningfortheblind.ai.AiSceneAnalyzer
import com.example.guiderunningfortheblind.ai.dashscope.ChatCompletionRequest
import com.example.guiderunningfortheblind.ai.dashscope.ChatMessageDto
import com.example.guiderunningfortheblind.ai.dashscope.DashScopeService
import com.example.guiderunningfortheblind.model.ChatMessage
import com.example.guiderunningfortheblind.speech.VoiceQueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Named

/**
 * AI 聊天 ViewModel - AI跑步教练集成版
 *
 * 【2026-05-28 集成】
 * 1. 注入 AiRunningCoach，每5秒自动获取三表数据生成跑步提示
 * 2. 收集 coachTip Flow，将AI教练提示显示到对话框并语音播报
 * 3. 暴露 startCoach/stopCoach/updateCoachData 供 RunningFragment 调用
 * 4. 基于 scene_fix 版本，保留所有场景分析和对话功能
 */
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val dashScopeService: DashScopeService,
    @param:Named("dashscope_api_key") private val apiKey: String,
    private val voiceQueue: VoiceQueueManager,
    private val aiSceneAnalyzer: AiSceneAnalyzer,
    private val aiRunningCoach: AiRunningCoach
) : ViewModel() {

    companion object {
        private const val TAG = "AiChatViewModel"

        /** 场景播报冷却期：3秒 */
        private const val SCENE_SPEAK_COOLDOWN_MS = 3_000L
        /** 环境概述间隔：30秒 */
        private const val ENVIRONMENT_INTERVAL = 30_000L
        /** 用户说话后静音期：5秒 */
        private const val USER_SILENCE_MS = 5_000L

        private const val SIMILARITY_THRESHOLD = 0.65f
        private const val RECENT_BUFFER_SIZE = 3

        private const val MODEL_CHAT = "qwen-plus"

        private val SYSTEM_PROMPT = """
            你是专为视障跑步者设计的智能助手。用简洁友好的中文回答，不超过50字。
            回答要实用、安全导向，帮助用户了解跑步环境和路况。
        """.trimIndent()
    }

    // =========================================================
    //  聊天消息
    // =========================================================

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _runningState = MutableStateFlow<RunningUiState?>(null)
    fun setRunningState(state: RunningUiState?) { _runningState.value = state }

    // =========================================================
    //  语音识别状态（UI层通过collectAsState观察）
    // =========================================================

    @Suppress("unused")
    private val _isRecognizing = MutableStateFlow(false)
    @Suppress("unused")
    val isRecognizing: StateFlow<Boolean> = _isRecognizing.asStateFlow()

    @Suppress("unused")
    private val _lastRecognizedText = MutableStateFlow<String?>(null)
    @Suppress("unused")
    val lastRecognizedText: StateFlow<String?> = _lastRecognizedText.asStateFlow()

    @Suppress("unused")
    private val _recognitionHint = MutableStateFlow<String?>(null)
    @Suppress("unused")
    val recognitionHint: StateFlow<String?> = _recognitionHint.asStateFlow()

    // =========================================================
    //  场景分析状态
    // =========================================================

    private var lastSceneSpeakTime = 0L
    private var lastEnvironmentTime = 0L
    private var lastUserMessageTime = 0L
    private val recentDescriptions = ArrayDeque<String>(RECENT_BUFFER_SIZE)

    private var sceneCollectionJob: Job? = null

    @Volatile
    private var aiQuotaExceeded = false
    private var quotaResetTime = 0L

    private var frameCounter = 0

    // =========================================================
    //  【新增】AI跑步教练
    // =========================================================

    /** 教练是否已启动 */
    @Volatile
    private var isCoachStarted = false

    /**
     * 【新增】启动AI跑步教练
     *
     * 在跑步开始时调用，启动5秒间隔的AI提示生成。
     * 重复调用会被忽略（通过 isCoachStarted 保护）。
     */
    fun startCoach(planId: String? = null) {
        if (isCoachStarted) {
            Log.w(TAG, "【AI教练】已在运行中，忽略重复启动请求")
            return
        }
        isCoachStarted = true
        aiRunningCoach.start(planId)
        Log.i(TAG, "【AI教练】已启动 planId=$planId")
    }

    /**
     * 【新增】停止AI跑步教练
     *
     * 在跑步结束时调用，停止定时提示生成。
     */
    fun stopCoach() {
        isCoachStarted = false
        aiRunningCoach.stop()
        Log.i(TAG, "【AI教练】已停止")
    }

    /**
     * 【新增】更新AI教练的实时数据
     *
     * 由 RunningFragment 在每次 uiState 变化时调用，
     * 让教练获得最新的距离、配速、心率数据。
     */
    fun updateCoachData(
        distance: Double,
        pace: String,
        heartRate: Int = 0,
        cadence: Int = 0
    ) {
        aiRunningCoach.updateRealtimeData(distance, pace, heartRate, cadence)
    }

    // =========================================================
    //  【新增】收集AI教练提示并显示到对话框
    // =========================================================

    init {
        viewModelScope.launch {
            aiRunningCoach.coachTip.collect { tip ->
                if (tip != null && tip.isNotBlank()) {
                    Log.i(TAG, "【AI教练提示】$tip")
                    // 添加【教练】前缀，区分于场景分析消息
                    addMessage(ChatMessage(ChatMessage.Role.AI, "【教练】$tip"))
                    // 语音播报在 AiRunningCoach 内部已经做了，
                    // 但为了确保对话框和语音同步，这里也播报一次（VoiceQueueManager会排队）
                    launch {
                        try {
                            voiceQueue.speak(tip)
                        } catch (e: Exception) {
                            Log.e(TAG, "【AI教练语音播报失败】", e)
                        }
                    }
                }
            }
        }
    }

    // =========================================================
    //  公开 API
    // =========================================================

    /**
     * 收集摄像头帧流进行场景分析
     */
    fun collectSceneFrames(frameFlow: Flow<Bitmap>) {
        sceneCollectionJob?.cancel()
        sceneCollectionJob = viewModelScope.launch {
            frameFlow.collect { bitmap ->
                supervisorScope {
                    launch {
                        try {
                            handleSceneFrame(bitmap)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "【帧处理异常】frame#$frameCounter", e)
                        }
                    }
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * 发送用户消息
     */
    fun sendUserMessage(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "sendUserMessage: 文字为空，忽略")
            return
        }

        lastUserMessageTime = System.currentTimeMillis()
        _lastRecognizedText.value = text
        addMessage(ChatMessage(ChatMessage.Role.USER, text))
        Log.i(TAG, "【发送用户消息】$text")

        viewModelScope.launch {
            val reply = try {
                generateChatReply(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "【生成回复异常】${e.javaClass.simpleName}: ${e.message}", e)
                "AI 服务异常，请稍后再试。"
            }

            addMessage(ChatMessage(ChatMessage.Role.AI, reply))

            launch {
                try {
                    voiceQueue.speak(reply)
                } catch (e: Exception) {
                    Log.e(TAG, "【语音播报失败】", e)
                }
            }
        }
    }

    @Suppress("unused")
    fun onVoiceRecognitionResult(text: String) {
        Log.i(TAG, "【语音输入结果】$text")
        _lastRecognizedText.value = text
        sendUserMessage(text)
    }

    @Suppress("unused")
    fun onVoiceRecognitionNoMatch() {
        Log.w(TAG, "语音未识别到文字")
        _recognitionHint.value = "未识别到文字"
        viewModelScope.launch {
            delay(2000)
            _recognitionHint.value = null
        }
    }

    @Suppress("unused")
    fun onVoiceRecognitionError(errorMessage: String) {
        Log.e(TAG, "【语音识别错误】$errorMessage")
        _recognitionHint.value = errorMessage
        viewModelScope.launch {
            delay(2000)
            _recognitionHint.value = null
        }
    }

    @Suppress("unused")
    fun setRecognizing(recognizing: Boolean) {
        _isRecognizing.value = recognizing
        if (recognizing) {
            _recognitionHint.value = null
        }
    }

    // =========================================================
    //  场景帧处理
    // =========================================================

    private suspend fun handleSceneFrame(bitmap: Bitmap) {
        frameCounter++
        val now = System.currentTimeMillis()
        val frameId = frameCounter

        if (now - lastUserMessageTime < USER_SILENCE_MS) {
            return
        }

        if (aiQuotaExceeded && now < quotaResetTime) {
            Log.d(TAG, "【帧#$frameId】API配额冷却中，跳过")
            return
        }

        // 步骤1：场景路况分析
        if (now - lastSceneSpeakTime >= SCENE_SPEAK_COOLDOWN_MS) {
            Log.d(TAG, "【帧#$frameId】开始场景分析")

            val description = try {
                aiSceneAnalyzer.analyzeScene(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "【帧#$frameId】场景分析异常: ${e.message}")
                null
            }

            if (description == null) {
                Log.d(TAG, "【帧#$frameId】场景分析无结果，跳过播报")
            } else if (isSimilarToRecent(description)) {
                Log.d(TAG, "【帧#$frameId】场景描述与最近相似，跳过播报: $description")
            } else {
                Log.i(TAG, "【帧#$frameId】场景分析结果: $description")
                lastSceneSpeakTime = now
                recordDescription(description)
                addMessage(ChatMessage(ChatMessage.Role.AI, description))

                viewModelScope.launch {
                    try {
                        voiceQueue.speak(description)
                    } catch (e: Exception) {
                        Log.e(TAG, "【帧#$frameId】路况播报失败", e)
                    }
                }
            }
        }

        // 步骤2：环境概述分析
        if (now - lastEnvironmentTime >= ENVIRONMENT_INTERVAL) {
            Log.d(TAG, "【帧#$frameId】开始环境分析")
            lastEnvironmentTime = now

            val envDesc = try {
                aiSceneAnalyzer.analyzeEnvironment(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "【帧#$frameId】环境分析异常: ${e.message}")
                null
            }

            if (envDesc != null) {
                Log.i(TAG, "【帧#$frameId】环境分析结果: $envDesc")
                addMessage(ChatMessage(ChatMessage.Role.AI, "【环境】$envDesc"))

                viewModelScope.launch {
                    try {
                        voiceQueue.speak(envDesc)
                    } catch (e: Exception) {
                        Log.e(TAG, "【帧#$frameId】环境播报失败", e)
                    }
                }
            } else {
                Log.d(TAG, "【帧#$frameId】环境分析无结果")
            }
        }
    }

    // =========================================================
    //  相似性检测
    // =========================================================

    private fun isSimilarToRecent(text: String): Boolean {
        val newBigrams = text.cleanForSimilarity().windowed(2).toSet()
        if (newBigrams.isEmpty()) return false
        return recentDescriptions.any { prev ->
            val prevBigrams = prev.cleanForSimilarity().windowed(2).toSet()
            val union = newBigrams.union(prevBigrams)
            if (union.isEmpty()) return@any false
            newBigrams.intersect(prevBigrams).size.toFloat() / union.size >= SIMILARITY_THRESHOLD
        }
    }

    private fun recordDescription(text: String) {
        if (recentDescriptions.size >= RECENT_BUFFER_SIZE) recentDescriptions.removeFirst()
        recentDescriptions.addLast(text)
    }

    private fun String.cleanForSimilarity(): String =
        replace(Regex("[，。、 约左右侧米前]"), "")

    // =========================================================
    //  AI 对话
    // =========================================================

    private suspend fun generateChatReply(userInput: String): String {
        val now = System.currentTimeMillis()
        if (aiQuotaExceeded && now < quotaResetTime) {
            return "AI 服务当前较忙，请稍后对话。跑步数据仍在记录中，请注意安全。"
        }

        val state = _runningState.value
        val userPrompt = buildString {
            appendLine("当前跑步数据：")
            if (state != null) {
                append("距离${state.currentDistance.toInt()}米，")
                append("配速${state.currentPace}，")
                append("心率${state.currentHeartRate ?: "未知"}。")
            } else {
                append("暂无数据。")
            }
            appendLine()
            appendLine("用户问题：$userInput")
            appendLine("请简洁回答，不超过50字。")
        }

        return try {
            val request = ChatCompletionRequest(
                model = MODEL_CHAT,
                messages = listOf(
                    ChatMessageDto(role = "system", content = SYSTEM_PROMPT),
                    ChatMessageDto(role = "user", content = userPrompt)
                )
            )

            Log.d(TAG, "【对话请求】model=$MODEL_CHAT")
            val response = dashScopeService.chatCompletions("Bearer $apiKey", request)
            Log.d(TAG, "【对话响应】choices=${response.choices?.size}")

            response.choices?.firstOrNull()?.message?.content?.trim()
                ?: "抱歉，我没能理解您的问题，请再说一次。"

        } catch (e: HttpException) {
            val code = e.code()
            Log.e(TAG, "【对话HTTP错误】$code")
            if (code == 429) {
                aiQuotaExceeded = true
                quotaResetTime = System.currentTimeMillis() + 90_000L
                "AI 服务配额暂时耗尽。请专注跑步安全。"
            } else {
                "AI 服务暂时不可用（HTTP $code），请稍后再试。"
            }
        } catch (e: Exception) {
            Log.e(TAG, "【对话异常】${e.javaClass.simpleName}: ${e.message}")
            "AI 服务暂时不可用，请稍后再试。"
        }
    }

    // =========================================================
    //  消息管理
    // =========================================================

    private fun addMessage(msg: ChatMessage) {
        _messages.update { current -> (current + msg).takeLast(50) }
        Log.d(TAG, "【消息已添加】${msg.role}: ${msg.content.take(20)}... 总消息数: ${_messages.value.size}")
    }

    override fun onCleared() {
        super.onCleared()
        sceneCollectionJob?.cancel()
        // 【新增】停止AI教练，释放资源
        stopCoach()
        aiRunningCoach.destroy()
    }
}
