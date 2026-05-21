package com.example

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

class AgentRefactorConfigurable : Configurable {

    private var portField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var maxTurnsField: JBTextField? = null

    override fun getDisplayName() = "Agent Refactor"

    override fun createComponent(): JComponent {
        val s = AgentRefactorSettings.instance()
        portField = JBTextField(s.port.toString(), 8)
        modelField = JBTextField(s.model, 32)
        maxTurnsField = JBTextField(s.maxTurns.toString(), 8)

        val noteLabel = JBLabel(
            "<html><small><i>Port change takes effect after reopening the project.</i></small></html>"
        ).apply { border = JBUI.Borders.emptyTop(2) }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Tool server port:", portField!!)
            .addComponent(noteLabel)
            .addSeparator(8)
            .addLabeledComponent("Claude model ID:", modelField!!)
            .addLabeledComponent("Max tool-use turns:", maxTurnsField!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = AgentRefactorSettings.instance()
        return portField?.text?.toIntOrNull() != s.port
            || modelField?.text?.trim() != s.model
            || maxTurnsField?.text?.toIntOrNull() != s.maxTurns
    }

    override fun apply() {
        val s = AgentRefactorSettings.instance()
        portField?.text?.toIntOrNull()?.let { s.port = it }
        modelField?.text?.trim()?.takeIf { it.isNotBlank() }?.let { s.model = it }
        maxTurnsField?.text?.toIntOrNull()?.let { s.maxTurns = it }
    }

    override fun reset() {
        val s = AgentRefactorSettings.instance()
        portField?.text = s.port.toString()
        modelField?.text = s.model
        maxTurnsField?.text = s.maxTurns.toString()
    }

    override fun disposeUIResources() {
        portField = null
        modelField = null
        maxTurnsField = null
    }
}
