package com.jarvis.core

data class ToolCall(
    val toolName: String,
    val riskLevel: String,
    val parameters: Map<String, String>,
    val planId: String?
)

data class ToolResult(
    val status: String,
    val data: String = "",
    val error: String? = null
)

interface JarvisAgent {
    val supportedTools: Set<String>
    suspend fun execute(call: ToolCall): ToolResult
}
