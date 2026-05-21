package com.example

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Thin client for the Anthropic Messages API.
 * Handles the tool-use loop: runs until stop_reason is "end_turn".
 */
class ClaudeClient(private val apiKey: String) {

    private val http = HttpClient.newHttpClient()
    private val endpoint = URI.create("https://api.anthropic.com/v1/messages")

    data class Message(val role: String, val content: Any) // content = String or JSONArray

    data class ToolResult(val toolUseId: String, val content: String)

    /**
     * Sends a conversation with tool definitions and runs the tool-use loop.
     * [systemPrompt] is injected as the system parameter on every API call.
     * [onUpdate] is called for each assistant text chunk and tool event.
     */
    fun chat(
        messages: MutableList<Message>,
        toolSchema: JSONArray,
        systemPrompt: String = "",
        maxTurns: Int = AgentRefactorSettings.instance().maxTurns,
        toolExecutor: (toolName: String, params: JSONObject) -> String,
        onUpdate: (ChatUpdate) -> Unit,
    ) {
        var turns = 0
        while (turns < maxTurns) {
            turns++
            val requestBody = buildRequest(messages, toolSchema, systemPrompt)
            val response = post(requestBody)
            val stopReason = response.optString("stop_reason", "end_turn")
            val contentArray = response.getJSONArray("content")

            val textParts = mutableListOf<String>()
            val toolUses = mutableListOf<Pair<JSONObject, ToolResult>>()

            for (i in 0 until contentArray.length()) {
                val block = contentArray.getJSONObject(i)
                when (block.getString("type")) {
                    "text" -> textParts.add(block.getString("text"))
                    "tool_use" -> {
                        val toolName = block.getString("name")
                        val toolId = block.getString("id")
                        val input = block.getJSONObject("input")
                        onUpdate(ChatUpdate.ToolCall(toolName, input))
                        val result = runCatching { toolExecutor(toolName, input) }
                            .getOrElse { e -> """{"error":${JSONObject.quote(e.message ?: e.javaClass.simpleName)}}""" }
                        toolUses.add(block to ToolResult(toolId, result))
                        onUpdate(ChatUpdate.ToolResult(toolName, result))
                    }
                }
            }

            if (textParts.isNotEmpty()) {
                onUpdate(ChatUpdate.AssistantText(textParts.joinToString("\n")))
            }

            // Append assistant turn
            messages.add(Message("assistant", contentArray))

            if (stopReason != "tool_use" || toolUses.isEmpty()) break

            // Build tool_result user turn
            val toolResultContent = JSONArray()
            for ((_, tr) in toolUses) {
                toolResultContent.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", tr.toolUseId)
                        .put("content", tr.content)
                )
            }
            messages.add(Message("user", toolResultContent))
        }
    }

    private fun buildRequest(messages: List<Message>, tools: JSONArray, systemPrompt: String): JSONObject {
        val messagesArr = JSONArray()
        for (m in messages) {
            val content = when (val c = m.content) {
                is String -> c
                is JSONArray -> c
                else -> c.toString()
            }
            messagesArr.put(JSONObject().put("role", m.role).put("content", content))
        }
        val req = JSONObject()
            .put("model", AgentRefactorSettings.instance().model)
            .put("max_tokens", 4096)
            .put("tools", tools)
            .put("messages", messagesArr)
        if (systemPrompt.isNotBlank()) req.put("system", systemPrompt)
        return req
    }

    private fun post(body: JSONObject): JSONObject {
        val req = HttpRequest.newBuilder(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(resp.body())
        if (resp.statusCode() != 200) {
            val err = json.optJSONObject("error")?.optString("message") ?: resp.body()
            throw RuntimeException("Anthropic API error ${resp.statusCode()}: $err")
        }
        return json
    }

    companion object {
        const val MODEL = "claude-sonnet-4-6"
    }
}

sealed class ChatUpdate {
    data class AssistantText(val text: String) : ChatUpdate()
    data class ToolCall(val name: String, val params: JSONObject) : ChatUpdate()
    data class ToolResult(val name: String, val result: String) : ChatUpdate()
}
