package com.example.guiderunningfortheblind.ai.dashscope

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * 通义千问 DashScope API 请求/响应数据模型（修正版）
 *
 * 关键修正：
 * - content 字段支持 String（文本模型）和 List<ContentItem>（多模态模型）两种格式
 * - 图片项使用 image_url 格式（符合 DashScope 原生接口规范）
 * - 响应中 content 统一用 JsonElement 解析，然后提取文本
 *
 * 接口文档：https://help.aliyun.com/zh/model-studio/developer-reference/
 */

// ═══════════════════════════════════════════════════════════
//  请求模型
// ═══════════════════════════════════════════════════════════

data class DashScopeRequest(
    val model: String,
    val input: Input,
    val parameters: Parameters = Parameters()
)

data class Input(
    val messages: List<DashScopeMessage>
)

/**
 * content 同时支持两种格式：
 * - 文本模型（qwen-turbo）：String
 * - 多模态模型（qwen-vl-plus）：List<ContentItem>
 */
data class DashScopeMessage(
    val role: String,
    val content: Any  // String 或 List<ContentItem>
)

/**
 * 内容项，支持文本和图片
 *
 * 文本：ContentItem(type="text", text="内容")
 * 图片：ContentItem(type="image_url", image_url=ImageUrl("data:image/jpeg;base64,xxx"))
 */
data class ContentItem(
    val type: String,
    val text: String? = null,
    val image_url: ImageUrl? = null
)

data class ImageUrl(
    val url: String
)

data class Parameters(
    @SerializedName("result_format")
    val resultFormat: String = "message"
)

// ═══════════════════════════════════════════════════════════
//  响应模型
// ═══════════════════════════════════════════════════════════

data class DashScopeResponse(
    val output: Output?,
    val usage: Usage?,
    @SerializedName("request_id")
    val requestId: String?,
    /**
     * 错误信息（请求失败时）
     */
    @SerializedName("code")
    val errorCode: String? = null,
    @SerializedName("message")
    val errorMessage: String? = null
)

data class Output(
    val choices: List<Choice>?
)

data class Choice(
    val message: ResponseMessage?,
    @SerializedName("finish_reason")
    val finishReason: String?
)

/**
 * 响应消息 —— content 用 JsonElement 接收（可能是 String 或 Array）
 */
data class ResponseMessage(
    val role: String?,
    val content: JsonElement?  // 统一用 JsonElement，然后提取文本
)

data class Usage(
    @SerializedName("input_tokens")
    val inputTokens: Int?,
    @SerializedName("output_tokens")
    val outputTokens: Int?
)

// ═══════════════════════════════════════════════════════════
//  辅助方法：从响应中提取纯文本
// ═══════════════════════════════════════════════════════════

/**
 * 从 ResponseMessage 中提取纯文本内容
 *
 * 支持两种格式：
 * - 字符串格式（qwen-turbo）：直接返回
 * - 数组格式（qwen-vl-plus）：遍历 ContentItem 提取 text 字段
 */
fun ResponseMessage.extractText(): String {
    val c = this.content ?: return ""
    return when {
        c.isJsonPrimitive -> c.asString
        c.isJsonArray -> {
            val items = c.asJsonArray.mapNotNull { element ->
                try {
                    val obj = element.asJsonObject
                    val type = obj.get("type")?.asString ?: ""
                    when (type) {
                        "text" -> obj.get("text")?.asString ?: ""
                        else -> ""
                    }
                } catch (_: Exception) {
                    ""
                }
            }
            items.filter { it.isNotBlank() }.joinToString("")
        }
        else -> ""
    }
}
