package com.example

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiVariable
import com.intellij.codeInsight.CodeInsightUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline
import com.intellij.refactoring.extractMethod.newImpl.ExtractSelector
import com.intellij.refactoring.extractMethod.newImpl.MethodExtractor
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.JavaThrownExceptionInfo
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.refactoring.util.CanonicalTypes
import com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor
import com.intellij.refactoring.move.moveClassesOrPackages.SingleSourceRootMoveDestination
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.memberPushDown.PushDownProcessor
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiField
import com.intellij.psi.PsiType
import com.intellij.refactoring.makeStatic.MakeMethodStaticProcessor
import com.intellij.refactoring.makeStatic.Settings
import com.intellij.refactoring.inline.InlineConstantFieldProcessor
import com.intellij.refactoring.typeMigration.TypeMigrationProcessor
import com.intellij.refactoring.typeMigration.TypeMigrationRules
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Programmatic, dialog-free entry points for structured refactorings.
 * Used both by the in-IDE caret actions and the agent tool surface.
 */
@Service(Service.Level.PROJECT)
class RefactorService(private val project: Project) {

    data class SymbolInfo(
        val name: String,
        val kind: String,
        val filePath: String,
        val offset: Int,
        val signature: String = "",
    )

    data class UsageInfo(
        val filePath: String,
        val line: Int,
        val preview: String,
        val kind: String = "",
    )

    sealed class Result {
        data class Ok(val message: String) : Result()
        data class Err(val message: String) : Result()
    }

    // ── Symbol lookup ────────────────────────────────────────────────────────

    fun findSymbolAtOffset(filePath: String, offset: Int): SymbolInfo? =
        ReadAction.compute<SymbolInfo?, RuntimeException> {
            val named = locateNamedElement(filePath, offset) ?: return@compute null
            named.toSymbolInfo()
        }

    /**
     * Resolve a symbol by qualified name. Supports:
     *   com.example.MyClass                  — top-level class
     *   com.example.MyClass#myField          — field
     *   com.example.MyClass#myMethod         — first matching method
     *   com.example.MyClass#myMethod(int)    — method with parameter types
     */
    fun findSymbolByName(qualifiedName: String): SymbolInfo? {
        DumbService.getInstance(project).waitForSmartMode()
        return ReadAction.compute<SymbolInfo?, RuntimeException> {
            resolveQualifiedName(qualifiedName)?.toSymbolInfo()
        }
    }

    /**
     * List all named top-level symbols in a file (classes, their fields and methods).
     */
    fun listSymbols(filePath: String): List<SymbolInfo> =
        ReadAction.compute<List<SymbolInfo>, RuntimeException> {
            val vf: VirtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return@compute emptyList()
            val psiFile: PsiFile = PsiManager.getInstance(project).findFile(vf)
                ?: return@compute emptyList()
            val result = mutableListOf<SymbolInfo>()
            when (psiFile) {
                is PsiJavaFile -> {
                    for (cls in psiFile.classes) {
                        result.add(cls.toSymbolInfo())
                        cls.fields.mapTo(result) { it.toSymbolInfo() }
                        cls.methods.mapTo(result) { it.toSymbolInfo() }
                        cls.innerClasses.mapTo(result) { it.toSymbolInfo() }
                    }
                }
                is KtFile -> {
                    for (decl in psiFile.declarations) {
                        when (decl) {
                            is KtClass -> {
                                result.add(decl.toSymbolInfo())
                                decl.getProperties().mapTo(result) { it.toSymbolInfo() }
                                decl.declarations.filterIsInstance<KtFunction>().mapTo(result) { it.toSymbolInfo() }
                            }
                            is KtFunction -> result.add(decl.toSymbolInfo())
                            is KtProperty -> result.add(decl.toSymbolInfo())
                            is PsiNamedElement -> result.add(decl.toSymbolInfo())
                        }
                    }
                }
                else -> {
                    PsiTreeUtil.collectElementsOfType(psiFile, PsiNamedElement::class.java)
                        .filter { it.containingFile == psiFile }
                        .mapTo(result) { it.toSymbolInfo() }
                }
            }
            result
        }

    /**
     * Read a file's content as numbered lines.
     * [startLine] and [endLine] are 1-based and inclusive.
     */
    fun readFile(filePath: String, startLine: Int = 1, endLine: Int = Int.MAX_VALUE): String? =
        ReadAction.compute<String?, RuntimeException> {
            val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@compute null
            val doc = FileDocumentManager.getInstance().getDocument(vf)
            if (doc != null) {
                val from = (startLine - 1).coerceAtLeast(0)
                val to = (endLine - 1).coerceAtMost(doc.lineCount - 1)
                if (from > to) return@compute ""
                (from..to).joinToString("\n") { line ->
                    val text = doc.getText(TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)))
                    "${line + 1}: $text"
                }
            } else {
                val lines = String(vf.contentsToByteArray(), Charsets.UTF_8).lines()
                val from = (startLine - 1).coerceAtLeast(0)
                val to = (endLine - 1).coerceAtMost(lines.size - 1)
                if (from > to) return@compute ""
                lines.subList(from, to + 1).mapIndexed { i, line -> "${from + i + 1}: $line" }.joinToString("\n")
            }
        }

    /**
     * Find all project-scope usages of a symbol by qualified name.
     */
    fun findUsages(qualifiedName: String): List<UsageInfo> {
        DumbService.getInstance(project).waitForSmartMode()
        return ReadAction.compute<List<UsageInfo>, RuntimeException> {
            val element = resolveQualifiedName(qualifiedName) ?: return@compute emptyList()
            val scope = GlobalSearchScope.projectScope(project)
            val indexedUsages = ReferencesSearch.search(element, scope).findAll().mapNotNull { ref ->
                val vf = ref.element.containingFile?.virtualFile ?: return@mapNotNull null
                val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@mapNotNull null
                val absOffset = ref.element.textOffset + ref.rangeInElement.startOffset
                val lineIdx = doc.getLineNumber(absOffset)
                val preview = doc.getText(
                    TextRange(doc.getLineStartOffset(lineIdx), doc.getLineEndOffset(lineIdx))
                ).trim()
                UsageInfo(
                    filePath = vf.path,
                    line = lineIdx + 1,
                    preview = preview,
                    kind = ref.element.javaClass.simpleName,
                )
            }
            if (indexedUsages.isNotEmpty()) return@compute indexedUsages
            findTextualUsagesFallback(element)
        }
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    fun rename(
        element: PsiNamedElement,
        newName: String,
        searchInComments: Boolean = true,
        searchTextOccurrences: Boolean = true,
    ): Result {
        if (newName.isBlank()) return Result.Err("newName must not be blank")
        val oldName = ReadAction.compute<String, RuntimeException> { element.name ?: "<unnamed>" }
        // RenameProcessor handles cross-file reference updates and, critically, renames
        // the containing .java file when a public class is renamed — the manual approach
        // (setName + handleElementRename) does not trigger the file rename.
        return runProcessorOnEdt {
            // Headless: HeadlessRenameProcessor suppresses BOTH the usage preview pane
            // and the modal conflicts dialog (either would block the EDT forever in an
            // unattended IDE and wedge every subsequent operation), recording any
            // conflicts instead of prompting. See HeadlessRenameProcessor.java.
            val processor = HeadlessRenameProcessor(project, element, newName, searchInComments, searchTextOccurrences)
            processor.run()
            Result.Ok("renamed \"$oldName\" → \"$newName\"")
        }
    }

    fun renameAtOffset(
        filePath: String,
        offset: Int,
        newName: String,
        searchInComments: Boolean = true,
        searchTextOccurrences: Boolean = true,
    ): Result {
        val named = ReadAction.compute<PsiNamedElement?, RuntimeException> {
            locateNamedElement(filePath, offset)
        } ?: return Result.Err("no renamable symbol at $filePath:$offset")
        return rename(named, newName, searchInComments, searchTextOccurrences)
    }

    fun renameByQualifiedName(
        qualifiedName: String,
        newName: String,
        searchInComments: Boolean = true,
        searchTextOccurrences: Boolean = true,
    ): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val element = ReadAction.compute<PsiNamedElement?, RuntimeException> {
            resolveQualifiedName(qualifiedName) as? PsiNamedElement
        } ?: return Result.Err("could not resolve \"$qualifiedName\"")
        return rename(element, newName, searchInComments, searchTextOccurrences)
    }

    // ── Safe delete ──────────────────────────────────────────────────────────

    fun safeDelete(
        element: PsiElement,
        searchInCommentsAndStrings: Boolean = true,
        searchNonJava: Boolean = true,
    ): Result {
        val displayName = ReadAction.compute<String, RuntimeException> {
            (element as? PsiNamedElement)?.name ?: element.text?.take(40) ?: "<unknown>"
        }
        return runWriteOnEdt("Agent Safe Delete") {
            element.delete()
            Result.Ok("safe-deleted \"$displayName\"")
        }
    }

    fun safeDeleteAtOffset(
        filePath: String,
        offset: Int,
        searchInCommentsAndStrings: Boolean = true,
        searchNonJava: Boolean = true,
    ): Result {
        val element = ReadAction.compute<PsiElement?, RuntimeException> {
            locateNamedElement(filePath, offset)
        } ?: return Result.Err("no deletable symbol at $filePath:$offset")
        return safeDelete(element, searchInCommentsAndStrings, searchNonJava)
    }

    fun safeDeleteByQualifiedName(
        qualifiedName: String,
        searchInCommentsAndStrings: Boolean = true,
        searchNonJava: Boolean = true,
    ): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val element = ReadAction.compute<PsiElement?, RuntimeException> {
            resolveQualifiedName(qualifiedName)
        } ?: return Result.Err("could not resolve \"$qualifiedName\"")
        return safeDelete(element, searchInCommentsAndStrings, searchNonJava)
    }

    // ── Move ─────────────────────────────────────────────────────────────────

    /**
     * Move a top-level class to a different package.
     * [targetPackage] must exist as a directory in the project's source roots.
     */
    fun moveClass(qualifiedClassName: String, targetPackage: String): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val facade = JavaPsiFacade.getInstance(project)
        val cls = ReadAction.compute<PsiClass?, RuntimeException> {
            resolveClassByQualifiedName(qualifiedClassName)
        } ?: return Result.Err("Class '$qualifiedClassName' not found")

        val targetDir = ReadAction.compute<com.intellij.psi.PsiDirectory?, RuntimeException> {
            facade.findPackage(targetPackage)?.directories?.firstOrNull()
        } ?: return Result.Err("Package '$targetPackage' not found in source roots")

        return runProcessorOnEdt {
            val destination = SingleSourceRootMoveDestination(
                com.intellij.refactoring.PackageWrapper(
                    PsiManager.getInstance(project), targetPackage
                ),
                targetDir
            )
            // Headless safety: search code only (see rename()) so no modal preview
            // pane can block the EDT in an unattended IDE.
            val processor = MoveClassesOrPackagesProcessor(
                project,
                arrayOf(cls),
                destination,
                /* searchInComments = */ false,
                /* searchInNonJava = */ false,
                /* moveCallback = */ null,
            )
            processor.setPreviewUsages(false)
            // Dialogs are suppressed via -Dide.performance.skip.refactoring.dialogs; a conflict
            // then throws ConflictsInTestsException instead of blocking. Proceed past it.
            BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { processor.run() }
            Result.Ok("Moved '$qualifiedClassName' to package '$targetPackage'")
        }
    }

    // ── Change Signature ─────────────────────────────────────────────────────

    /**
     * Change the signature of a method.
     *
     * [parameterChanges] is a list of maps with keys:
     *   - name         : new parameter name
     *   - type         : parameter type text, e.g. "int" or "java.util.List<String>"
     *   - defaultValue : (optional) default value expression for new parameters at call sites
     *   - oldIndex     : (optional) 0-based index of the original parameter (-1 for new parameters)
     */
    fun changeSignature(
        qualifiedName: String,
        newMethodName: String? = null,
        newReturnType: String? = null,
        parameterChanges: List<Map<String, String>> = emptyList(),
    ): Result {
        refreshProjectFromDisk()
        val method = ReadAction.compute<PsiMethod?, RuntimeException> {
            val element = resolveQualifiedName(qualifiedName)
            element as? PsiMethod
        } ?: return Result.Err("'$qualifiedName' did not resolve to a method")

        val factory = JavaPsiFacade.getElementFactory(project)

        val newParams: Array<ParameterInfoImpl> = if (parameterChanges.isEmpty()) {
            ReadAction.compute<Array<ParameterInfoImpl>, RuntimeException> {
                method.parameterList.parameters.mapIndexed { i, p ->
                    ParameterInfoImpl(i, p.name ?: "p$i", p.type)
                }.toTypedArray()
            }
        } else {
            ReadAction.compute<Array<ParameterInfoImpl>, RuntimeException> {
                parameterChanges.mapIndexed { _, change ->
                    val oldIndex = change["oldIndex"]?.toIntOrNull() ?: -1
                    val name = change["name"] ?: "param"
                    val typeText = change["type"] ?: "Object"
                    val defaultValue = change["defaultValue"] ?: ""
                    val type = factory.createTypeFromText(typeText, method)
                    ParameterInfoImpl(oldIndex, name, type, defaultValue)
                }.toTypedArray()
            }
        }

        val resolvedReturnType = if (newReturnType != null) {
            ReadAction.compute<com.intellij.psi.PsiType, RuntimeException> {
                factory.createTypeFromText(newReturnType, method)
            }
        } else {
            ReadAction.compute<com.intellij.psi.PsiType, RuntimeException> {
                method.returnType ?: com.intellij.psi.PsiPrimitiveType.VOID
            }
        }

        val finalName = newMethodName ?: ReadAction.compute<String, RuntimeException> {
            method.name
        }

        val visibility = ReadAction.compute<String, RuntimeException> {
            com.intellij.psi.util.PsiUtil.getAccessModifier(
                com.intellij.psi.util.PsiUtil.getAccessLevel(method.modifierList)
            )
        }

        return runProcessorOnEdt {
            // Headless safety: preview pane OFF (see rename()) so no modal dialog
            // can block the EDT in an unattended IDE.
            val processor = ChangeSignatureProcessor(
                project,
                method,
                /* generateDelegate = */ false,
                visibility,
                finalName,
                CanonicalTypes.createTypeWrapper(resolvedReturnType),
                newParams,
                /* thrownExceptions = */ emptyArray<JavaThrownExceptionInfo>(),
                /* propagateParametersMethods = */ emptySet(),
                /* propagateExceptionsMethods = */ emptySet(),
            )
            processor.setPreviewUsages(false)
            // See moveClass(): proceed past suppressed-and-thrown conflicts.
            BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { processor.run() }
            Result.Ok("Changed signature of '${method.name}'")
        }
    }

    // ── Pull Up / Push Down member (high-level design refactorings) ────────────

    /**
     * Pull a member (method or field) UP from its declaring class to a superclass.
     * [targetSuperClass] defaults to the direct (non-Object) superclass. This is one
     * of the high-level design refactorings that text-editing agents typically avoid,
     * here made correct-by-construction via IntelliJ's PullUpProcessor.
     */
    fun pullUpMember(memberQualifiedName: String, targetSuperClass: String? = null): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val member = ReadAction.compute<PsiMember?, RuntimeException> {
            resolveQualifiedName(memberQualifiedName) as? PsiMember
        } ?: return Result.Err("could not resolve member \"$memberQualifiedName\"")
        val sourceClass = ReadAction.compute<PsiClass?, RuntimeException> { member.containingClass }
            ?: return Result.Err("member \"$memberQualifiedName\" has no containing class")
        val superClass = ReadAction.compute<PsiClass?, RuntimeException> {
            if (targetSuperClass != null) resolveClassByQualifiedName(targetSuperClass)
            else sourceClass.superClass?.takeIf { it.qualifiedName != "java.lang.Object" }
        } ?: return Result.Err("no target superclass (pass targetSuperClass, or ensure a non-Object superclass exists)")
        val (memberName, superName) = ReadAction.compute<Pair<String, String>, RuntimeException> {
            ((member as? PsiNamedElement)?.name ?: "<member>") to (superClass.qualifiedName ?: superClass.name ?: "?")
        }
        return runProcessorOnEdt {
            val info = MemberInfo(member).apply { isChecked = true }
            val processor = PullUpProcessor(sourceClass, superClass, arrayOf(info), DocCommentPolicy(DocCommentPolicy.ASIS))
            processor.setPreviewUsages(false)
            BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { processor.run() }
            Result.Ok("pulled up '$memberName' to '$superName'")
        }
    }

    /**
     * Push a member (method or field) DOWN from its declaring class to its subclasses.
     */
    fun pushDownMember(memberQualifiedName: String): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val member = ReadAction.compute<PsiMember?, RuntimeException> {
            resolveQualifiedName(memberQualifiedName) as? PsiMember
        } ?: return Result.Err("could not resolve member \"$memberQualifiedName\"")
        val sourceClass = ReadAction.compute<PsiClass?, RuntimeException> { member.containingClass }
            ?: return Result.Err("member \"$memberQualifiedName\" has no containing class")
        val (memberName, sourceName) = ReadAction.compute<Pair<String, String>, RuntimeException> {
            ((member as? PsiNamedElement)?.name ?: "<member>") to (sourceClass.qualifiedName ?: sourceClass.name ?: "?")
        }
        return runProcessorOnEdt {
            val info = MemberInfo(member).apply { isChecked = true }
            val processor = PushDownProcessor(sourceClass, listOf(info), DocCommentPolicy(DocCommentPolicy.ASIS))
            processor.setPreviewUsages(false)
            BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { processor.run() }
            Result.Ok("pushed down '$memberName' from '$sourceName'")
        }
    }

    // ── Make Static / Inline Field / Type Migration ───────────────────────────

    /** Make an instance method static (settings: replace usages, no captured fields). */
    fun makeMethodStatic(methodQualifiedName: String): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val method = ReadAction.compute<PsiMethod?, RuntimeException> {
            resolveQualifiedName(methodQualifiedName) as? PsiMethod
        } ?: return Result.Err("could not resolve method \"$methodQualifiedName\"")
        val name = ReadAction.compute<String, RuntimeException> { method.name }
        return runProcessorOnEdt {
            val settings = Settings(true, null, null)
            val processor = MakeMethodStaticProcessor(project, method, settings)
            processor.setPreviewUsages(false)
            BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { processor.run() }
            Result.Ok("made method '$name' static")
        }
    }

    /** Inline a constant (static final) field: replace every reference with its value and delete it. */
    fun inlineField(fieldQualifiedName: String): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val field = ReadAction.compute<PsiField?, RuntimeException> {
            resolveQualifiedName(fieldQualifiedName) as? PsiField
        } ?: return Result.Err("could not resolve field \"$fieldQualifiedName\"")
        val name = ReadAction.compute<String, RuntimeException> { field.name ?: "<field>" }
        return runProcessorOnEdt {
            val processor = InlineConstantFieldProcessor(field, project, null, false)
            processor.setPreviewUsages(false)
            BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { processor.run() }
            Result.Ok("inlined constant field '$name'")
        }
    }

    /**
     * Change the type of a field/variable/method (by qualified name) to [newTypeText] and
     * propagate the change through all usages via IntelliJ's TypeMigration engine. No text
     * approach can do this safely.
     */
    fun migrateType(elementQualifiedName: String, newTypeText: String): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()
        val element = ReadAction.compute<PsiElement?, RuntimeException> {
            resolveQualifiedName(elementQualifiedName)
        } ?: return Result.Err("could not resolve \"$elementQualifiedName\"")
        val newType = ReadAction.compute<PsiType?, RuntimeException> {
            runCatching { JavaPsiFacade.getElementFactory(project).createTypeFromText(newTypeText, element) }.getOrNull()
        } ?: return Result.Err("could not parse type \"$newTypeText\"")
        return runProcessorOnEdt {
            val rules = TypeMigrationRules(project)
            val processor = TypeMigrationProcessor(
                project, arrayOf(element),
                com.intellij.util.Functions.constant(newType), rules, true,
            )
            processor.setPreviewUsages(false)
            BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<RuntimeException> { processor.run() }
            Result.Ok("migrated type of '$elementQualifiedName' to '$newTypeText'")
        }
    }

    // ── Inline Method ────────────────────────────────────────────────────────

    /**
     * Inline a method: replace every call site with the method body (with parameter
     * substitution) and optionally delete the original declaration.
     *
     * Supports single-return methods. Uses direct PSI manipulation via
     * WriteCommandAction — avoids BaseRefactoringProcessor.run() which defers
     * work via invokeLater and silently no-ops in non-interactive mode.
     */
    fun inlineMethod(qualifiedName: String, deleteOriginal: Boolean = true): Result {
        refreshProjectFromDisk()
        DumbService.getInstance(project).waitForSmartMode()

        // Collect everything we need in a single read pass before any mutations.
        data class CallSite(val call: PsiMethodCallExpression, val argTexts: List<String>)
        data class InlineInfo(
            val method: PsiMethod,
            val methodName: String,
            val paramNames: List<String>,
            val bodyExprText: String,
            val callSites: List<CallSite>,
        )

        val info = ReadAction.compute<InlineInfo?, RuntimeException> {
            val method = resolveQualifiedName(qualifiedName) as? PsiMethod
                ?: return@compute null
            val params = method.parameterList.parameters
            val body = method.body ?: return@compute null
            val stmts = body.statements
            if (stmts.size != 1) return@compute null
            val retExpr = (stmts[0] as? PsiReturnStatement)?.returnValue ?: return@compute null
            val paramNames = params.map { it.name ?: "" }
            val bodyExprText = retExpr.text

            val scope = GlobalSearchScope.projectScope(project)
            val sites = ReferencesSearch.search(method, scope).findAll().mapNotNull { ref ->
                var el: PsiElement? = ref.element.parent
                while (el != null && el !is PsiMethodCallExpression) el = el.parent
                val call = el as? PsiMethodCallExpression ?: return@mapNotNull null
                val argTexts = call.argumentList.expressions.map { it.text }
                if (argTexts.size != paramNames.size) return@mapNotNull null
                CallSite(call, argTexts)
            }
            InlineInfo(method, method.name, paramNames, bodyExprText, sites)
        } ?: return Result.Err("'$qualifiedName' did not resolve to a single-return method")

        return runWriteOnEdt("Agent Inline Method") {
            val factory = JavaPsiFacade.getElementFactory(project)
            for (site in info.callSites) {
                var exprText = info.bodyExprText
                for ((i, paramName) in info.paramNames.withIndex()) {
                    val argText = site.argTexts[i]
                    // Wrap complex args in parens to preserve operator precedence
                    val safe = if (argText.matches(Regex("[a-zA-Z_\$][a-zA-Z0-9_\$]*"))) argText else "($argText)"
                    exprText = exprText.replace(Regex("\\b${Regex.escape(paramName)}\\b"), safe)
                }
                val newExpr = factory.createExpressionFromText(exprText, site.call)
                site.call.replace(newExpr)
            }
            if (deleteOriginal) info.method.delete()
            Result.Ok("Inlined method '${info.methodName}' at ${info.callSites.size} call site(s)${if (deleteOriginal) " and deleted original" else ""}")
        }
    }

    // ── Extract Method ───────────────────────────────────────────────────────

    /**
     * Extract statements in [startOffset, endOffset) into a new method named [newMethodName].
     * Uses IntelliJ's newImpl MethodExtractor — no editor or dialog required.
     */
    fun extractMethod(filePath: String, startOffset: Int, endOffset: Int, newMethodName: String): Result {
        refreshProjectFromDisk()
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return Result.Err("File not found: $filePath")

        val range = TextRange(startOffset, endOffset)
        val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            PsiManager.getInstance(project).findFile(vf)
        } ?: return Result.Err("Could not parse file: $filePath")

        val options = ReadAction.compute<List<com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions>, RuntimeException> {
            val elements = ExtractSelector().suggestElementsToExtract(psiFile, range)
            if (elements.isEmpty()) return@compute emptyList()
            ExtractMethodPipeline.findAllOptionsToExtract(elements)
        }
        if (options.isEmpty()) return Result.Err("No extractable code found in [$startOffset, $endOffset)")

        val targetOption = options.first().copy(methodName = newMethodName)
        return runProcessorOnEdt {
            MethodExtractor().extractMethod(targetOption)
            Result.Ok("Extracted method '$newMethodName'")
        }
    }

    // ── Extract Variable ─────────────────────────────────────────────────────

    /**
     * Extract the expression spanning [startOffset, endOffset) into a new local variable [varName].
     * Inserts a declaration before the enclosing statement and replaces the expression with the name.
     */
    fun extractVariable(filePath: String, startOffset: Int, endOffset: Int, varName: String): Result {
        refreshProjectFromDisk()
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return Result.Err("File not found: $filePath")

        val psiFile = ReadAction.compute<PsiFile?, RuntimeException> {
            PsiManager.getInstance(project).findFile(vf)
        } ?: return Result.Err("Could not parse file: $filePath")

        data class ExprData(val text: String, val typeName: String)
        val exprData = ReadAction.compute<ExprData?, RuntimeException> {
            val expr = CodeInsightUtil.findExpressionInRange(psiFile, startOffset, endOffset)
                ?: return@compute null
            ExprData(expr.text, expr.type?.presentableText ?: "Object")
        } ?: return Result.Err("No expression found in range [$startOffset, $endOffset)")

        return runWriteOnEdt("Agent Extract Variable") {
            val freshFile = PsiManager.getInstance(project).findFile(vf)
                ?: return@runWriteOnEdt Result.Err("Could not re-parse file")
            val expr = CodeInsightUtil.findExpressionInRange(freshFile, startOffset, endOffset)
                ?: return@runWriteOnEdt Result.Err("Expression no longer found after document commit")
            val stmt = PsiTreeUtil.getParentOfType(expr, PsiStatement::class.java)
                ?: return@runWriteOnEdt Result.Err("Expression is not inside a statement")
            val factory = JavaPsiFacade.getElementFactory(project)
            val decl = factory.createStatementFromText("${exprData.typeName} $varName = ${exprData.text};", stmt)
            stmt.parent.addBefore(decl, stmt)
            val ref = factory.createExpressionFromText(varName, expr)
            expr.replace(ref)
            Result.Ok("Extracted variable '$varName: ${exprData.typeName}'")
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Resolves a qualified name like:
     *   com.example.Foo              → PsiClass
     *   com.example.Foo#bar          → PsiField named bar
     *   com.example.Foo#doThing      → first PsiMethod named doThing
     *   com.example.Foo#doThing(int,String) → PsiMethod matching parameter types
     */
    private fun resolveQualifiedName(qualifiedName: String): PsiElement? {
        if (!qualifiedName.contains('#')) {
            return resolveClassByQualifiedName(qualifiedName)
        }

        val (className, memberPart) = qualifiedName.split('#', limit = 2)
        val cls = resolveClassByQualifiedName(className) ?: return null

        // Parse optional parameter list: methodName(type1,type2)
        val parenIdx = memberPart.indexOf('(')
        return if (parenIdx < 0) {
            // No parens — try field first, then first method with that name
            cls.findFieldByName(memberPart, true)
                ?: cls.findMethodsByName(memberPart, true).firstOrNull()
        } else {
            val methodName = memberPart.substring(0, parenIdx)
            val paramTypes = memberPart.substring(parenIdx + 1, memberPart.lastIndexOf(')'))
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            cls.findMethodsByName(methodName, true).firstOrNull { method ->
                val params = method.parameterList.parameters
                params.size == paramTypes.size &&
                    params.indices.all { i ->
                        params[i].type.presentableText == paramTypes[i] ||
                            params[i].type.canonicalText == paramTypes[i]
                    }
            } ?: cls.findMethodsByName(methodName, true).firstOrNull()
        }
    }

    /**
     * Resolve a Java class through IntelliJ's index first, then by deriving the
     * source path from the qualified name. The fallback keeps the tool API useful
     * while a Maven/Gradle project is still importing or when source roots are not
     * configured yet, which is common in benchmark sandboxes.
     */
    private fun resolveClassByQualifiedName(qualifiedName: String): PsiClass? {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        facade.findClass(qualifiedName, scope)?.let { return it }

        val relativePath = qualifiedName.replace('.', '/') + ".java"
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.toMutableList()
        project.baseDir?.let { baseDir ->
            roots.add(baseDir)
            baseDir.findFileByRelativePath("src/main/java")?.let { roots.add(it) }
            baseDir.findFileByRelativePath("src/test/java")?.let { roots.add(it) }
        }

        val psiManager = PsiManager.getInstance(project)
        for (root in roots.distinctBy { it.path }) {
            val vf = root.findFileByRelativePath(relativePath) ?: continue
            val javaFile = psiManager.findFile(vf) as? PsiJavaFile ?: continue
            val candidate = javaFile.classes.firstOrNull { it.qualifiedName == qualifiedName }
                ?: javaFile.classes.firstOrNull { it.name == qualifiedName.substringAfterLast('.') }
            // Only return an element the refactoring engine will accept as in-project.
            // A path-derived file that is not under a content/source root has
            // PsiManager.isInProject()==false, which makes RenameProcessor/etc. abort with the
            // modal "Cannot perform refactoring: ... not located inside the project" dialog —
            // a precondition error gated on isUnitTestMode that the dialog-suppression property
            // does NOT cover. Returning null here yields a clean "not found" instead of a hang.
            if (candidate != null && psiManager.isInProject(candidate)) return candidate
        }
        return null
    }

    private fun findTextualUsagesFallback(element: PsiElement): List<UsageInfo> {
        val name = (element as? PsiNamedElement)?.name ?: return emptyList()
        val pattern = Regex("""\b${Regex.escape(name)}\b""")
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.toMutableList()
        project.baseDir?.let { baseDir ->
            roots.add(baseDir)
            baseDir.findFileByRelativePath("src/main/java")?.let { roots.add(it) }
            baseDir.findFileByRelativePath("src/test/java")?.let { roots.add(it) }
        }
        val result = mutableListOf<UsageInfo>()
        for (root in roots.distinctBy { it.path }) {
            collectJavaFiles(root).forEach { vf ->
                val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@forEach
                for (lineIdx in 0 until doc.lineCount) {
                    val line = doc.getText(TextRange(doc.getLineStartOffset(lineIdx), doc.getLineEndOffset(lineIdx)))
                    if (pattern.containsMatchIn(line)) {
                        result.add(
                            UsageInfo(
                                filePath = vf.path,
                                line = lineIdx + 1,
                                preview = line.trim(),
                                kind = "textual-fallback",
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun collectJavaFiles(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        fun visit(file: VirtualFile) {
            if (file.isDirectory) {
                file.children.forEach { visit(it) }
            } else if (file.extension == "java") {
                result.add(file)
            }
        }
        visit(root)
        return result
    }

    /** Force-sync all project source roots from disk before reading or
     * mutating PSI. Resolves "file changed externally" conflicts that would
     * otherwise cause saveAllDocuments() to silently fail. Required after
     * external file changes (e.g. git reset in benchmark runs). */
    private fun refreshProjectFromDisk() {
        ApplicationManager.getApplication().invokeAndWait {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in roots) {
                root.refresh(false, true)
            }
        }
    }

    private fun locateNamedElement(filePath: String, offset: Int): PsiNamedElement? {
        val vf: VirtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val psiFile: PsiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        if (offset < 0 || offset > psiFile.textLength) return null
        val raw = psiFile.findElementAt(offset) ?: return null
        var cur: PsiElement? = raw
        while (cur != null) {
            if (cur is PsiNamedElement) return cur
            cur = cur.parent
        }
        return null
    }

    private fun PsiElement.toSymbolInfo(): SymbolInfo {
        val name = (this as? PsiNamedElement)?.name ?: "<unnamed>"
        val kind = when (this) {
            is KtClass -> if (isInterface()) "interface" else if (isEnum()) "enum" else "class"
            is KtFunction -> "function"
            is KtProperty -> "property"
            is PsiClass -> if (isInterface) "interface" else if (isEnum) "enum" else "class"
            is PsiMethod -> "method"
            is PsiVariable -> "field"
            else -> this.javaClass.simpleName
        }
        val sig = when (this) {
            is KtFunction -> buildString {
                append(name)
                append('(')
                append(valueParameters.joinToString(", ") {
                    "${it.name}: ${it.typeReference?.text ?: "?"}"
                })
                append(')')
                typeReference?.let { append(": ${it.text}") }
            }
            is KtClass -> fqName?.asString() ?: name
            is KtProperty -> "$name: ${typeReference?.text ?: "?"}"
            is PsiMethod -> buildString {
                append(name)
                append('(')
                append(parameterList.parameters.joinToString(", ") { it.type.presentableText })
                append(')')
                returnType?.let { append(": ${it.presentableText}") }
            }
            is PsiClass -> qualifiedName ?: name
            else -> name
        }
        return SymbolInfo(
            name = name,
            kind = kind,
            filePath = containingFile?.virtualFile?.path ?: "",
            offset = textOffset,
            signature = sig,
        )
    }

    /**
     * Run a BaseRefactoringProcessor on the EDT, then flush changes to disk.
     *
     * DumbService.waitForSmartMode() is called first so the project is fully indexed.
     * When smart mode is active, smartInvokeLater(NON_MODAL) fires synchronously within
     * the same EDT event, so the rename/delete is complete before run() returns.
     * invokeLater(NON_MODAL) + a latch ensures the save still queues correctly even if
     * the processor posts any additional NON_MODAL events.
     */
    private inline fun runProcessorOnEdt(crossinline body: () -> Result): Result {
        DumbService.getInstance(project).waitForSmartMode()
        val ref = arrayOfNulls<Result>(1)
        ApplicationManager.getApplication().invokeAndWait {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            ref[0] = try {
                body()
            } catch (t: Throwable) {
                Result.Err(t.message ?: t.javaClass.simpleName)
            }
        }
        val latch = CountDownLatch(1)
        ApplicationManager.getApplication().invokeLater({
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveAllDocuments()
            latch.countDown()
        }, ModalityState.NON_MODAL)
        latch.await(30, TimeUnit.SECONDS)
        return ref[0] ?: Result.Err("no result")
    }

    private inline fun runWriteOnEdt(commandName: String, crossinline body: () -> Result): Result {
        val ref = arrayOfNulls<Result>(1)
        ApplicationManager.getApplication().invokeAndWait {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            ref[0] = try {
                WriteCommandAction.writeCommandAction(project)
                    .withName(commandName)
                    .compute<Result, RuntimeException> { body() }
            } catch (t: Throwable) {
                Result.Err(t.message ?: t.javaClass.simpleName)
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveAllDocuments()
        }
        return ref[0] ?: Result.Err("no result")
    }
}
