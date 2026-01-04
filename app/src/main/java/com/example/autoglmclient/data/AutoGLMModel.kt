package com.example.autoglmclient.data

// === 1. 请求结构 (OpenAI/Zhipu 兼容格式) ===
data class OpenAiRequest(
    // [修改点] 默认模型改为 autoglm-phone
    val model: String = "autoglm-phone",
    val messages: List<Message>,
    val temperature: Double = 0.1, // 低温度以保证指令准确
    val max_tokens: Int = 1024
)

data class Message(
    val role: String,
    val content: List<Content>
)

data class Content(
    val type: String, // "text" or "image_url"
    val text: String? = null,
    val image_url: ImageUrl? = null
)

data class ImageUrl(
    val url: String // 格式: "data:image/jpeg;base64,{BASE64_STRING}"
)

// === 2. 响应结构 ===
data class OpenAiResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: MessageContent
)

data class MessageContent(
    val content: String // 这里面才是模型返回的真正文本（包含 thought 和 action 的 JSON）
)

// === 3. 本地解析用的简单模型 ===
// 模型返回的文本我们会尝试解析成这个结构
// === 3. 本地解析用的简单模型 ===
// 修改后的 AgentCommand 类
data class AgentCommand(
    val thought: String,
    val action: String,
    val params: List<Int>,
    val appName: String? = null,
    val text: String? = null // [新增] 用于存储 Input 指令的文本
)