package com.example

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile

/**
 * Builds a system prompt that gives Claude enough project context to use the
 * tool API without requiring the user to manually supply file paths or offsets.
 */
object ProjectContextProvider {

    fun buildSystemPrompt(project: Project): String {
        val refactorSvc = project.service<RefactorService>()
        return buildString {
            appendLine(STATIC_INSTRUCTIONS)
            appendLine()
            appendLine("## Project context")
            appendLine("Project name: ${project.name}")

            // Source roots
            val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
            if (sourceRoots.isNotEmpty()) {
                appendLine("Source roots:")
                sourceRoots.forEach { appendLine("  - ${it.path}") }
            }

            // Open file(s)
            val openFiles = FileEditorManager.getInstance(project).openFiles
            if (openFiles.isNotEmpty()) {
                appendLine()
                appendLine("Currently open files:")
                for (vf in openFiles) {
                    appendLine("  - ${vf.path}")
                    // List top-level symbols from the open file
                    val symbols = ReadAction.compute<List<RefactorService.SymbolInfo>, RuntimeException> {
                        refactorSvc.listSymbols(vf.path)
                    }
                    if (symbols.isNotEmpty()) {
                        appendLine("    Symbols:")
                        symbols.take(30).forEach { s ->
                            appendLine("      ${s.kind} ${s.signature}  (offset ${s.offset})")
                        }
                        if (symbols.size > 30) appendLine("      … and ${symbols.size - 30} more")
                    }
                }
            }

            appendLine()
            appendLine("Tool port: ${AgentToolServer.PORT} (server running at 127.0.0.1:${AgentToolServer.PORT})")
        }
    }

    private val STATIC_INSTRUCTIONS = """
You are an expert software engineering assistant with access to IntelliJ's structured refactoring engine via a tool API.

## How to use the tools

**Always prefer `find_symbol_by_name` over `find_symbol`** when you know the class/method/field name.
Use the format `com.example.MyClass#memberName` for members, `com.example.MyClass` for classes.

**`list_symbols(filePath)`** — call this first when the user asks about a file you haven't seen yet.
It returns all declared symbols with their offsets and signatures.

**Workflow for rename/delete:**
1. Call `find_symbol_by_name` to confirm the symbol exists and get its offset.
2. Call `rename_symbol` or `safe_delete` using the `qualifiedName` parameter (no need for filePath/offset).

**Workflow for adding members:**
1. Call `list_symbols` on the target file to see what already exists.
2. Call `add_field`, `add_method`, `add_inner_class`, `add_kt_property`, or `add_kt_function`.

**Workflow for move/change-signature:**
1. Use `find_symbol_by_name` to confirm the class/method.
2. Call `move_class` (provide existing target package) or `change_signature` with the full parameter list.

**Important constraints:**
- File paths must be absolute. Use the paths shown in the project context below.
- Offsets are 0-based character offsets. Prefer using `qualifiedName` to avoid guessing offsets.
- The `create_java_file` and `create_kotlin_file` tools require the package directory to already exist.
- All operations are AST-safe — they update all usages, imports, and references automatically.
    """.trimIndent()
}
