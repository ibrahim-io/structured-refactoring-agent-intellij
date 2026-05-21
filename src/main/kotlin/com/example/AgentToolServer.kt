package com.example

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * Localhost HTTP server that exposes structured refactorings as JSON tools an external
 * LLM agent can call.  Started when the project opens, stopped on project close.
 *
 * Port: 6473 (configurable later via settings).
 */
@Service(Service.Level.PROJECT)
class AgentToolServer(private val project: Project) : Disposable {

    private val log = thisLogger()
    private var server: com.sun.net.httpserver.HttpServer? = null

    init {
        start()
    }

    private fun start() {
        try {
            val port = AgentRefactorSettings.instance().port
            val srv = com.sun.net.httpserver.HttpServer.create(
                java.net.InetSocketAddress("127.0.0.1", port), 0
            )
            srv.createContext("/tools", ToolsHandler(project))
            srv.createContext("/tools/schema", SchemaHandler())
            srv.createContext("/status", StatusHandler(project))
            srv.executor = java.util.concurrent.Executors.newCachedThreadPool()
            srv.start()
            server = srv
            log.info("AgentToolServer listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            log.warn("AgentToolServer failed to start: ${e.message}")
        }
    }

    override fun dispose() {
        server?.stop(0)
        server = null
    }

    companion object {
        const val PORT = 6473
    }
}
