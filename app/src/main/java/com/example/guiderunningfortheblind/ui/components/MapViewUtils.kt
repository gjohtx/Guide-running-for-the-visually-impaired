package com.example.guiderunningfortheblind.ui.components

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.amap.api.maps.MapView

/**
 * MapView 生命周期管理工具
 *
 * 复用于所有需要显示高德地图的页面：
 * - NavigationRunningFragment（导航迷你地图）
 * - HistoryDetailFragment（历史轨迹地图）
 * - HistoryMapTrackScreen（历史轨迹全屏地图）
 *
 * 自动处理 MapView 的 onCreate/onResume/onPause/onDestroy 生命周期绑定，
 * 避免内存泄漏和 Native 库崩溃。
 */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    mapView.onCreate(Bundle())
                }
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> { /* MapView 无 onStop */ }
                Lifecycle.Event.ON_DESTROY -> {
                    try {
                        mapView.map?.setOnMapLoadedListener(null)
                    } catch (e: Exception) { /* ignore */ }
                    mapView.onDestroy()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            try {
                mapView.map?.setOnMapLoadedListener(null)
                mapView.onDestroy()
            } catch (e: Exception) {
                Log.e("MapViewUtils", "MapView onDispose 异常", e)
            }
        }
    }
    return mapView
}
