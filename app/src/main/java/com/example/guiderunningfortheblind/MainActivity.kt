package com.example.guiderunningfortheblind

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.example.guiderunningfortheblind.speech.VoiceCoordinator
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity - 应用主入口
 *
 * 【修复摘要】
 * 1. 修复 voiceCoordinator.observe() 被重复调用的问题
 * 2. 权限回调中正确通知 VoiceCoordinator 权限变化
 * 3. 权限回调中不再重复调用 observe()
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var voiceCoordinator: VoiceCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<androidx.fragment.app.FragmentContainerView>(
            R.id.nav_host_fragment
        )?.let { navHost ->
            navHost.setViewTreeLifecycleOwner(this)
        }

        voiceCoordinator = (application as MainApplication).voiceCoordinator
        // 只在这里调用一次 observe，不再在权限回调中重复调用
        voiceCoordinator.observe(this)

        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val normalPerms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            normalPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = normalPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                toRequest.toTypedArray(),
                1001
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1001) {
            val recordAudioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
            if (recordAudioIndex >= 0 && recordAudioIndex < grantResults.size) {
                val granted = grantResults[recordAudioIndex] == PackageManager.PERMISSION_GRANTED
                voiceCoordinator.onAudioPermissionResult(granted)
            }
        }

        // 处理后台定位权限请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasLocation) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    1002
                )
            }
        }
    }
}
