package com.example

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.refactoring.extractMethod.ExtractMethodHandler

class AgentExtractMethodAction : AnAction("Agent: Extract Method (selection)") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            Messages.showInfoMessage(project, "No active editor.", "Agent Extract Method")
            return
        }
        if (!editor.selectionModel.hasSelection()) {
            Messages.showInfoMessage(project, "Select the code block to extract first.", "Agent Extract Method")
            return
        }
        // Delegates to IntelliJ's standard extract-method dialog — it handles naming,
        // signature, and all AST rewrites.
        ExtractMethodHandler().invoke(project, editor, e.getData(CommonDataKeys.PSI_FILE), null)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() == true
    }
}
