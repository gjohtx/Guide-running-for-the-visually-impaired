package com.example.guiderunningfortheblind.ai.dashscope

import com.google.gson.annotations.SerializedName

/**
 * DashScope OpenAI 兼容端点数据模型（统一版）
 *
 * 端点：POST /compatible-mode/v1/chat/completions
 * 用于所有模型：qwen-plus（文本）、qwen-vl-plus-latest（视觉）等
 *
 * 【2026-05-27 统一】所有模型统一走兼容端点，不再使用多模态专用端点
 * - 文本模型：content = String
 * - 视觉模型：content = List<ContentPart>（图片+文本数组）
 *
 * 文档：https://help.aliyun.com/zh/model-studio/developer-reference/compatibility-of-openai-with-dashscope
 */

// ═══════════════════════════════════════════════════════════
//  请求模型
// ═══════════════════════════════════════════════════════════

/**
 * 聊天完成请求（OpenAI 兼容格式）
 *
 * @param model 模型名称，如 "qwen-plus" 或 "qwen-vl-plus-latest"
 * @param messages 消息列表
 */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>
)

/**
 * 聊天消息 DTO（content 支持 String 和 List<ContentPart> 两种格式）
 */
data class ChatMessageDto(
    val role: String,
    val content: Any  // String 或 List<ContentPart>
)

/**
 * 内容片段（用于多模态消息）
 *
 * 文本：ContentPart(type="text", text="内容")
 * 图片：ContentPart(type="image_url", image_url=ImageUrl("data:image/jpeg;base64,..."))
 */
data class ContentPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url")
    val imageUrl: com.example.guiderunningfortheblind.ai.dashscope.ImageUrl? = null
)

// ═══════════════════════════════════════════════════════════
//  响应模型
// ═══════════════════════════════════════════════════════════

/**
 * 聊天完成响应（OpenAI 兼容格式）
 */
data class ChatCompletionResponse(
    val choices: List<ChatChoice>?,
    val usage: ChatUsage?,
    @SerializedName("request_id")
    val requestId: String?
)

data class ChatChoice(
    val message: ChatResponseMessage?,
    @SerializedName("finish_reason")
    val finishReason: String?
)

/**
 * 响应消息 —— content 固定为 String（DashScope 兼容端点统一返回字符串）
 */
data class ChatResponseMessage(
    val role: String?,
    val content: String?
)

data class ChatUsage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int?,
    @SerializedName("completion_tokens")
    val completionTokens: Int?,
    @SerializedName("total_tokens")
    val totalTokens: Int?
)

/**
 * DashScope API 错误响应体
 *
 * HTTP 400/401/429/500 时返回：
 * {
 *   "error": {
 *     "code": "InvalidParameter",
 *     "message": "详细错误信息",
 *     "type": "invalid_request_error"
 *   }
 * }
 */
data class DashScopeErrorResponse(
    val error: DashScopeErrorDetail?
)

data class DashScopeErrorDetail(
    val code: String?,
    val message: String?,
    val type: String?
)
