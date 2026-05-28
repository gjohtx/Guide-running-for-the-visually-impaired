package com.example.guiderunningfortheblind.speech

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * 语音输入框组件 - 支持按住说话（Push-to-Talk）
 *
 * 【功能】
 * 1. 文本输入框：显示和编辑文字
 * 2. 语音按钮：按住开始识别，松手停止识别
 * 3. 识别状态反馈：监听中动画、识别结果提示
 * 4. 未识别到文字时显示提示
 * 5. 视障友好：所有交互元素带语义标签，可被 TalkBack 朗读
 *
 * 【使用方式】
 * ```kotlin
 * val speechViewModel: SpeechViewModel = hiltViewModel()
 * val voiceCoordinator = (application as MainApplication).voiceCoordinator
 *
 * // 在 Composable 初始化时设置 VoiceCoordinator
 * LaunchedEffect(Unit) {
 *     speechViewModel.setVoiceCoordinator(voiceCoordinator)
 * }
 *
 * // 在 UI 中使用
 * VoiceInputField(
 *     viewModel = speechViewModel,
 *     onSend = { text ->
 *         // 处理发送文字
 *     },
 *     modifier = Modifier.fillMaxWidth()
 * )
 * ```
 */
@Composable
fun VoiceInputField(
    viewModel: SpeechViewModel,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "按住麦克风说话...",
    label: String = "语音输入"
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // 观察 ViewModel 状态
    val inputText by viewModel.inputText.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val lastResult by viewModel.lastRecognitionResult.collectAsState()
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 识别结果提示状态
    var showResultHint by remember { mutableStateOf(false) }
    var resultHintText by remember { mutableStateOf("") }

    // 监听识别结果，显示提示
    LaunchedEffect(lastResult) {
        when (lastResult) {
            is RecognitionResult.Success -> {
                val text = (lastResult as RecognitionResult.Success).text
                resultHintText = "已识别: $text"
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            is RecognitionResult.NoMatch -> {
                resultHintText = "未识别到文字"
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            is RecognitionResult.Error -> {
                resultHintText = "识别失败，请重试"
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            null -> {
                showResultHint = false
            }
        }
    }

    Column(modifier = modifier) {
        // 识别结果提示条（动画显示/隐藏）
        AnimatedVisibility(
            visible = showResultHint,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it }
        ) {
            ResultHintBar(
                text = resultHintText,
                isError = lastResult is RecognitionResult.NoMatch ||
                        lastResult is RecognitionResult.Error
            )
        }

        // 输入框主体
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // 监听中动画条
                AnimatedVisibility(
                    visible = isListening,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ListeningIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 文字输入框
                    TextField(
                        value = inputText,
                        onValueChange = { viewModel.updateInputText(it) },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = label
                            },
                        placeholder = {
                            Text(
                                text = if (isListening) "正在聆听..." else placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    onSend(inputText)
                                }
                            }
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        enabled = !isListening // 监听中禁用键盘输入
                    )

                    // 语音按钮（按住说话）
                    VoiceButton(
                        isListening = isListening,
                        hasPermission = hasPermission,
                        onPressStart = {
                            if (!hasPermission) return@VoiceButton
                            Log.d("VoiceInputField", "按下语音按钮")
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.startVoiceInput()
                        },
                        onPressEnd = {
                            if (!hasPermission) return@VoiceButton
                            Log.d("VoiceInputField", "松开语音按钮")
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.stopVoiceInput()
                        }
                    )

                    // 发送按钮
                    AnimatedVisibility(visible = inputText.isNotBlank() && !isListening) {
                        IconButton(
                            onClick = { onSend(inputText) },
                            modifier = Modifier.semantics {
                                contentDescription = "发送"
                                role = Role.Button
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // 底部提示文字
        AnimatedVisibility(
            visible = !hasPermission,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = "请授予录音权限以使用语音输入功能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, start = 16.dp)
            )
        }
    }
}

/**
 * 语音按钮（按住说话）
 *
 * 核心交互：
 * - 按下：开始语音识别
 * - 松开：停止语音识别
 * - 使用 pointerInput + detectTapGestures 精确感知按下/松开
 *
 * 视障友好：
 * - 语义标签描述当前状态
 * - 监听中震动反馈
 */
@Composable
private fun VoiceButton(
    isListening: Boolean,
    hasPermission: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    // 脉冲动画（监听中时）
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "voiceButtonScale"
    )

    // 监听中的环形动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp)
    ) {
        // 脉冲环（仅监听中显示）
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(pulseScale)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                        shape = CircleShape
                    )
            )
        }

        // 按钮主体
        FilledIconButton(
            onClick = { /* 空：所有逻辑通过 pointerInput 处理 */ },
            modifier = Modifier
                .size(48.dp)
                .scale(scale)
                .then(
                    if (hasPermission) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    // 按下开始
                                    onPressStart()
                                    // 等待松开
                                    val released = tryAwaitRelease()
                                    // 松开停止
                                    onPressEnd()
                                    Log.d("VoiceButton", "按压结束, released=$released")
                                }
                            )
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
                },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = when {
                    !hasPermission -> MaterialTheme.colorScheme.surfaceVariant
                    isListening -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                contentColor = when {
                    !hasPermission -> MaterialTheme.colorScheme.onSurfaceVariant
                    isListening -> MaterialTheme.colorScheme.onError
                    else -> MaterialTheme.colorScheme.onPrimary
                }
            ),
            enabled = hasPermission
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 监听中指示器 - 波形动画
 */
@Composable
private fun ListeningIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 三个跳动的点
        val infiniteTransition = rememberInfiniteTransition(label = "dots")
        repeat(3) { index ->
            val delay = index * 150
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = delay, easing = EaseInOutQuad),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )
            if (index < 2) {
                Spacer(modifier = Modifier.width(6.dp))
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = "正在聆听...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 识别结果提示条
 */
@Composable
private fun ResultHintBar(
    text: String,
    isError: Boolean
) {
    val backgroundColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics {
                contentDescription = text
            }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
    }
}

/**
 * 语音输入框预览/独立使用版本
 *
 * 如果不需要 ViewModel，可以直接使用此函数，手动管理状态。
 */
@Composable
fun VoiceInputFieldStandalone(
    value: String,
    onValueChange: (String) -> Unit,
    isListening: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "按住麦克风说话...",
    hasPermission: Boolean = true,
    lastResult: RecognitionResult? = null
) {
    val haptic = LocalHapticFeedback.current

    // 识别结果提示状态
    var showResultHint by remember { mutableStateOf(false) }
    var resultHintText by remember { mutableStateOf("") }

    LaunchedEffect(lastResult) {
        when (lastResult) {
            is RecognitionResult.Success -> {
                resultHintText = "已识别: ${lastResult.text}"
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            is RecognitionResult.NoMatch -> {
                resultHintText = "未识别到文字"
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            is RecognitionResult.Error -> {
                resultHintText = "识别失败，请重试"
                showResultHint = true
                delay(2000)
                showResultHint = false
            }
            null -> {
                showResultHint = false
            }
        }
    }

    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = showResultHint,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it }
        ) {
            ResultHintBar(
                text = resultHintText,
                isError = lastResult is RecognitionResult.NoMatch ||
                        lastResult is RecognitionResult.Error
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                AnimatedVisibility(
                    visible = isListening,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ListeningIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "语音输入框"
                            },
                        placeholder = {
                            Text(
                                text = if (isListening) "正在聆听..." else placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { onSend() }
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        enabled = !isListening
                    )

                    VoiceButton(
                        isListening = isListening,
                        hasPermission = hasPermission,
                        onPressStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStartListening()
                        },
                        onPressEnd = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onStopListening()
                        }
                    )

                    AnimatedVisibility(visible = value.isNotBlank() && !isListening) {
                        IconButton(
                            onClick = onSend,
                            modifier = Modifier.semantics {
                                contentDescription = "发送"
                                role = Role.Button
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !hasPermission,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = "请授予录音权限以使用语音输入功能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, start = 16.dp)
            )
        }
    }
}
