package com.example

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement

class AgentSafeDeleteAtCaretAction : AnAction("Agent: Safe Delete at Caret") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset
        val raw = psiFile.findElementAt(offset) ?: run {
            Messages.showInfoMessage(project, "No element at caret.", "Agent Safe Delete")
            return
        }
        val named = findNamedAncestor(raw) ?: run {
            Messages.showInfoMessage(project, "No deletable symbol at caret.", "Agent Safe Delete")
            return
        }

        val name = named.name ?: "<unnamed>"
        val confirm = Messages.showYesNoDialog(
            project,
            "Safe-delete \"$name\"? This checks for usages first.",
            "Agent Safe Delete",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return

        when (val result = project.service<RefactorService>().safeDelete(named)) {
            is RefactorService.Result.Ok -> Messages.showInfoMessage(project, result.message, "Agent Safe Delete")
            is RefactorService.Result.Err -> Messages.showErrorDialog(project, result.message, "Agent Safe Delete")
        }
    }

    private fun findNamedAncestor(start: PsiElement): PsiNamedElement? {
        var cur: PsiElement? = start
        while (cur != null) {
            if (cur is PsiNamedElement) return cur
            cur = cur.parent
        }
        return null
    }
}
