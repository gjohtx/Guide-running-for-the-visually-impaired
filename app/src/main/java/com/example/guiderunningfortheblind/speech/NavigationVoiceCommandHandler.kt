package com.example.guiderunningfortheblind.speech

import android.util.Log
import com.example.guiderunningfortheblind.navigation.NavigationManager
import kotlinx.coroutines.CoroutineScope

/**
 * 简化后的跑步语音指令处理器
 * 仅保留跑步控制指令，移除导航目的地解析逻辑
 */
class NavigationVoiceCommandHandler(
    private val navigationManager: NavigationManager,
    private val voiceQueueManager: VoiceQueueManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "NavVoiceCmdHandler"

        // 跑步相关关键词
        private val START_KEYWORDS = listOf("开始跑步", "出发", "开始", "走吧")
        private val STOP_KEYWORDS = listOf("停止跑步", "结束跑步", "停止")
        private val PAUSE_KEYWORDS = listOf("暂停", "暂停跑步")
        private val RESUME_KEYWORDS = listOf("继续", "恢复", "继续跑步")
        private val STATUS_KEYWORDS = listOf("状态", "进度", "跑步数据", "多远了")
    }

    /**
     * 处理语音输入文本
     */
    fun handleVoiceCommand(command: String): Boolean {
        val normalizedCmd = command.trim().lowercase()
        Log.d(TAG, "处理语音指令: $normalizedCmd")

        return when {
            matchesAny(normalizedCmd, START_KEYWORDS) -> {
                navigationManager.startAiPlannedRun()
                true
            }
            matchesAny(normalizedCmd, STOP_KEYWORDS) -> {
                navigationManager.stopRun()
                true
            }
            matchesAny(normalizedCmd, PAUSE_KEYWORDS) -> {
                navigationManager.pauseRun()
                true
            }
            matchesAny(normalizedCmd, RESUME_KEYWORDS) -> {
                navigationManager.resumeRun()
                true
            }
            matchesAny(normalizedCmd, STATUS_KEYWORDS) -> {
                val metrics = navigationManager.metrics.value
                val distKm = "%.1f".format(metrics.distanceMeters / 1000.0)
                voiceQueueManager.speak("您已跑步 $distKm 公里，当前配速 ${metrics.currentPace}")
                true
            }
            else -> false
        }
    }

    private fun matchesAny(command: String, keywords: List<String>): Boolean {
        return keywords.any { command.contains(it) }
    }
}
