package com.example.guiderunningfortheblind.ui.running

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.example.guiderunningfortheblind.MainApplication
import com.example.guiderunningfortheblind.R
import com.example.guiderunningfortheblind.camera.CameraPreview
import com.example.guiderunningfortheblind.camera.CameraViewModel
import com.example.guiderunningfortheblind.model.ChatMessage
import com.example.guiderunningfortheblind.speech.RecognitionResult
import com.example.guiderunningfortheblind.speech.SpeechViewModel
import com.example.guiderunningfortheblind.ui.theme.GuideRunningFortheBlindTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

/**
 * RunningFragment - 跑步主页面（AI跑步教练集成版）
 *
 * 【2026-05-28 集成】
 * 1. 在 uiState 变化时同步实时数据给 AiRunningCoach
 * 2. 根据 isRunning 状态自动启动/停止AI教练
 * 3. AiChatViewModel 内部收集 coachTip Flow 并显示到对话框 + 语音播报
 * 4. 保留原有所有功能（场景分析、语音输入、对话等）
 */
@AndroidEntryPoint
class RunningFragment : Fragment() {

    companion object {
        private const val TAG = "RunningFragment"
    }

    /**
     * 【ColorOS 兼容】RecognizerIntent fallback 启动器
     */
    private val recognizerFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val app = requireActivity().application as MainApplication
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            Log.i(TAG, "RecognizerIntent 返回结果: $matches")
            app.voiceCommandManager.onRecognizerIntentResult(matches)
        } else {
            Log.w(TAG, "RecognizerIntent 被取消或失败 (resultCode=${result.resultCode})")
            app.voiceCommandManager.onRecognizerIntentCancelled()
        }
    }

    private val sessionViewModel: RunningSessionViewModel by activityViewModels {
        val app = requireActivity().application as MainApplication
        RunningSessionViewModel.Factory(
            app.runningSessionRepository,
            app.locationManager,
            app.healthConnectManager,
            app.userProfileRepository
        )
    }

    private val cameraViewModel: CameraViewModel by activityViewModels()
    private val speechViewModel: SpeechViewModel by activityViewModels()
    private val aiChatViewModel: AiChatViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )

            setContent {
                val uiState by sessionViewModel.uiState.collectAsStateWithLifecycle()
                val messages by aiChatViewModel.messages.collectAsStateWithLifecycle()

                // ═══════════════════════════════════════════════════
                //  【新增】同步跑步状态到 AI 教练
                // ═══════════════════════════════════════════════════

                // 追踪上一次 isRunning 状态，用于检测开始/结束
                var wasRunning by remember { mutableStateOf(false) }

                LaunchedEffect(uiState) {
                    // 1. 同步实时数据给AI教练（每次 uiState 更新都调用）
                    aiChatViewModel.setRunningState(uiState)
                    aiChatViewModel.updateCoachData(
                        distance = uiState.currentDistance,
                        pace = uiState.currentPace,
                        heartRate = uiState.currentHeartRate ?: 0
                    )

                    // 2. 检测跑步开始/结束，控制AI教练生命周期
                    if (uiState.isRunning && !wasRunning) {
                        // 跑步开始
                        Log.i(TAG, "【跑步状态】跑步开始，启动AI教练")
                        aiChatViewModel.startCoach(planId = null)
                    } else if (!uiState.isRunning && wasRunning) {
                        // 跑步结束
                        Log.i(TAG, "【跑步状态】跑步结束，停止AI教练")
                        aiChatViewModel.stopCoach()
                    }
                    wasRunning = uiState.isRunning
                }

                // ═══════════════════════════════════════════════════
                //  原有功能（保持不变）
                // ═══════════════════════════════════════════════════

                // 核心接线：把摄像头帧流接入 AiChatViewModel
                LaunchedEffect(Unit) {
                    aiChatViewModel.collectSceneFrames(cameraViewModel.sceneFrameFlow)
                }

                // 障碍物计数
                LaunchedEffect(Unit) {
                    cameraViewModel.obstacleDetectionCount.collect {
                        sessionViewModel.onObstacleDetected()
                    }
                }

                // 跑步状态语音事件
                LaunchedEffect(Unit) {
                    sessionViewModel.voiceEvents.collect { message ->
                        speechViewModel.queueInstruction(message)
                    }
                }

                // 初始化语音协调器
                val application = requireActivity().application as MainApplication
                LaunchedEffect(Unit) {
                    speechViewModel.setVoiceCoordinator(application.voiceCoordinator)
                }

                // 【ColorOS 兼容】监听 RecognizerIntent fallback 请求
                LaunchedEffect(Unit) {
                    application.voiceCommandManager.requestFallbackRecognition.collect {
                        Log.i(TAG, "收到 RecognizerIntent fallback 请求，启动系统语音输入")
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话...")
                        }
                        recognizerFallbackLauncher.launch(intent)
                    }
                }

                // 监听识别结果，填入输入框并发送给AI
                val lastResult by speechViewModel.lastRecognitionResult.collectAsStateWithLifecycle()
                LaunchedEffect(lastResult) {
                    if (lastResult is RecognitionResult.Success) {
                        val text = (lastResult as RecognitionResult.Success).text
                        Log.i(TAG, "【跑步页】识别结果填入输入框并发送: \"$text\"")
                        speechViewModel.updateInputText(text)
                        aiChatViewModel.sendUserMessage(text)
                        speechViewModel.clearRecognitionResult()
                    }
                }

                // 语音指令路由
                LaunchedEffect(Unit) {
                    val voiceManager = application.voiceCommandManager
                    voiceManager.commands.collect { command ->
                        when {
                            command.equals("STOP", ignoreCase = true) -> {
                                sessionViewModel.endSession()
                                findNavController().navigate(R.id.action_running_to_postRun)
                            }
                        }
                    }
                }

                GuideRunningFortheBlindTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 摄像头预览（全屏背景）
                        CameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            analyzer = cameraViewModel.analyzer
                        )

                        // AI 路况 + 对话气泡（含AI教练提示）
                        ChatPanel(
                            messages = messages,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .padding(horizontal = 8.dp)
                        )

                        // 底部：跑步数据 + 输入栏 + 语音设置
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        ) {
                            RunningScreen(
                                uiState = uiState,
                                onEndRun = {
                                    sessionViewModel.endSession()
                                    findNavController().navigate(R.id.action_running_to_postRun)
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // 语音设置面板（可折叠）
                            SpeechSettingsPanel(speechViewModel = speechViewModel)

                            Spacer(modifier = Modifier.height(4.dp))

                            // 按住说话输入栏
                            VoiceChatInputBar(
                                speechViewModel = speechViewModel,
                                onSendText = { text -> aiChatViewModel.sendUserMessage(text) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  按住说话输入栏
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun VoiceChatInputBar(
    speechViewModel: SpeechViewModel,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val inputText by speechViewModel.inputText.collectAsStateWithLifecycle()
    val isListening by speechViewModel.isListening.collectAsStateWithLifecycle()
    val lastResult by speechViewModel.lastRecognitionResult.collectAsStateWithLifecycle()

    val hasRecordPermission = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    var showResultHint by remember { mutableStateOf(false) }
    var resultHintText by remember { mutableStateOf("") }
    var isResultError by remember { mutableStateOf(false) }

    LaunchedEffect(lastResult) {
        when (lastResult) {
            is RecognitionResult.Success -> {
                val text = (lastResult as RecognitionResult.Success).text
                resultHintText = "已识别: $text"
                isResultError = false
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            is RecognitionResult.NoMatch -> {
                resultHintText = "未识别到文字"
                isResultError = true
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            is RecognitionResult.Error -> {
                val error = (lastResult as RecognitionResult.Error).error
                resultHintText = when (error) {
                    com.example.guiderunningfortheblind.speech.RecognizerError.PERMISSION_DENIED -> "请授予录音权限"
                    com.example.guiderunningfortheblind.speech.RecognizerError.NETWORK_ERROR -> "网络错误，请检查网络"
                    else -> "识别失败，请重试"
                }
                isResultError = true
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            null -> { /* 无操作 */ }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = showResultHint,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Surface(
                color = if (isResultError) Color(0xFFE53935).copy(alpha = 0.9f)
                else Color(0xFF43A047).copy(alpha = 0.9f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = resultHintText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                AnimatedVisibility(
                    visible = isListening,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ListeningIndicatorBar()
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { speechViewModel.updateInputText(it) },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = if (isListening) "正在聆听，请说话" else "文字输入"
                            },
                        placeholder = {
                            Text(
                                text = if (isListening) "正在聆听..." else "按住麦克风说话或输入文字...",
                                color = Color.White.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val text = inputText.trim()
                                if (text.isNotBlank()) {
                                    onSendText(text)
                                    speechViewModel.clearInput()
                                }
                            }
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.White.copy(alpha = 0.5f),
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.5f),
                            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f)
                        ),
                        enabled = !isListening
                    )

                    PushToTalkButton(
                        isListening = isListening,
                        hasPermission = hasRecordPermission,
                        onPressStart = {
                            if (!hasRecordPermission) return@PushToTalkButton
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            speechViewModel.startVoiceInput()
                        },
                        onPressEnd = {
                            if (!hasRecordPermission) return@PushToTalkButton
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            speechViewModel.stopVoiceInput()
                        }
                    )

                    AnimatedVisibility(
                        visible = inputText.isNotBlank() && !isListening,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        IconButton(
                            onClick = {
                                val text = inputText.trim()
                                if (text.isNotBlank()) {
                                    onSendText(text)
                                    speechViewModel.clearInput()
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .semantics {
                                    contentDescription = "发送消息"
                                    role = Role.Button
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = null,
                                tint = Color(0xFF4FC3F7),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  按住说话按钮
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun PushToTalkButton(
    isListening: Boolean,
    hasPermission: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1.12f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "btnScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    val bgColor = when {
        !hasPermission -> Color.Gray.copy(alpha = 0.4f)
        isListening -> Color(0xFFEF5350)
        else -> Color(0xFF4FC3F7)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(52.dp)
    ) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .scale(pulseScale)
                    .background(
                        color = Color(0xFFEF5350).copy(alpha = pulseAlpha),
                        shape = CircleShape
                    )
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .scale(scale)
                .background(
                    color = bgColor,
                    shape = CircleShape
                )
                .then(
                    if (hasPermission) {
                        Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                Log.d("PushToTalk", "手指按下，开始语音识别")
                                onPressStart()

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val allUp = event.changes.all { !it.pressed }
                                    if (allUp) {
                                        event.changes.forEach { it.consume() }
                                        Log.d("PushToTalk", "手指抬起，停止语音识别")
                                        onPressEnd()
                                        break
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    } else Modifier
                )
                .semantics {
                    contentDescription = if (isListening) {
                        "正在聆听，松开按钮结束"
                    } else {
                        "按住说话"
                    }
                    role = Role.Button
                }
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.MicNone,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  监听中指示器
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ListeningIndicatorBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        repeat(3) { index ->
            val delay = index * 150
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = delay, easing = EaseInOutQuad),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .scale(scale)
                    .background(
                        color = Color(0xFF4FC3F7),
                        shape = CircleShape
                    )
            )
            if (index < 2) {
                Spacer(modifier = Modifier.width(5.dp))
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = "正在聆听...",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF4FC3F7)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  语音设置面板
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun SpeechSettingsPanel(speechViewModel: SpeechViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val speechRate by speechViewModel.speechRate.collectAsStateWithLifecycle()
    val speechVolume by speechViewModel.speechVolume.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "语音设置",
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "语速  ${"%.1f".format(speechRate)}x",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(90.dp)
                        )
                        Slider(
                            value = speechRate,
                            onValueChange = { speechViewModel.setSpeechRate(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 5,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF4FC3F7),
                                activeTrackColor = Color(0xFF4FC3F7)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "音量  ${(speechVolume * 100).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(90.dp)
                        )
                        Slider(
                            value = speechVolume,
                            onValueChange = { speechViewModel.setSpeechVolume(it) },
                            valueRange = 0f..1f,
                            steps = 9,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF81C784),
                                activeTrackColor = Color(0xFF81C784)
                            )
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  对话气泡列表
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LazyColumn(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(8.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(messages) { msg ->
            val isUser = msg.role == ChatMessage.Role.USER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Surface(
                    color = if (isUser) Color(0xFF2A5C8A) else Color(0xFF2E2E2E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.widthIn(max = 270.dp)
                ) {
                    Text(
                        text = msg.content,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  跑步数据面板
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun RunningScreen(
    uiState: RunningUiState,
    onEndRun: () -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem(label = "距离", value = "${uiState.currentDistance.toInt()}m")
                MetricItem(label = "配速", value = uiState.currentPace)
                MetricItem(label = "心率", value = uiState.currentHeartRate?.toString() ?: "--")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "障碍物: ${uiState.obstacleCount}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = onEndRun,
            modifier = Modifier.fillMaxWidth()
        ) { Text("结束跑步") }
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
        Text(text = value, color = Color.White, style = MaterialTheme.typography.headlineSmall)
    }
}
