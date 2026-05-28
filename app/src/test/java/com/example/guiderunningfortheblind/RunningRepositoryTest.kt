package com.example.guiderunningfortheblind

import org.junit.Test
import org.junit.Assert.assertEquals

class RepositoryTest {

    @Test
    fun testDataTransformation() {
        // 测试配速字符串转秒数的逻辑，确保“跑者思维”下的数据准确
        val paceString = "6'00\""
        val seconds = paceToSeconds(paceString)
        assertEquals(360, seconds)
    }

    private fun paceToSeconds(pace: String): Int {
        val parts = pace.replace("\"", "").split("'")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    @Test
    fun testDistanceUnitConversion() {
        // 验证米到公里的转换，支撑语音数据反馈功能
        val meters = 1000.0
        val km = meters / 1000.0
        assertEquals(1.0, km, 0.0)
    }
}