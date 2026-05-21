package com.example

import com.intellij.lang.LanguageExtension
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.changeSignature.ChangeSignatureUtil

/**
 * Delegates to IntelliJ's built-in Change Signature dialog for the method at caret.
 * For programmatic/agent use, the change_signature HTTP tool is available instead.
 */
class AgentChangeSignatureAction : AnAction("Agent: Change Signature at Caret") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset
        val raw = psiFile.findElementAt(offset) ?: run {
            Messages.showInfoMessage(project, "No element at caret.", "Agent Change Signature")
            return
        }
        generateSequence(raw as PsiElement?) { it.parent }
            .filterIsInstance<PsiMethod>()
            .firstOrNull() ?: run {
            Messages.showInfoMessage(project, "Place the caret inside a method signature.", "Agent Change Signature")
            return
        }

        // Get the handler registered for this language (works for Java and Kotlin)
        val language = psiFile.language
        val handler: ChangeSignatureHandler? =
            LanguageExtension<ChangeSignatureHandler>("com.intellij.refactoring.changeSignatureHandler")
                .forLanguage(language)
        if (handler == null) {
            Messages.showInfoMessage(project,
                "Change Signature is not supported for ${language.displayName}.", "Agent Change Signature")
            return
        }
        handler.invoke(project, editor, psiFile, e.dataContext)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
    }
}
