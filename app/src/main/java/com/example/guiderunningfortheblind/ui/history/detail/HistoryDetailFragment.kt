package com.example.guiderunningfortheblind.ui.history.detail

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.guiderunningfortheblind.MainApplication
import com.example.guiderunningfortheblind.ui.theme.GuideRunningFortheBlindTheme
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.example.guiderunningfortheblind.data.local.entity.RunningSessionEntity
import com.example.guiderunningfortheblind.ui.components.rememberMapViewWithLifecycle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 历史跑步记录详情页
 *
 * 功能：
 * 1. 顶部：高德地图，显示当时的跑步轨迹（蓝色折线）
 *    - 起点标记（绿色）
 *    - 终点标记（红色）
 *    - 完整移动路径
 * 2. 底部：跑步数据统计卡片
 *    - 总距离、平均配速、障碍物次数等
 *
 * 【修复】JSON 解析兼容 lat/lng 和 latitude/longitude 两种格式
 */
class HistoryDetailFragment : Fragment() {

    private val viewModel: HistoryDetailViewModel by viewModels {
        HistoryDetailViewModel.Factory(
            (requireActivity().application as MainApplication).runningSessionRepository,
            arguments?.getLong("sessionId") ?: -1L
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val session by viewModel.session.collectAsStateWithLifecycle()
                GuideRunningFortheBlindTheme {
                    session?.let {
                        HistoryDetailScreen(session = it)
                    } ?: Box(modifier = Modifier.fillMaxSize()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

/**
 * 历史详情主界面
 *
 * @param session 跑步会话数据
 */
@Composable
fun HistoryDetailScreen(session: RunningSessionEntity) {

    // ── 解析轨迹点 JSON（兼容两种格式）──
    val routePoints: List<LatLng> = remember(session.routePointJson) {
        parseRoutePoints(session.routePointJson)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── 上半部分：地图 ──
        Box(modifier = Modifier.weight(1f)) {
            if (routePoints.isNotEmpty()) {
                HistoryDetailMap(routePoints = routePoints)
            } else {
                // 无轨迹数据时的占位
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Map,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "无轨迹数据",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── 下半部分：数据统计卡片 ──
        HistoryDetailStatsCard(session = session)
    }
}

/**
 * 历史详情地图组件
 *
 * 使用高德 MapView 显示：
 * - 蓝色折线：完整跑步轨迹
 * - 绿色标记：起点
 * - 红色标记：终点
 * - 自动调整视野包含整条轨迹
 *
 * @param routePoints 轨迹点列表
 */
@Composable
private fun HistoryDetailMap(routePoints: List<LatLng>) {
    val mapView = rememberMapViewWithLifecycle()
    var isMapLoaded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (!isMapLoaded) {
                    view.map?.setOnMapLoadedListener { isMapLoaded = true }
                    return@AndroidView
                }
                try {
                    val aMap = view.map ?: return@AndroidView
                    aMap.clear()

                    if (routePoints.size >= 2) {
                        // 1. 绘制跑步轨迹（蓝色折线）
                        aMap.addPolyline(
                            PolylineOptions()
                                .addAll(routePoints)
                                .color(0xFF1976D2.toInt())
                                .width(14f)
                                .lineCapType(PolylineOptions.LineCapType.LineCapRound)
                                .lineJoinType(PolylineOptions.LineJoinType.LineJoinRound)
                        )

                        // 2. 起点标记（绿色）
                        aMap.addMarker(
                            MarkerOptions()
                                .position(routePoints.first())
                                .title("起点")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                        )

                        // 3. 终点标记（红色）
                        aMap.addMarker(
                            MarkerOptions()
                                .position(routePoints.last())
                                .title("终点")
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        )

                        // 4. 调整相机视野包含整条轨迹
                        val boundsBuilder = LatLngBounds.Builder()
                        routePoints.forEach { boundsBuilder.include(it) }
                        try {
                            aMap.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120)
                            )
                        } catch (e: Exception) {
                            aMap.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(routePoints.first(), 16f)
                            )
                        }
                    } else if (routePoints.size == 1) {
                        // 只有一个点
                        aMap.addMarker(
                            MarkerOptions()
                                .position(routePoints.first())
                                .title("位置")
                        )
                        aMap.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(routePoints.first(), 16f)
                        )
                    }
                } catch (e: Exception) {
                    Log.e("HistoryDetail", "高德地图渲染失败", e)
                }
            }
        )

        // 地图左上角标签
        Surface(
            modifier = Modifier
                .padding(12.dp)
                .align(Alignment.TopStart),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ) {
            Text(
                text = "跑步轨迹",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 历史详情统计卡片
 *
 * @param session 跑步会话数据
 */
@Composable
private fun HistoryDetailStatsCard(session: RunningSessionEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "跑步数据",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 使用 Row 布局展示数据，每行两项
            Row(modifier = Modifier.fillMaxWidth()) {
                HistoryStatItem(
                    label = "总距离",
                    value = "%.2f 公里".format(session.totalDistance / 1000),
                    modifier = Modifier.weight(1f)
                )
                HistoryStatItem(
                    label = "平均配速",
                    value = session.avgPace,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                HistoryStatItem(
                    label = "障碍物次数",
                    value = "${session.obstacleCount} 次",
                    modifier = Modifier.weight(1f)
                )
                HistoryStatItem(
                    label = "平均心率",
                    value = "${session.avgHeartRate} bpm",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                HistoryStatItem(
                    label = "有氧耐力评分",
                    value = "${session.aerobicEnduranceScore} 分",
                    modifier = Modifier.weight(1f)
                )
                HistoryStatItem(
                    label = "建议恢复",
                    value = "${session.recoveryTimeHours} 小时",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 统计单项
 */
@Composable
private fun HistoryStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  【修复】轨迹点 JSON 解析 - 兼容两种格式
// ═══════════════════════════════════════════════════════════════

/**
 * 解析跑步轨迹 JSON 字符串
 *
 * 兼容两种存储格式：
 * 1. [{"lat": 39.9, "lng": 116.4}, ...]  <- 短格式
 * 2. [{"latitude": 39.9, "longitude": 116.4}, ...]  <- 高德 LatLng 标准格式
 *
 * @param json 轨迹点 JSON 字符串
 * @return LatLng 列表
 */
fun parseRoutePoints(json: String): List<LatLng> {
    if (json.isBlank()) return emptyList()
    return try {
        // 先尝试短格式 {"lat": x, "lng": y}
        val shortType = object : TypeToken<List<Map<String, Double>>>() {}.type
        val shortPoints: List<Map<String, Double>>? = try {
            Gson().fromJson(json, shortType)
        } catch (e: Exception) { null }

        if (shortPoints != null && shortPoints.isNotEmpty()) {
            val first = shortPoints.first()
            if (first.containsKey("lat") || first.containsKey("lng")) {
                return shortPoints.map {
                    LatLng(
                        it["lat"] ?: it["latitude"] ?: 0.0,
                        it["lng"] ?: it["longitude"] ?: 0.0
                    )
                }.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            }
        }

        // 再尝试标准格式 {"latitude": x, "longitude": y}
        val standardType = object : TypeToken<List<Map<String, Double>>>() {}.type
        val standardPoints: List<Map<String, Double>> = Gson().fromJson(json, standardType)
        standardPoints.map {
            LatLng(
                it["latitude"] ?: it["lat"] ?: 0.0,
                it["longitude"] ?: it["lng"] ?: 0.0
            )
        }.filter { it.latitude != 0.0 && it.longitude != 0.0 }

    } catch (e: Exception) {
        Log.e("HistoryDetail", "轨迹 JSON 解析失败: ${e.message}")
        // 最终降级：尝试使用高德 LatLng 的 Gson 反序列化
        try {
            val latLngType = object : TypeToken<List<LatLng>>() {}.type
            Gson().fromJson<List<LatLng>>(json, latLngType) ?: emptyList()
        } catch (e2: Exception) {
            Log.e("HistoryDetail", "LatLng 直接反序列化也失败")
            emptyList()
        }
    }
}
