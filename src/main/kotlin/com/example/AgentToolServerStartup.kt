package com.example

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class AgentToolServerStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        // Force service instantiation so the HTTP server starts as soon as
        // the project finishes loading, without waiting for the first use.
        project.service<AgentToolServer>()
    }
}
