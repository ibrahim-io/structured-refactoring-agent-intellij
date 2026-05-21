package com.example

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.inline.InlineRefactoringActionHandler

class AgentInlineAtCaretAction : AnAction("Agent: Inline at Caret") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            Messages.showInfoMessage(project, "No active editor.", "Agent Inline")
            return
        }
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: run {
            Messages.showInfoMessage(project, "No PSI file available.", "Agent Inline")
            return
        }

        val offset = editor.caretModel.offset
        val raw = psiFile.findElementAt(offset) ?: run {
            Messages.showInfoMessage(project, "No element at caret.", "Agent Inline")
            return
        }
        val named = findNamedAncestor(raw) ?: run {
            Messages.showInfoMessage(project, "No inlineable symbol at caret.", "Agent Inline")
            return
        }

        // InlineRefactoringActionHandler picks the right inliner based on element type
        // (InlineMethodProcessor, InlineLocalHandler, InlineConstantRefactoring, etc.)
        InlineRefactoringActionHandler().invoke(project, editor, psiFile, null)
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
