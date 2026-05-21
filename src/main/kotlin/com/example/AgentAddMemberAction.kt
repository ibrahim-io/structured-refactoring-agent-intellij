package com.example

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class AgentAddMemberAction : AnAction("Agent: Add Member to Class at Caret") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)
        val psiClass = generateSequence(element) { it.parent }
            .filterIsInstance<com.intellij.psi.PsiClass>()
            .firstOrNull()
        if (psiClass == null) {
            Messages.showInfoMessage(project, "Place the caret inside a class body.", "Agent Add Member")
            return
        }

        val dialog = AddMemberDialog(project, psiClass.name ?: "class")
        if (!dialog.showAndGet()) return

        val svc = project.service<PsiCreationService>()
        val result = when (dialog.kind()) {
            MemberKind.FIELD -> svc.addField(psiFile.virtualFile.path, psiClass.name, dialog.text())
            MemberKind.METHOD -> svc.addMethod(psiFile.virtualFile.path, psiClass.name, dialog.text())
            MemberKind.INNER_CLASS -> svc.addInnerClass(psiFile.virtualFile.path, psiClass.name, dialog.text())
        }
        when (result) {
            is PsiCreationService.Result.Ok -> Messages.showInfoMessage(project, result.message, "Agent Add Member")
            is PsiCreationService.Result.Err -> Messages.showErrorDialog(project, result.message, "Agent Add Member")
        }
    }
}

private enum class MemberKind { FIELD, METHOD, INNER_CLASS }

private class AddMemberDialog(project: com.intellij.openapi.project.Project, className: String) :
    DialogWrapper(project, true) {

    private val fieldBtn = JBRadioButton("Field", true)
    private val methodBtn = JBRadioButton("Method")
    private val innerClassBtn = JBRadioButton("Inner class / interface")
    private val textArea = JBTextArea(10, 60).apply {
        lineWrap = false
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        border = JBUI.Borders.empty(4)
    }

    init {
        title = "Add Member to $className"
        ButtonGroup().apply {
            add(fieldBtn); add(methodBtn); add(innerClassBtn)
        }
        fieldBtn.addActionListener { textArea.text = "private int myField;" }
        methodBtn.addActionListener { textArea.text = "public void myMethod() {\n    \n}" }
        innerClassBtn.addActionListener { textArea.text = "public static class Inner {\n    \n}" }
        textArea.text = "private int myField;"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val kindRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("Kind: "))
            add(fieldBtn); add(Box.createHorizontalStrut(8))
            add(methodBtn); add(Box.createHorizontalStrut(8))
            add(innerClassBtn)
        }
        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            add(kindRow, BorderLayout.NORTH)
            add(JBScrollPane(textArea).apply {
                preferredSize = Dimension(600, 200)
            }, BorderLayout.CENTER)
        }
    }

    fun kind(): MemberKind = when {
        methodBtn.isSelected -> MemberKind.METHOD
        innerClassBtn.isSelected -> MemberKind.INNER_CLASS
        else -> MemberKind.FIELD
    }

    fun text(): String = textArea.text.trim()
}
