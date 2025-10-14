package com.example

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor

class AgentRenameAtCaretAction : AnAction("Agent: Rename at Caret") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val psiFile = e.getRequiredData(CommonDataKeys.PSI_FILE)

        // Find PSI element under caret
        val offset = editor.caretModel.offset
        val elementAtCaret = psiFile.findElementAt(offset) ?: run {
            Messages.showInfoMessage(project, "No element at caret.", "Agent Rename")
            return
        }

        // Walk up to a PsiNamedElement (method/class/field/parameter)
        val named = findNamedAncestor(elementAtCaret)
        if (named == null) {
            Messages.showInfoMessage(project, "No renamable symbol at caret.", "Agent Rename")
            return
        }

        val currentName = named.name ?: "<unnamed>"
        val newName = Messages.showInputDialog(
            project,
            "Rename \"$currentName\" to:",
            "Agent Rename",
            Messages.getQuestionIcon(),
            "",
            null
        ) ?: return

        // Run IntelliJ's structured Rename (not text replace)
        WriteCommandAction.runWriteCommandAction(project) {
            RenameProcessor(project, named, newName, /*searchInComments*/ true, /*searchTextOccurrences*/ true).run()
        }
        Messages.showInfoMessage(project, "Renamed \"$currentName\" → \"$newName\".", "Agent Rename")
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
