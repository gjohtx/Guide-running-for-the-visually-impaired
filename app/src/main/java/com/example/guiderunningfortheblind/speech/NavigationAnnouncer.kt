package com.example.guiderunningfortheblind.speech

import android.util.Log
import com.example.guiderunningfortheblind.model.Announcement
import com.example.guiderunningfortheblind.model.AnnouncePriority
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 简化后的语音播报器
 * 移除导航相关指引，保留跑步数据和安全预警
 */
class NavigationAnnouncer(
    private val voiceQueueManager: VoiceQueueManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "NavigationAnnouncer"
        private const val DATA_COOLDOWN_MS = 15000L        // 数据播报冷却
        private const val DEDUP_WINDOW_MS = 30000L        // 防重复时间窗口
    }

    private val pendingAnnouncements = ConcurrentLinkedQueue<Announcement>()
    private var lastDataTime = 0L
    private val recentTexts = ArrayDeque<Pair<String, Long>>(20)
    
    private val announcerScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob() + Dispatchers.Main.immediate
    )
    private val isProcessing = AtomicBoolean(false)

    init {
        announcerScope.launch {
            while (isActive) {
                try {
                    processQueueIfIdle()
                    delay(500)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "队列处理异常", e)
                    delay(1000)
                }
            }
        }
    }

    fun announceSafetyAlert(alertText: String) {
        Log.d(TAG, "安全预警: $alertText")
        voiceQueueManager.stopAndClear()
        pendingAnnouncements.clear()
        speakAndLog(alertText, AnnouncePriority.EMERGENCY)
    }

    fun announceRunningData(distanceKm: Double, pace: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastDataTime < DATA_COOLDOWN_MS) return
        lastDataTime = now

        val min = durationMs / 60000
        val text = "您已跑步${"%.1f".format(distanceKm)}公里，用时${min}分钟，当前配速${pace}。"
        pendingAnnouncements.offer(
            createAnnouncement(text, AnnouncePriority.RUNNING_DATA)
        )
    }

    fun announceImmediate(text: String, priority: AnnouncePriority = AnnouncePriority.INFO) {
        voiceQueueManager.stopAndClear()
        pendingAnnouncements.clear()
        speakAndLog(text, priority)
    }

    fun reset() {
        pendingAnnouncements.clear()
        lastDataTime = 0L
        recentTexts.clear()
    }

    private suspend fun processQueueIfIdle() {
        if (isProcessing.get() || pendingAnnouncements.isEmpty() || !voiceQueueManager.isAvailable()) return
        if (voiceQueueManager.isSpeaking.value) return

        if (isProcessing.compareAndSet(false, true)) {
            try {
                val announcement = extractHighestPriority() ?: return
                if (!isDuplicate(announcement.text)) {
                    speakAndLog(announcement.text, announcement.priority)
                    recordAnnouncement(announcement.text)
                }
                delay(300)
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun speakAndLog(text: String, priority: AnnouncePriority) {
        Log.i(TAG, "播报 [$priority]: $text")
        voiceQueueManager.speak(text)
    }

    private fun extractHighestPriority(): Announcement? {
        if (pendingAnnouncements.isEmpty()) return null
        val list = pendingAnnouncements.toList()
        pendingAnnouncements.clear()
        val sorted = list.sortedBy { it.priority.level }
        val highest = sorted.firstOrNull() ?: return null
        sorted.drop(1).forEach { pendingAnnouncements.offer(it) }
        return highest
    }

    private fun createAnnouncement(text: String, priority: AnnouncePriority): Announcement {
        return Announcement(
            id = UUID.randomUUID().toString(),
            text = text,
            priority = priority,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun isDuplicate(text: String): Boolean {
        val now = System.currentTimeMillis()
        return recentTexts.any { (t, time) -> t == text && (now - time) < DEDUP_WINDOW_MS }
    }

    private fun recordAnnouncement(text: String) {
        recentTexts.addLast(text to System.currentTimeMillis())
        if (recentTexts.size > 20) recentTexts.removeFirst()
    }
}
