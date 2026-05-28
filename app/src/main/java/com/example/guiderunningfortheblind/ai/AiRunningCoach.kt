package com.example.guiderunningfortheblind.ai

import android.util.Log
import com.example.guiderunningfortheblind.ai.dashscope.ChatCompletionRequest
import com.example.guiderunningfortheblind.ai.dashscope.ChatMessageDto
import com.example.guiderunningfortheblind.ai.dashscope.DashScopeService
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.data.local.entity.UserProfileEntity
import com.example.guiderunningfortheblind.data.repository.RunningCoachRepository
import com.example.guiderunningfortheblind.speech.VoiceQueueManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * AI 跑步教练 - 距离驱动播报版
 *
 * 【触发方式】距离每增加500米播报一次（取代固定5秒间隔）
 * 【播报内容】已跑距离、剩余距离、当前速度、速度是否适宜
 *
 * 【使用方式】
 * ```kotlin
 * // 跑步开始
 * aiRunningCoach.start(planId = "plan_001")
 *
 * // 每次GPS更新时调用（RunningFragment 在 uiState 变化时自动调用）
 * aiRunningCoach.updateRealtimeData(distance, pace, heartRate, cadence)
 * // 距离跨过500米倍数时自动触发播报
 *
 * // ViewModel 收集 coachTip Flow 显示到对话框
 * viewModelScope.launch {
 *     aiRunningCoach.coachTip.collect { tip ->
 *         tip?.let { addMessage(ChatMessage(ChatMessage.Role.AI, "【教练】$it")) }
 *     }
 * }
 *
 * // 跑步结束
 * aiRunningCoach.stop()
 * ```
 */
@Singleton
class AiRunningCoach @Inject constructor(
    private val coachRepository: RunningCoachRepository,
    private val dashScopeService: DashScopeService,
    @Named("dashscope_api_key") private val apiKey: String,
    private val voiceQueue: VoiceQueueManager
) {
    companion object {
        private const val TAG = "AiRunningCoach"
        private const val MODEL_COACH = "qwen-plus"

        /** 播报距离间隔：500米 */
        private const val DISTANCE_INTERVAL_M = 500.0

        private val COACH_SYSTEM_PROMPT = """
            你是视障跑步者的AI陪跑教练。根据用户当前跑步数据生成简洁的跑步状态播报。

            【必须包含以下信息】
            1. 当前已跑了多少公里（格式：X.X公里）
            2. 距离目标还剩多少公里（如自由跑则说"自由跑模式"）
            3. 当前速度（配速，如"6分30秒"）
            4. 速度是否适宜（与目标配速对比，给出"偏快/偏慢/适宜"的判断和建议）

            【输出要求】
            1. 控制在30个字以内
            2. 语气亲切自然，像专业陪跑员在身边指导
            3. 只输出纯文字，不要格式标记、不要编号、不要换行
            4. 直接输出播报文字，不需要称呼用户

            【示例输出】
            已跑2.5公里，还剩1.5公里到达目标，当前配速6分15秒，速度适宜，继续保持。
            已跑3.0公里，自由跑模式，当前配速5分50秒，速度偏快，建议适当放慢。
            已跑0.5公里，还剩4.5公里到达目标，当前配速7分10秒，速度偏慢，可以稍微加快。
            已跑5.0公里，目标已达成，当前配速6分00秒，速度适宜，状态很棒。
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════════════
    //  提示流（供UI层收集显示到对话框）
    // ═══════════════════════════════════════════════════════════

    private val _coachTip = MutableStateFlow<String?>(null)

    /** AI教练生成的最新提示（UI层collect此Flow显示到对话框） */
    val coachTip: StateFlow<String?> = _coachTip.asStateFlow()

    // ═══════════════════════════════════════════════════════════
    //  实时数据
    // ═══════════════════════════════════════════════════════════

    @Volatile
    private var currentDistance: Double = 0.0

    @Volatile
    private var currentPace: String = "--"

    @Volatile
    private var currentHeartRate: Int = 0

    @Volatile
    private var currentCadence: Int = 0

    private var runStartTime: Long = 0L

    // ═══════════════════════════════════════════════════════════
    //  距离驱动播报状态
    // ═══════════════════════════════════════════════════════════

    /** 上次播报时的距离（米），用于计算是否跨过了500米阈值 */
    @Volatile
    private var lastAnnouncedDistance: Double = 0.0

    /** 正在播报中，防止距离更新时重复触发 */
    @Volatile
    private var isAnnouncing = false

    // ═══════════════════════════════════════════════════════════
    //  协程控制
    // ═══════════════════════════════════════════════════════════

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var planId: String? = null

    @Volatile
    private var isRunning = false


    /**
     * 启动AI跑步教练
     *
     * @param planId 当前跑步计划ID（可为null表示自由跑）
     */
    fun start(planId: String? = null) {
        if (isRunning) {
            Log.w(TAG, "AI教练已在运行中")
            return
        }
        this.planId = planId
        this.isRunning = true
        this.runStartTime = System.currentTimeMillis()
        this.currentDistance = 0.0
        this.lastAnnouncedDistance = 0.0
        this.isAnnouncing = false
        _coachTip.value = null

        Log.i(TAG, "AI跑步教练已启动 planId=$planId 播报间隔=${DISTANCE_INTERVAL_M}米")

        // 播报开始提示
        val startMsg = if (planId != null) "跑步开始，每500米为你播报一次状态，加油。"
        else "自由跑开始，每500米为你播报一次状态，加油。"
//        voiceQueue.speak(startMsg)
        _coachTip.value = "【教练】$startMsg"
    }

    /**
     * 停止AI跑步教练
     */
    fun stop() {
        isRunning = false
        Log.i(TAG, "AI跑步教练已停止")

        // 播报结束总结
        val distKm = (currentDistance / 1000.0).format(1)
        val summary = "跑步结束，共跑${distKm}公里。"
//        voiceQueue.speak(summary)
        _coachTip.value = "【教练】$summary"
    }

    /**
     * 更新实时跑步数据
     *
     * 【核心】每次GPS更新时调用。距离跨过500米倍数时自动触发播报。
     *
     * @param currentDistance 当前总距离（米）
     * @param currentPace 当前配速（如 "6'15\""）
     * @param currentHeartRate 当前心率（可选，默认0）
     * @param currentCadence 当前步频（可选，默认0）
     */
    fun updateRealtimeData(
        currentDistance: Double,
        currentPace: String,
        currentHeartRate: Int = 0,
        currentCadence: Int = 0
    ) {
        if (!isRunning) return

        val prevDistance = this.currentDistance
        this.currentDistance = currentDistance
        this.currentPace = currentPace
        this.currentHeartRate = currentHeartRate
        this.currentCadence = currentCadence

        // 检查是否跨过了500米的倍数阈值
        // 例如：从480米到510米，跨过了500米阈值
        val prevThreshold = (prevDistance / DISTANCE_INTERVAL_M).toInt()
        val currThreshold = (currentDistance / DISTANCE_INTERVAL_M).toInt()

        if (currThreshold > prevThreshold && !isAnnouncing) {
            // 跨过了至少一个500米阈值
            val crossedDistance = currThreshold * DISTANCE_INTERVAL_M
            lastAnnouncedDistance = crossedDistance
            Log.i(TAG, "【距离触发】距离从${prevDistance}米增加到${currentDistance}米，跨过${crossedDistance}米阈值，触发播报")

            scope.launch {
                try {
                    isAnnouncing = true
                    generateAndPublishTip()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "生成提示失败", e)
                } finally {
                    isAnnouncing = false
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  核心逻辑
    // ═══════════════════════════════════════════════════════════

    private suspend fun generateAndPublishTip() {
        val plan = planId?.let { coachRepository.getCurrentPlan(it) }
        val profile = coachRepository.getUserProfile()
        val session = coachRepository.getLatestRunningSession()

        val prompt = buildCoachPrompt(plan, profile, session)
        Log.d(TAG, "【Prompt】\n$prompt")

        val tip = callAiForTip(prompt)
        if (tip.isNotBlank()) {
            Log.i(TAG, "【Coach Tip】$tip")

            // 1. 发送到Flow供UI层显示到对话框
            _coachTip.value = tip

            // 2. 语音播报（在主线程调用TTS）
            withContext(Dispatchers.Main) {
//                voiceQueue.speak(tip)
            }
        }
    }

    /**
     * 构建教练Prompt
     *
     * 明确要求AI输出：已跑距离、剩余距离、当前速度、速度是否适宜
     */
    private fun buildCoachPrompt(
        plan: RunningPlanEntity?,
        profile: UserProfileEntity?,
        session: RunningSessionEntity?
    ): String {
        val elapsedSec = (System.currentTimeMillis() - runStartTime) / 1000
        val elapsedMin = elapsedSec / 60

        // 已跑距离（公里）
        val distKm = (currentDistance / 1000.0)

        // 剩余距离
        val remainingKm = if (plan != null && plan.goalDistance > 0) {
            val rem = (plan.goalDistance - currentDistance) / 1000.0
            if (rem > 0) String.format("%.1f", rem) else "0"
        } else null

        // 目标距离
        val goalKm = if (plan != null && plan.goalDistance > 0) {
            String.format("%.1f", plan.goalDistance / 1000.0)
        } else null

        return buildString {
            appendLine("【必须回答的问题】")
            appendLine("请告诉我当前跑步状态，必须包含以下4点：")
            appendLine("1. 当前已跑了多少公里（现在跑了 ${String.format("%.1f", distKm)} 公里）")
            if (remainingKm != null) {
                appendLine("2. 距离目标（${goalKm}公里）还剩多少公里（还剩 ${remainingKm} 公里）")
            } else {
                appendLine("2. 当前是自由跑模式，没有设定目标距离")
            }
            appendLine("3. 当前速度是多少（当前配速 ${currentPace}）")
            appendLine("4. 速度是否适宜（目标配速是 ${plan?.targetPace ?: "未设定"}）")
            appendLine()

            appendLine("【跑步计划】")
            if (plan != null) {
                appendLine("计划名称: ${plan.title}")
                appendLine("目标距离: ${goalKm}公里")
                appendLine("目标配速: ${plan.targetPace}")
                appendLine("已跑距离: ${String.format("%.1f", distKm)}公里")
                appendLine("剩余距离: ${remainingKm}公里")
            } else {
                appendLine("模式: 自由跑")
                appendLine("已跑距离: ${String.format("%.1f", distKm)}公里")
            }

            appendLine()
            appendLine("【用户资料】")
            if (profile != null) {
                appendLine("年龄: ${profile.age}岁")
                appendLine("目标步频: ${profile.targetCadence}步/分")
                if (profile.isVirtualPartnerEnabled) {
                    appendLine("虚拟伙伴配速: ${profile.virtualPartnerPace}")
                }
            } else {
                appendLine("使用默认设置")
            }

            appendLine()
            appendLine("【实时状态】")
            appendLine("跑步时长: ${elapsedMin}分钟")
            appendLine("已跑距离: ${String.format("%.2f", distKm * 1000)}米 / ${String.format("%.1f", distKm)}公里")
            appendLine("当前配速: ${currentPace}")
            if (currentHeartRate > 0) appendLine("当前心率: ${currentHeartRate}次/分")
            if (currentCadence > 0) appendLine("当前步频: ${currentCadence}步/分")

            if (session != null) {
                appendLine()
                appendLine("【历史参考】")
                appendLine("本次平均配速: ${session.avgPace}")
                if (session.avgHeartRate > 0) appendLine("本次平均心率: ${session.avgHeartRate}次/分")
            }

            appendLine()
            appendLine("请直接输出播报文字，30字以内，包含已跑距离、剩余距离/目标达成情况、当前配速、速度是否适宜这4项信息。")
        }
    }

    private suspend fun callAiForTip(prompt: String): String {
        return try {
            val request = ChatCompletionRequest(
                model = MODEL_COACH,
                messages = listOf(
                    ChatMessageDto(role = "system", content = COACH_SYSTEM_PROMPT),
                    ChatMessageDto(role = "user", content = prompt)
                )
            )
            val response = dashScopeService.chatCompletions("Bearer $apiKey", request)
            response.choices?.firstOrNull()?.message?.content?.trim() ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "AI调用失败: ${e.message}")
            // AI失败时返回本地生成的兜底播报，确保用户始终能听到提示
            buildFallbackTip()
        }
    }

    /**
     * AI调用失败时的兜底播报
     *
     * 不依赖AI，本地直接生成，确保用户始终能听到距离播报
     */
    private fun buildFallbackTip(): String {
        val distKm = currentDistance / 1000.0
        val distStr = String.format("%.1f", distKm)

        return if (planId != null) {
            "已跑${distStr}公里，距离触发播报，继续加油。"
        } else {
            "已跑${distStr}公里，状态不错，继续保持。"
        }
    }

    private fun Double.format(digits: Int): String =
        String.format("%.${digits}f", this)

    /**
     * 释放资源
     */
    fun destroy() {
        stop()
        scope.cancel()
    }
}
