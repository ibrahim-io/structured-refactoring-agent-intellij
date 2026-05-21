package com.example

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager

@Service(Service.Level.PROJECT)
class PsiCreationService(private val project: Project) {

    sealed class Result {
        data class Ok(val message: String) : Result()
        data class Err(val message: String) : Result()
    }

    // ── Members on existing classes ──────────────────────────────────────────

    fun addField(filePath: String, className: String?, fieldText: String): Result {
        val cls = findClass(filePath, className)
            ?: return Result.Err("Class '${className ?: "<first>"}' not found in $filePath")
        val factory = JavaPsiFacade.getElementFactory(project)
        return runWriteOnEdt("Agent Add Field") {
            val field = factory.createFieldFromText(fieldText.trim().ensureSemicolon(), cls)
            val added = cls.add(field)
            shortenAndReformat(added)
            Result.Ok("Added field to ${cls.name}")
        }
    }

    fun addMethod(filePath: String, className: String?, methodText: String): Result {
        val cls = findClass(filePath, className)
            ?: return Result.Err("Class '${className ?: "<first>"}' not found in $filePath")
        val factory = JavaPsiFacade.getElementFactory(project)
        return runWriteOnEdt("Agent Add Method") {
            val method = factory.createMethodFromText(methodText.trim(), cls)
            val added = cls.add(method)
            shortenAndReformat(added)
            Result.Ok("Added method to ${cls.name}")
        }
    }

    fun addInnerClass(filePath: String, outerClassName: String?, innerClassText: String): Result {
        val outer = findClass(filePath, outerClassName)
            ?: return Result.Err("Class '${outerClassName ?: "<first>"}' not found in $filePath")
        val factory = JavaPsiFacade.getElementFactory(project)
        return runWriteOnEdt("Agent Add Inner Class") {
            val inner = factory.createClassFromText(innerClassText.trim(), outer)
            val added = outer.add(inner)
            shortenAndReformat(added)
            Result.Ok("Added inner class to ${outer.name}")
        }
    }

    // ── Create new top-level files ───────────────────────────────────────────

    /**
     * Creates a new Java source file in [packageName].
     * [content] should be the full file text (including package statement).
     * [fileName] must end with ".java".
     *
     * The file is placed in the first writable source root that contains [packageName].
     */
    fun createJavaFile(packageName: String, fileName: String, content: String): Result {
        if (!fileName.endsWith(".java")) return Result.Err("fileName must end with .java")
        val targetDir = resolvePackageDirectory(packageName)
            ?: return Result.Err("Package '$packageName' not found in any source root. Make sure the directory exists.")

        val factory = PsiFileFactory.getInstance(project)
        return runWriteOnEdt("Agent Create File") {
            val existing = targetDir.findFile(fileName)
            if (existing != null) return@runWriteOnEdt Result.Err("$fileName already exists in $packageName")
            val psiFile = factory.createFileFromText(fileName, JavaFileType.INSTANCE, content)
            targetDir.add(psiFile)
            Result.Ok("Created $packageName/$fileName")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun findClass(filePath: String, className: String?): PsiClass? {
        val vf: VirtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: return null
        return if (className != null) psiFile.classes.firstOrNull { it.name == className }
        else psiFile.classes.firstOrNull()
    }

    private fun resolvePackageDirectory(packageName: String): com.intellij.psi.PsiDirectory? {
        // Prefer writable source roots
        val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        val pkgRelPath = packageName.replace('.', '/')
        for (root in sourceRoots) {
            val dir = root.findFileByRelativePath(pkgRelPath)
            if (dir != null && dir.isDirectory) {
                return PsiManager.getInstance(project).findDirectory(dir)
            }
        }
        // Fallback: find via JavaPsiFacade (covers multi-module projects)
        return JavaPsiFacade.getInstance(project).findPackage(packageName)?.directories?.firstOrNull()
    }

    private fun shortenAndReformat(element: com.intellij.psi.PsiElement) {
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(element)
        com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project).reformat(element)
    }

    private fun runWriteOnEdt(commandName: String, body: () -> Result): Result {
        val ref = arrayOfNulls<Result>(1)
        ApplicationManager.getApplication().invokeAndWait {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveAllDocuments()
            ref[0] = try {
                WriteCommandAction.writeCommandAction(project)
                    .withName(commandName)
                    .compute<Result, RuntimeException> { body() }
            } catch (t: Throwable) {
                Result.Err(t.message ?: t.javaClass.simpleName)
            }
        }
        return ref[0] ?: Result.Err("no result")
    }

    private fun String.ensureSemicolon() = if (trimEnd().endsWith(";")) this else "$this;"
}
