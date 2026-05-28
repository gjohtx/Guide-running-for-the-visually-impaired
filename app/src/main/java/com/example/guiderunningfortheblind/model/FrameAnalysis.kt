package com.example.guiderunningfortheblind.model

import com.example.guiderunningfortheblind.camera.ObstacleDetector

data class FrameAnalysis(
    val timestamp: Long,
    val detections: List<ObstacleDetector.DetectionResult>
)