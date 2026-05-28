package com.example.guiderunningfortheblind.ai.dashscope

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * 通义千问 DashScope API 接口（统一端点版）
 *
 * Base URL: https://dashscope.aliyuncs.com/
 * 统一使用 OpenAI 兼容端点：compatible-mode/v1/chat/completions
 *
 * 【2026-05-27 统一】所有模型统一走兼容端点，不再使用多模态专用端点
 * 原因：qwen-vl-plus-latest 不支持多模态端点，只支持兼容端点
 *
 * 支持的模型：
 * - qwen-plus：文本对话
 * - qwen-vl-plus-latest：图片理解
 * - qwen-max：最强文本模型
 */
interface DashScopeService {

    /**
     * 【统一端点】OpenAI 兼容聊天完成
     *
     * 适用所有模型：
     * - 文本模型（qwen-plus）：content 为 String
     * - 视觉模型（qwen-vl-plus-latest）：content 为 List<ContentPart>
     *
     * @param authorization "Bearer {api_key}"
     * @param request 包含 model、messages 的完整请求体
     */
    @POST("compatible-mode/v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}
