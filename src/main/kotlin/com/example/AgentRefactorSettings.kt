package com.example

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "AgentRefactorSettings", storages = [Storage("AgentRefactorSettings.xml")])
class AgentRefactorSettings : PersistentStateComponent<AgentRefactorSettings.State> {

    class State {
        @JvmField var port: Int = 6473
        @JvmField var model: String = "claude-sonnet-4-6"
        @JvmField var maxTurns: Int = 12
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var port: Int
        get() = myState.port
        set(value) { myState.port = value }

    var model: String
        get() = myState.model
        set(value) { myState.model = value }

    var maxTurns: Int
        get() = myState.maxTurns
        set(value) { myState.maxTurns = value }

    companion object {
        fun instance(): AgentRefactorSettings =
            ApplicationManager.getApplication().getService(AgentRefactorSettings::class.java)
    }
}
