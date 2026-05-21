package com.example

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile

class AgentMoveClassAction : AnAction("Agent: Move Class to Package") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: run {
            Messages.showInfoMessage(project, "Open a Java file first.", "Agent Move Class")
            return
        }
        val editor = e.getData(CommonDataKeys.EDITOR)
        val cls: PsiClass? = if (editor != null) {
            val offset = editor.caretModel.offset
            val raw = psiFile.findElementAt(offset)
            generateSequence(raw) { it.parent }.filterIsInstance<PsiClass>().firstOrNull()
                ?: psiFile.classes.firstOrNull()
        } else {
            psiFile.classes.firstOrNull()
        }

        if (cls == null) {
            Messages.showInfoMessage(project, "No class found in file.", "Agent Move Class")
            return
        }

        val currentPkg = psiFile.packageName
        val targetPkg = Messages.showInputDialog(
            project,
            "Move class \"${cls.name}\" to package:",
            "Agent Move Class",
            Messages.getQuestionIcon(),
            currentPkg,
            null,
        ) ?: return

        when (val result = project.service<RefactorService>().moveClass(
            qualifiedClassName = cls.qualifiedName ?: cls.name ?: return,
            targetPackage = targetPkg,
        )) {
            is RefactorService.Result.Ok -> Messages.showInfoMessage(project, result.message, "Agent Move Class")
            is RefactorService.Result.Err -> Messages.showErrorDialog(project, result.message, "Agent Move Class")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PSI_FILE) is PsiJavaFile
    }
}
