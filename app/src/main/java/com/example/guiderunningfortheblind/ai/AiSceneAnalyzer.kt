package com.example.guiderunningfortheblind.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.guiderunningfortheblind.ai.dashscope.ChatCompletionRequest
import com.example.guiderunningfortheblind.ai.dashscope.ChatMessageDto
import com.example.guiderunningfortheblind.ai.dashscope.ContentPart
import com.example.guiderunningfortheblind.ai.dashscope.DashScopeErrorResponse
import com.example.guiderunningfortheblind.ai.dashscope.DashScopeService
import com.example.guiderunningfortheblind.ai.dashscope.ImageUrl
import com.google.gson.Gson
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * AI 场景分析器 —— 通义千问 DashScope 版本
 * - Prompt重构：删除过度保守的"必须说无法判断"规则，改为鼓励积极分析
 * - 图片质量提升：分辨率提高到合适尺寸，JPEG质量从80%提到90%
 * - 新增画面质量自检：让模型主动评估画面质量并调整分析深度
 * - System Prompt重写：更积极的角色定位
 */
@Singleton
class AiSceneAnalyzer @Inject constructor(
    private val dashScopeService: DashScopeService,
    @Named("dashscope_api_key") private val apiKey: String
) {
    companion object {
        private const val TAG = "AiSceneAnalyzer"

        /** 图片理解模型：qwen-vl-plus-latest */
        private const val MODEL_VL = "qwen-vl-plus-latest"

        /**
         * 场景路况 Prompt（重写版）
         *
         * 【修复思路】
         * 旧版问题：规则2"看不清必须说无法判断"+规则3"100%确认才可说"的组合
         *         导致模型过度保守，几乎总是回复"无法判断"
         *
         * 新版策略：
         * 1. 删除"必须说无法判断"的硬性规则，改为鼓励尽可能分析
         * 2. 用"画面质量等级"让模型自主判断分析深度
         * 3. 提供具体的分析维度，引导模型输出结构化观察
         * 4. 保留"不要编造"的核心约束，但不再强制要求看不清时放弃分析
         */
        private val SCENE_PROMPT = """
            你是视障跑步者的眼睛。请仔细观察这张摄像头画面，分析前方路况。

            【画面质量自检】
            先评估画面质量：
            - 清晰：能辨认道路、物体轮廓 → 给出具体路况分析
            - 一般：能看到大致环境但细节模糊 → 给出环境类型+潜在风险提示
            - 很差：几乎看不到内容 → 才说"视线不佳，无法判断"

            【分析维度（根据画面质量选择输出深度）】
            1. 路面状况：平坦/有台阶/有坑/有斜坡/有积水
            2. 前方障碍物：行人、车辆、柱子、施工围栏、树枝等
            3. 障碍物方位和距离（估算）：左/中/右/前方，近/中/远
            4. 建议行动：减速/绕行/直行/注意脚下

            【输出要求】
            1. 画面清晰时：必须给出具体的路况描述（方位+物体+建议），30字以内
            2. 画面一般时：描述能看到的道路类型+提醒注意潜在危险，30字以内
            3. 只有画面确实极差时才说"视线不佳，无法判断"
            4. 绝对不要编造画面中不存在的东西
            5. 不要总说"无法判断"——只要能看到一点内容就尝试分析
            6. 直接输出叙述文字，不要前缀，不要解释你为什么这样判断

            【优秀输出示例】
            "前方5米有台阶，注意脚下"
            "左侧有行人靠近，靠右慢行"
            "前方道路施工，请绕行"
            "下坡路段，控制速度"
            "路面有积水，注意防滑"
            "画面较暗，建议放慢速度前行"
        """.trimIndent()

        /**
         * 环境概述 Prompt
         */
        private val ENVIRONMENT_PROMPT = """
            你是视障跑步者的眼睛。请仔细观察这张摄像头画面，描述当前跑步环境。

            【画面质量自检】
            先评估画面质量：
            - 清晰：能辨认道路类型和周围环境 → 给出具体环境描述
            - 一般：能看到大致场景但细节模糊 → 描述能确定的环境类型
            - 很差：几乎看不到内容 → 才说"无法判断当前环境"

            【分析维度】
            1. 道路类型：人行道/公园步道/马路/操场/楼梯/走廊
            2. 周围环境：树木/建筑/车辆/行人多不多
            3. 光线条件：明亮/阴暗/逆光
            4. 注意事项：施工/车辆/人流/路面不平等

            【输出要求】
            1. 画面清晰时：描述道路类型+周围环境+注意事项，30字以内
            2. 画面一般时：描述能确定的道路类型+提醒小心，30字以内
            3. 只有画面确实极差时才说"无法判断当前环境"
            4. 不要编造，但只要能看到一点内容就尝试分析
            5. 直接输出叙述文字，不要前缀

            【优秀输出示例】
            "公园步道，两侧有树木，路面平坦"
            "人行道，前方有施工围栏，注意绕行"
            "马路边，车辆较多，建议靠边慢跑"
            "夜间光线较暗，建议开启照明放慢速度"
        """.trimIndent()

        /**
         * System Prompt——更积极的角色定位
         */
        private val SYSTEM_PROMPT_VL = """
            你是视障跑步者的安全助手和眼睛。你的核心任务是通过分析摄像头拍摄的画面，帮助视障用户安全跑步。

            【重要原则】
            1. 积极分析：只要画面能看到任何内容，就必须尝试分析，不要轻易放弃
            2. 诚实描述：看到什么说什么，没看到的东西不编造
            3. 简洁实用：每句话控制在30字以内，直接给出行动建议
            4. 安全优先：宁可提醒过度，也不要遗漏潜在危险

            【避免的行为】
            - 不要总是说"无法判断"——除非画面完全黑屏或模糊到什么都看不见
            - 不要泛泛地说"前方畅通"——要说具体看到了什么
            - 不要长篇大论——视障用户需要快速获取信息
        """.trimIndent()

        private val gson = Gson()

        /** 图片最大宽度：提高到适当尺寸以保留更多细节 */
        private const val MAX_IMAGE_WIDTH = 1280
        /** 图片最大高度 */
        private const val MAX_IMAGE_HEIGHT = 720
        /** JPEG压缩质量：提高到90%保留更多细节 */
        private const val JPEG_QUALITY = 90
    }

    // 配额耗尽冷却机制
    @Volatile
    private var quotaCooldownUntil = 0L
    private val QUOTA_COOLDOWN_MS = 90_000L

    private fun isQuotaError(e: Throwable): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("quota") || msg.contains("exceeded") || msg.contains("429")
                || msg.contains("throttling") || msg.contains("rate limit")
    }

    private fun checkQuotaCooldown(): Boolean {
        val now = System.currentTimeMillis()
        if (now < quotaCooldownUntil) {
            Log.w(TAG, "API 配额冷却中，剩余 ${(quotaCooldownUntil - now) / 1000} 秒")
            return true
        }
        return false
    }

    // ═══════════════════════════════════════════════════════════
    //  场景路况分析
    // ═══════════════════════════════════════════════════════════

    suspend fun analyzeScene(bitmap: Bitmap): String? {
        if (checkQuotaCooldown()) return null

        return runCatching {
            val scaled = scaleBitmap(bitmap, maxWidth = MAX_IMAGE_WIDTH, maxHeight = MAX_IMAGE_HEIGHT)
            val imageBase64 = bitmapToBase64(scaled, JPEG_QUALITY)
            Log.d(TAG, "【场景分析】开始，图片大小: ${scaled.width}x${scaled.height}")
            val result = callVisionApi(imageBase64, SCENE_PROMPT)
            if (scaled !== bitmap) scaled.recycle()
            // 过滤掉虚假的"畅通"回答和过度保守的"无法判断"连发
            result?.takeIf {
                it.isNotBlank() && !isFakeClearResult(it) && !isOverlyConservative(it)
            }
        }.onFailure { e ->
            logDetailedError("场景分析", e)
            if (isQuotaError(e)) {
                quotaCooldownUntil = System.currentTimeMillis() + QUOTA_COOLDOWN_MS
            }
        }.getOrNull()
    }

    // ═══════════════════════════════════════════════════════════
    //  环境概述分析
    // ═══════════════════════════════════════════════════════════

    suspend fun analyzeEnvironment(bitmap: Bitmap): String? {
        if (checkQuotaCooldown()) return null

        return runCatching {
            val scaled = scaleBitmap(bitmap, maxWidth = MAX_IMAGE_WIDTH, maxHeight = MAX_IMAGE_HEIGHT)
            val imageBase64 = bitmapToBase64(scaled, JPEG_QUALITY)
            Log.d(TAG, "【环境分析】开始，图片大小: ${scaled.width}x${scaled.height}")
            val result = callVisionApi(imageBase64, ENVIRONMENT_PROMPT)
            if (scaled !== bitmap) scaled.recycle()
            result?.takeIf {
                it.isNotBlank() && !isFakeClearResult(it) && !isOverlyConservative(it)
            }
        }.onFailure { e ->
            logDetailedError("环境分析", e)
            if (isQuotaError(e)) {
                quotaCooldownUntil = System.currentTimeMillis() + QUOTA_COOLDOWN_MS
            }
        }.getOrNull()
    }

    // ═══════════════════════════════════════════════════════════
    //  结果过滤
    // ═══════════════════════════════════════════════════════════

    /**
     * 判断是否为虚假的"畅通"回答
     */
    private fun isFakeClearResult(text: String): Boolean {
        val lower = text.lowercase()
        // 如果回答包含具体物体描述，说明真的分析了画面，保留
        val hasSpecificObject = lower.contains("米") ||
                lower.contains("有") && (
                        lower.contains("台阶") || lower.contains("人") ||
                                lower.contains("柱") || lower.contains("坑") ||
                                lower.contains("门") || lower.contains("墙") ||
                                lower.contains("车") || lower.contains("障碍") ||
                                lower.contains("电线") || lower.contains("树枝") ||
                                lower.contains("石头") || lower.contains("箱子") ||
                                lower.contains("槛") || lower.contains("坡") ||
                                lower.contains("缝") || lower.contains("线") ||
                                lower.contains("施工") || lower.contains("积水") ||
                                lower.contains("栏杆") || lower.contains("斜坡")
                        )
        if (hasSpecificObject) return false // 有具体物体，保留

        // 如果只是泛泛地说"畅通""无障碍""可前行"，过滤掉
        val fakePatterns = listOf(
            "前方畅通", "畅通", "无障碍", "可前行", "可继续",
            "一切正常", "路况良好", "路面平坦", "安全",
            "没有障碍", "未见异常", "放心跑"
        )
        return fakePatterns.any { lower.contains(it) }
    }

    private fun isOverlyConservative(text: String): Boolean {
        val lower = text.lowercase()
        // 如果回答中包含任何实质性的分析内容（方位词、物体、建议），则不过滤
        val hasSubstantiveContent = lower.contains("米") ||
                lower.contains("前方") || lower.contains("左侧") ||
                lower.contains("右侧") || lower.contains("注意") ||
                lower.contains("减速") || lower.contains("慢行") ||
                lower.contains("绕行") || lower.contains("小心") ||
                lower.contains("靠") || lower.contains("公园") ||
                lower.contains("人行道") || lower.contains("马路") ||
                lower.contains("下坡") || lower.contains("上坡") ||
                lower.contains("施工") || lower.contains("积水") ||
                lower.contains("光线") || lower.contains("暗") ||
                lower.contains("周围") || lower.contains("道路类型") ||
                lower.contains("树") || lower.contains("车辆") ||
                lower.contains("跑步") || lower.contains("建议")

        if (hasSubstantiveContent) return false // 有实质内容，保留

        // 只有纯粹的搪塞回答才过滤
        val conservativePatterns = listOf(
            "无法判断", "看不清楚", "无法识别", "无法分析",
            "不能判断", "不能识别", "画面模糊", "不清楚"
        )
        // 如果整句话就是这些搪塞词，过滤掉
        val isPureExcuse = conservativePatterns.any { lower.contains(it) } &&
                !lower.contains("建议") && !lower.contains("注意") &&
                !lower.contains("小心") && !lower.contains("慢")
        return isPureExcuse
    }

    // ═══════════════════════════════════════════════════════════
    //  内部 API 调用
    // ═══════════════════════════════════════════════════════════

    private suspend fun callVisionApi(imageBase64: String, prompt: String): String? {
        val request = ChatCompletionRequest(
            model = MODEL_VL,
            messages = listOf(
                ChatMessageDto(
                    role = "system",
                    content = SYSTEM_PROMPT_VL
                ),
                ChatMessageDto(
                    role = "user",
                    content = listOf(
                        ContentPart(
                            type = "image_url",
                            imageUrl = ImageUrl(url = imageBase64)
                        ),
                        ContentPart(type = "text", text = prompt)
                    )
                )
            )
        )

        val response = dashScopeService.chatCompletions("Bearer $apiKey", request)

        val content = response.choices?.firstOrNull()?.message?.content?.trim()
        if (content.isNullOrBlank()) {
            Log.w(TAG, "API 返回空内容")
            return null
        }
        Log.d(TAG, "API 返回: $content")
        return content
    }

    // ═══════════════════════════════════════════════════════════
    //  详细错误日志
    // ═══════════════════════════════════════════════════════════

    private fun logDetailedError(context: String, e: Throwable) {
        when (e) {
            is HttpException -> {
                val code = e.code()
                val errorBody = e.response()?.errorBody()?.string()
                Log.e(TAG, "$context 失败: HTTP $code")
                if (!errorBody.isNullOrBlank()) {
                    Log.e(TAG, "错误响应体: $errorBody")
                    try {
                        val err = gson.fromJson(errorBody, DashScopeErrorResponse::class.java).error
                        if (err != null) {
                            Log.e(TAG, "错误详情: code=${err.code}, message=${err.message}")
                        }
                    } catch (_: Exception) {}
                }
            }
            else -> {
                Log.e(TAG, "$context 失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  工具方法
    // ═══════════════════════════════════════════════════════════

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val ratio = minOf(
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height
        )
        if (ratio >= 1f) return bitmap
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /**
     * Bitmap 转 Base64（可配置JPEG质量）
     */
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
