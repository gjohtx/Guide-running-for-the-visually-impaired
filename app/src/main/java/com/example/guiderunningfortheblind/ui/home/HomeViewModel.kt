package com.example.guiderunningfortheblind.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.guiderunningfortheblind.data.local.entity.RunningPlanEntity
import com.example.guiderunningfortheblind.data.repository.RunningRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel - 管理跑步计划和语音命令路由
 *
 * 【修复摘要】
 * 1. 新增语音命令路由：[handleVoiceCommand] 将识别到的文字映射为具体命令
 * 2. 新增 VoiceCommand 密封类，定义首页支持的所有语音操作
 * 3. 新增 recognizedCommand Flow，用于 UI 层观察并执行导航
 * 4. 新增 feedbackMessage Flow，用于语音播报操作反馈
 */
class HomeViewModel(private val repository: RunningRepository) : ViewModel() {

    val plans: StateFlow<List<RunningPlanEntity>> = repository.allPlans
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ═══════════════════════════════════════════════════════════
    //  【新增】语音命令处理
    // ═══════════════════════════════════════════════════════════

    /** 最近一次识别到的语音命令 */
    private val _recognizedCommand = MutableStateFlow<VoiceCommand?>(null)
    val recognizedCommand: StateFlow<VoiceCommand?> = _recognizedCommand.asStateFlow()

    /** 语音反馈消息（用于 TTS 播报） */
    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshPlans()
        }
    }

    fun deletePlan(plan: RunningPlanEntity) {
        viewModelScope.launch {
            repository.deletePlan(plan)
        }
    }

    /**
     * 处理语音识别到的文字命令
     *
     * 首页只支持三个命令：
     * - "开始跑步" → START_RUNNING
     * - "历史记录" → GO_HISTORY
     * - "设置" → GO_SETTINGS
     *
     * 其他所有话都识别为 UNKNOWN，只播报语音提示，不执行任何操作。
     *
     * @param command 识别到的文字
     * @return 对应的 VoiceCommand
     */
    fun handleVoiceCommand(command: String): VoiceCommand {
        val result = when {
            command.contains("开始跑步") || command.contains("跑步") ->
                VoiceCommand.StartRunning

            command.contains("历史记录") || command.contains("历史") ->
                VoiceCommand.GoHistory

            command.contains("设置") ->
                VoiceCommand.GoSettings

            else -> VoiceCommand.Unknown(command)
        }

        _recognizedCommand.value = result

        // 生成反馈消息
        _feedbackMessage.value = when (result) {
            is VoiceCommand.StartRunning -> "开始跑步"
            is VoiceCommand.GoHistory -> "历史记录"
            is VoiceCommand.GoSettings -> "设置"
            is VoiceCommand.Unknown -> "请说：开始跑步、历史记录或设置"
        }

        return result
    }

    /**
     * 消费已处理的命令（避免重复执行）
     */
    fun consumeCommand() {
        _recognizedCommand.value = null
        _feedbackMessage.value = null
    }

    // ═══════════════════════════════════════════════════════════
    //  【新增】语音命令定义
    // ═══════════════════════════════════════════════════════════

    /**
     * 首页支持的语音命令
     */
    sealed class VoiceCommand {
        /** 开始跑步 */
        data object StartRunning : VoiceCommand()
        /** 前往历史记录 */
        data object GoHistory : VoiceCommand()
        /** 前往设置 */
        data object GoSettings : VoiceCommand()
        /** 无法识别的命令，包含原始文字 */
        data class Unknown(val originalText: String) : VoiceCommand()
    }

    // ═══════════════════════════════════════════════════════════

    class Factory(private val repository: RunningRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository) as T
        }
    }
}
