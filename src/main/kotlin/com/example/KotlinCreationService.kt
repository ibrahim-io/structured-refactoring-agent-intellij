package com.example

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * PSI creation operations for Kotlin source files.
 * Only loaded when the Kotlin plugin is present (optional dependency).
 */
@Service(Service.Level.PROJECT)
class KotlinCreationService(private val project: Project) {

    sealed class Result {
        data class Ok(val message: String) : Result()
        data class Err(val message: String) : Result()
    }

    fun addProperty(filePath: String, className: String?, propertyText: String): Result {
        val ktClass = findKtClass(filePath, className)
            ?: return Result.Err("Class '${className ?: "<first>"}' not found in $filePath")
        val factory = KtPsiFactory(project)
        return runWriteOnEdt("Agent Add Kotlin Property") {
            val property = factory.createProperty(propertyText.trim())
            val body = ktClass.body ?: return@runWriteOnEdt Result.Err("Class has no body")
            val anchor = body.lBrace ?: return@runWriteOnEdt Result.Err("No open brace found")
            body.addAfter(property, anchor)
            Result.Ok("Added property to ${ktClass.name}")
        }
    }

    fun addFunction(filePath: String, className: String?, functionText: String): Result {
        val ktClass = findKtClass(filePath, className)
            ?: return Result.Err("Class '${className ?: "<first>"}' not found in $filePath")
        val factory = KtPsiFactory(project)
        return runWriteOnEdt("Agent Add Kotlin Function") {
            val fn = factory.createFunction(functionText.trim())
            val body = ktClass.body ?: return@runWriteOnEdt Result.Err("Class has no body")
            body.addBefore(fn, body.rBrace)
            Result.Ok("Added function to ${ktClass.name}")
        }
    }

    fun createKotlinFile(packageName: String, fileName: String, content: String): Result {
        if (!fileName.endsWith(".kt")) return Result.Err("fileName must end with .kt")
        val targetDir = resolvePackageDirectory(packageName)
            ?: return Result.Err("Package '$packageName' not found in any source root")
        val factory = com.intellij.psi.PsiFileFactory.getInstance(project)
        return runWriteOnEdt("Agent Create Kotlin File") {
            val existing = targetDir.findFile(fileName)
            if (existing != null) return@runWriteOnEdt Result.Err("$fileName already exists in $packageName")
            val psiFile = factory.createFileFromText(fileName, KotlinFileType.INSTANCE, content)
            targetDir.add(psiFile)
            Result.Ok("Created $packageName/$fileName")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun findKtClass(filePath: String, className: String?): KtClass? {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return null
        return if (className != null)
            ktFile.declarations.filterIsInstance<KtClass>().firstOrNull { it.name == className }
        else
            ktFile.declarations.filterIsInstance<KtClass>().firstOrNull()
    }

    private fun resolvePackageDirectory(packageName: String): com.intellij.psi.PsiDirectory? {
        val pkgRelPath = packageName.replace('.', '/')
        for (root in com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentSourceRoots) {
            val dir = root.findFileByRelativePath(pkgRelPath)
            if (dir != null && dir.isDirectory) {
                return PsiManager.getInstance(project).findDirectory(dir)
            }
        }
        return null
    }

    private inline fun runWriteOnEdt(commandName: String, crossinline body: () -> Result): Result {
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
}
