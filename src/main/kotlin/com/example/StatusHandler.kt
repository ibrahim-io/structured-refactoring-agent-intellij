package com.example

import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.json.JSONObject

/** GET /status — returns project name and server port so callers can verify connectivity. */
class StatusHandler(private val project: Project) : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        cors(exchange)
        if (exchange.requestMethod == "OPTIONS") {
            exchange.sendResponseHeaders(204, -1)
            return
        }
        val body = JSONObject()
            .put("ok", true)
            .put("project", project.name)
            .put("port", AgentToolServer.PORT)
            .put("tools", listOf(
                "find_symbol_by_name", "list_symbols",
                "find_symbol", "rename_symbol", "safe_delete",
                "add_field", "add_method", "add_inner_class", "create_java_file",
                "move_class", "change_signature",
                "add_kt_property", "add_kt_function", "create_kotlin_file"
            ))
            .toString()
            .toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}

internal fun cors(exchange: HttpExchange) {
    exchange.responseHeaders.add("Access-Control-Allow-Origin", "http://localhost:*")
    exchange.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    exchange.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type")
}
