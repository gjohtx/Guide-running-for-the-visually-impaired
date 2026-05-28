package com.example.guiderunningfortheblind.model

data class DeviceCheckState(
    val isGpsFixed: Boolean = false, // GPS信号正常
    val batteryLevel: Int = 0, // 电量百分比
    val isCameraClean: Boolean = false, // 摄像头清洁度
    val isPeripheralConnected: Boolean = false, // 蓝牙外设（心率带）绑定状态
    val isFlashlightReady: Boolean = true // 闪光灯状态
)
