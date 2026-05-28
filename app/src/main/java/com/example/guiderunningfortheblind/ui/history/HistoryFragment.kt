package com.example.guiderunningfortheblind.ui.history

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.example.guiderunningfortheblind.MainApplication
import com.example.guiderunningfortheblind.R
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.speech.SpeechViewModel
import com.example.guiderunningfortheblind.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import androidx.fragment.app.activityViewModels
import java.util.*

/**
 * 历史记录界面 - 无障碍重构版
 *
 * 核心设计：
 * 1. 月份筛选器（顶部大号下拉按钮）
 * 2. 记录卡片：左侧日期+距离大字，右侧配速色条
 * 3. 展开：语音卡片 + "播放摘要"按钮
 * 4. 空状态提示
 * 5. 最小字号20sp，卡片高度96dp
 */
@AndroidEntryPoint
class HistoryFragment : Fragment() {

    private val viewModel: HistoryViewModel by viewModels {
        HistoryViewModel.Factory((requireActivity().application as MainApplication).runningSessionRepository)
    }

    private val speechViewModel: SpeechViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            val sessions by viewModel.sessions.collectAsStateWithLifecycle()

            GuideRunningFortheBlindTheme {
                HistoryScreenAccessible(
                    sessions = sessions,
                    onSessionClick = { sessionId ->
                        val bundle = Bundle().apply { putLong("sessionId", sessionId) }
                        findNavController().navigate(R.id.action_history_to_historyDetail, bundle)
                    },
                    onPlaySummary = { session ->
                        playSessionSummary(session)
                    },
                    onDeleteSession = { sessionId ->
                        viewModel.deleteSession(sessionId)
                    },
                    speechViewModel = speechViewModel
                )
            }
        }
    }

    private fun playSessionSummary(session: RunningSessionEntity) {
        val distanceKm = "%.1f".format(session.totalDistance / 1000)
        val dateStr = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(session.startTime))
        val durationMin = ((session.endTime ?: System.currentTimeMillis()) - session.startTime) / 60000

        val summary = "${dateStr}跑步记录：全程${distanceKm}公里，用时${durationMin}分钟，" +
                "平均配速${session.avgPace}，共避开${session.obstacleCount}个障碍物。"
        speechViewModel.queueInstruction(summary)
    }
}

// ═══════════════════════════════════════════════════════════
//  历史记录主屏幕（无障碍版）
// ═══════════════════════════════════════════════════════════

@Composable
fun HistoryScreenAccessible(
    sessions: List<RunningSessionEntity>,
    onSessionClick: (Long) -> Unit,
    onPlaySummary: (RunningSessionEntity) -> Unit,
    onDeleteSession: (Long) -> Unit,
    speechViewModel: SpeechViewModel
) {
    val vibrator = LocalContext.current.let {
        ContextCompat.getSystemService(it, Vibrator::class.java)
    }

    var selectedMonth by remember { mutableStateOf<String?>(null) }
    var showMonthFilter by remember { mutableStateOf(false) }

    // 按月份分组
    val monthGroups = remember(sessions) {
        val sdf = SimpleDateFormat("yyyy年M月", Locale.CHINA)
        sessions.groupBy { sdf.format(Date(it.startTime)) }
            .toList()
            .sortedByDescending { (month, _) ->
                try {
                    sdf.parse(month)?.time ?: 0L
                } catch (_: Exception) { 0L }
            }
    }

    val months = remember(monthGroups) { monthGroups.map { it.first } }

    // 筛选后的记录
    val filteredSessions = remember(sessions, selectedMonth, monthGroups) {
        if (selectedMonth != null) {
            monthGroups.find { it.first == selectedMonth }?.second
                ?.sortedByDescending { it.startTime } ?: emptyList()
        } else {
            sessions.sortedByDescending { it.startTime }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // ── 标题 + 月份筛选 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "跑步历史",
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            AccessibleFilterButton(
                label = selectedMonth ?: "全部月份",
                onClick = { showMonthFilter = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 记录列表 ──
        if (filteredSessions.isEmpty()) {
            EmptyHistoryState(
                modifier = Modifier.fillMaxSize(),
                speechViewModel = speechViewModel
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredSessions, key = { it.sessionId }) { session ->
                    HistoryCard(
                        session = session,
                        onClick = { onSessionClick(session.sessionId) },
                        onPlaySummary = { onPlaySummary(session) },
                        onDelete = {
                            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                            onDeleteSession(session.sessionId)
                        },
                        speechViewModel = speechViewModel
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    // 月份筛选Dialog
    if (showMonthFilter) {
        MonthFilterDialog(
            months = months,
            selectedMonth = selectedMonth,
            onSelect = { selectedMonth = it },
            onDismiss = { showMonthFilter = false }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  历史记录卡片（96dp高 + 配速色条）
// ═══════════════════════════════════════════════════════════

@Composable
fun HistoryCard(
    session: RunningSessionEntity,
    onClick: () -> Unit,           // ← 跳转到详情页
    onPlaySummary: () -> Unit,
    onDelete: () -> Unit,
    speechViewModel: SpeechViewModel
) {
    var isExpanded by remember { mutableStateOf(false) }
    val vibrator = LocalContext.current.let {
        ContextCompat.getSystemService(it, Vibrator::class.java)
    }

    val dateStr = remember(session.startTime) {
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(session.startTime))
    }
    val weekdayStr = remember(session.startTime) {
        SimpleDateFormat("EEE", Locale.CHINA).format(Date(session.startTime))
    }
    val distanceKm = remember(session.totalDistance) {
        "%.1f".format(session.totalDistance / 1000)
    }
    val paceBarColor = remember(session.avgPace) {
        calculatePaceColor(session.avgPace)
    }

    // ── 卡片主体：点击跳转详情页 ──
    Card(
        onClick = {
            // 【修改1】单击卡片 = 跳转到详情页（地图+轨迹）
            vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$dateStr$weekdayStr，跑步${distanceKm}公里，平均配速${session.avgPace}，点击查看详情"
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column {
            // ── 主卡片内容 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：日期 + 距离
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = dateStr,
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = weekdayStr,
                            color = TextTertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        text = "${distanceKm} 公里",
                        color = TextPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 中间：配速
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = session.avgPace,
                        color = paceBarColor,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "平均配速",
                        color = TextTertiary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // 【修改2】展开/折叠按钮（独立操作，不触发跳转）
                IconButton(
                    onClick = {
                        isExpanded = !isExpanded
                        vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded)
                            Icons.Default.KeyboardArrowUp
                        else
                            Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "折叠详情" else "展开详情",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 配速色条（最右侧）
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(60.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    paceBarColor.copy(alpha = 0.8f),
                                    paceBarColor
                                )
                            )
                        )
                )
            }

            // ── 展开区域 ──
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                // 【修改3】使用新的展开详情卡片
                ExpandedDetailCard(
                    session = session,
                    onClick = onClick,           // ← 传递跳转回调
                    onPlaySummary = onPlaySummary,
                    onDelete = onDelete,
                    speechViewModel = speechViewModel
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  展开详情卡片（语音卡片）
// ═══════════════════════════════════════════════════════════

@Composable
private fun ExpandedDetailCard(
    session: RunningSessionEntity,
    onClick: () -> Unit,           // ← 新增：点击查看详情回调
    onPlaySummary: () -> Unit,
    onDelete: () -> Unit,
    speechViewModel: SpeechViewModel
) {
    val durationMin = remember(session.startTime, session.endTime) {
        val end = session.endTime ?: System.currentTimeMillis()
        ((end - session.startTime) / 60000).toInt()
    }
    val vibrator = LocalContext.current.let {
        ContextCompat.getSystemService(it, Vibrator::class.java)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        HorizontalDivider(color = SurfaceElevated, thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        // 统计数据
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DetailItem("用时", "${durationMin}分钟")
            DetailItem("障碍物", "${session.obstacleCount}个")
            DetailItem("步频", "${session.avgCadence}步/分")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 操作按钮 ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 【修改3】查看地图轨迹按钮（主要操作）
            Button(
                onClick = {
                    vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                    onClick()  // ← 跳转到详情页（带地图轨迹）
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NavigationBlue),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "查看地图轨迹",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 播放摘要
                OutlinedButton(
                    onClick = {
                        vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                        onPlaySummary()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandYellow.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text(
                        "▶ 播放摘要",
                        color = BrandYellow,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // 删除
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text(
                        "删除",
                        color = DangerRed,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  月份筛选Dialog
// ═══════════════════════════════════════════════════════════

@Composable
fun MonthFilterDialog(
    months: List<String>,
    selectedMonth: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = "选择月份",
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonthOption(
                    label = "全部月份",
                    isSelected = selectedMonth == null,
                    onClick = { onSelect(null); onDismiss() }
                )
                months.forEach { month ->
                    MonthOption(
                        label = month,
                        isSelected = month == selectedMonth,
                        onClick = { onSelect(month); onDismiss() }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun MonthOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) NavigationBlue.copy(alpha = 0.2f) else SurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics {
                selected = isSelected
                contentDescription = "$label${if (isSelected) "，已选中" else ""}"
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label,
                color = if (isSelected) NavigationBlue else TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  筛选按钮
// ═══════════════════════════════════════════════════════════

@Composable
fun AccessibleFilterButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = SurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .height(44.dp)
            .semantics {
                contentDescription = "当前筛选：$label，点击切换"
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = TextPrimary,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "\u25BC",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  空状态
// ═══════════════════════════════════════════════════════════

@Composable
fun EmptyHistoryState(
    modifier: Modifier = Modifier,
    speechViewModel: SpeechViewModel
) {
    LaunchedEffect(Unit) {
        delay(500)
        speechViewModel.queueInstruction("暂无跑步记录，点击开始跑步按钮开始你的第一次跑步吧")
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "\uD83C\uDFC3",
            style = MaterialTheme.typography.displaySmall,
            color = TextTertiary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无跑步记录",
            color = TextSecondary,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "开始你的第一次跑步吧",
            color = TextTertiary,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  配速色条颜色计算
// ═══════════════════════════════════════════════════════════

private fun calculatePaceColor(paceStr: String): Color {
    return try {
        val parts = paceStr.replace("\"", "").replace("'", ":").split(":")
        if (parts.size >= 2) {
            val seconds = parts[0].toInt() * 60 + parts[1].toInt()
            when {
                seconds <= 300 -> PaceBarFast
                seconds <= 360 -> BrandYellow
                seconds <= 420 -> PaceBarMedium
                else -> PaceBarSlow
            }
        } else {
            TextSecondary
        }
    } catch (_: Exception) {
        TextSecondary
    }
}
