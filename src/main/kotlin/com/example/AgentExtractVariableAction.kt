package com.example

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.refactoring.introduceVariable.IntroduceVariableHandler

class AgentExtractVariableAction : AnAction("Agent: Extract Variable (selection)") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            Messages.showInfoMessage(project, "No active editor.", "Agent Extract Variable")
            return
        }
        if (!editor.selectionModel.hasSelection()) {
            Messages.showInfoMessage(project, "Select the expression to extract first.", "Agent Extract Variable")
            return
        }
        IntroduceVariableHandler().invoke(project, editor, e.getData(CommonDataKeys.PSI_FILE), null)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() == true
    }
}
