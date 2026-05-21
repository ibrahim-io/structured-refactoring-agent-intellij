package com.example

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class AgentChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val history = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = JBColor.background()
        border = JBUI.Borders.empty(8)
    }
    private val input = JBTextField().apply {
        toolTipText = "Describe a refactoring… (Enter to send, Shift+Enter for newline)"
    }
    private val sendBtn = JButton("Send")
    private val settingsBtn = JButton("API Key…")

    private val conversation = mutableListOf<ClaudeClient.Message>()
    private val toolSchema = JSONArray(SchemaHandler.SCHEMA)

    init {
        val scroll = JBScrollPane(history)
        val inputRow = JPanel(BorderLayout(4, 0)).apply {
            add(input, BorderLayout.CENTER)
            add(sendBtn, BorderLayout.EAST)
        }
        val topBar = JPanel(BorderLayout()).apply {
            add(JLabel("  Agent Refactor Chat"), BorderLayout.WEST)
            add(settingsBtn, BorderLayout.EAST)
            border = JBUI.Borders.emptyBottom(4)
        }
        add(topBar, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(inputRow, BorderLayout.SOUTH)

        sendBtn.addActionListener { sendMessage() }
        settingsBtn.addActionListener { promptApiKey() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendMessage()
                }
            }
        })

        appendLine("Welcome to Agent Refactor Chat.")
        appendLine("Examples:")
        appendLine("  • \"Rename variable `foo` at offset 342 of /path/to/MyClass.java to `bar`\"")
        appendLine("  • \"Add a getName() method to UserService.java\"")
        appendLine("  • \"Create a new interface PaymentGateway in package com.example.payments\"")
        appendLine("Tools: find_symbol_by_name, list_symbols, find_symbol, rename_symbol, safe_delete,")
        appendLine("       add_field, add_method, add_inner_class, create_java_file, move_class,")
        appendLine("       change_signature, extract_method, extract_variable, read_file, find_usages\n")
    }

    private fun sendMessage() {
        val text = input.text.trim()
        if (text.isBlank()) return
        val apiKey = ApiKeyService.instance().getKey()
        if (apiKey.isNullOrBlank()) {
            promptApiKey()
            return
        }
        input.text = ""
        appendLine("You: $text\n")
        conversation.add(ClaudeClient.Message("user", text))
        setEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val systemPrompt = ReadAction.compute<String, RuntimeException> {
                    ProjectContextProvider.buildSystemPrompt(project)
                }
                val client = ClaudeClient(apiKey)
                client.chat(
                    messages = conversation,
                    toolSchema = toolSchema,
                    systemPrompt = systemPrompt,
                    toolExecutor = ::executeTool,
                    onUpdate = ::handleUpdate,
                )
            } catch (e: Exception) {
                appendOnEdt("\n[Error] ${e.message}\n")
            } finally {
                SwingUtilities.invokeLater { setEnabled(true) }
            }
        }
    }

    private fun executeTool(toolName: String, params: JSONObject): String {
        val refactor = project.service<RefactorService>()
        val create = project.service<PsiCreationService>()
        return when (toolName) {
            // ── Symbol lookup ─────────────────────────────────────────────
            "find_symbol_by_name" -> {
                val info = refactor.findSymbolByName(params.getString("qualifiedName"))
                if (info == null) """{"error":"symbol not found"}"""
                else JSONObject().put("name", info.name).put("kind", info.kind)
                    .put("filePath", info.filePath).put("offset", info.offset)
                    .put("signature", info.signature).toString()
            }
            "list_symbols" -> {
                val symbols = refactor.listSymbols(params.getString("filePath"))
                org.json.JSONObject().put("symbols",
                    org.json.JSONArray(symbols.map { s ->
                        JSONObject().put("name", s.name).put("kind", s.kind)
                            .put("offset", s.offset).put("signature", s.signature)
                    })
                ).toString()
            }
            // ── Refactor tools ────────────────────────────────────────────
            "find_symbol" -> {
                val info = refactor.findSymbolAtOffset(
                    params.getString("filePath"),
                    params.getInt("offset"),
                ) ?: return """{"error":"symbol not found"}"""
                JSONObject().put("name", info.name).put("kind", info.kind)
                    .put("filePath", info.filePath).put("offset", info.offset)
                    .put("signature", info.signature).toString()
            }
            "rename_symbol" -> {
                val qn = params.optString("qualifiedName").takeIf { it.isNotEmpty() }
                if (qn != null) refactor.renameByQualifiedName(qn, params.getString("newName"),
                    params.optBoolean("searchInComments", true),
                    params.optBoolean("searchTextOccurrences", true)).toJson()
                else refactor.renameAtOffset(params.getString("filePath"), params.getInt("offset"),
                    params.getString("newName"),
                    params.optBoolean("searchInComments", true),
                    params.optBoolean("searchTextOccurrences", true)).toJson()
            }
            "safe_delete" -> {
                val qn = params.optString("qualifiedName").takeIf { it.isNotEmpty() }
                if (qn != null) refactor.safeDeleteByQualifiedName(qn,
                    params.optBoolean("searchInCommentsAndStrings", true),
                    params.optBoolean("searchNonJava", true)).toJson()
                else refactor.safeDeleteAtOffset(params.getString("filePath"), params.getInt("offset"),
                    params.optBoolean("searchInCommentsAndStrings", true),
                    params.optBoolean("searchNonJava", true)).toJson()
            }
            // ── Creation tools ────────────────────────────────────────────
            "add_field" -> create.addField(
                filePath = params.getString("filePath"),
                className = params.optString("className").takeIf { it.isNotEmpty() },
                fieldText = params.getString("fieldText"),
            ).toCreationJson()
            "add_method" -> create.addMethod(
                filePath = params.getString("filePath"),
                className = params.optString("className").takeIf { it.isNotEmpty() },
                methodText = params.getString("methodText"),
            ).toCreationJson()
            "add_inner_class" -> create.addInnerClass(
                filePath = params.getString("filePath"),
                outerClassName = params.optString("className").takeIf { it.isNotEmpty() },
                innerClassText = params.getString("innerClassText"),
            ).toCreationJson()
            "create_java_file" -> create.createJavaFile(
                packageName = params.getString("packageName"),
                fileName = params.getString("fileName"),
                content = params.getString("content"),
            ).toCreationJson()
            // ── Structural refactor tools ─────────────────────────────────
            "move_class" -> refactor.moveClass(
                qualifiedClassName = params.getString("qualifiedClassName"),
                targetPackage = params.getString("targetPackage"),
            ).toJson()
            "change_signature" -> {
                val paramChanges = params.optJSONArray("parameterChanges")
                    ?.let { arr -> (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        obj.keySet().associateWith { key -> obj.getString(key) }
                    }} ?: emptyList()
                refactor.changeSignature(
                    qualifiedName = params.getString("qualifiedName"),
                    newMethodName = params.optString("newMethodName").takeIf { it.isNotEmpty() },
                    newReturnType = params.optString("newReturnType").takeIf { it.isNotEmpty() },
                    parameterChanges = paramChanges,
                ).toJson()
            }
            // ── Extract tools ─────────────────────────────────────────────
            "extract_method" -> refactor.extractMethod(
                filePath = params.getString("filePath"),
                startOffset = params.getInt("startOffset"),
                endOffset = params.getInt("endOffset"),
                newMethodName = params.getString("methodName"),
            ).toJson()
            "extract_variable" -> refactor.extractVariable(
                filePath = params.getString("filePath"),
                startOffset = params.getInt("startOffset"),
                endOffset = params.getInt("endOffset"),
                varName = params.getString("varName"),
            ).toJson()
            // ── Read / find-usages tools ──────────────────────────────────
            "read_file" -> {
                val content = refactor.readFile(
                    filePath = params.getString("filePath"),
                    startLine = params.optInt("startLine", 1),
                    endLine = params.optInt("endLine", Int.MAX_VALUE),
                )
                if (content == null) """{"error":"file not found"}"""
                else JSONObject().put("content", content).put("filePath", params.getString("filePath")).toString()
            }
            "find_usages" -> {
                val usages = refactor.findUsages(params.getString("qualifiedName"))
                JSONObject()
                    .put("usages", JSONArray(usages.map { u ->
                        JSONObject().put("filePath", u.filePath).put("line", u.line)
                            .put("preview", u.preview).put("kind", u.kind)
                    }))
                    .put("count", usages.size)
                    .toString()
            }
            // ── Kotlin creation tools ─────────────────────────────────────
            "add_kt_property" -> {
                val ktSvc = runCatching { project.getService(KotlinCreationService::class.java) }.getOrNull()
                ktSvc?.addProperty(
                    filePath = params.getString("filePath"),
                    className = params.optString("className").takeIf { it.isNotEmpty() },
                    propertyText = params.getString("propertyText"),
                )?.toKtCreationJson() ?: """{"error":"Kotlin plugin not available"}"""
            }
            "add_kt_function" -> {
                val ktSvc = runCatching { project.getService(KotlinCreationService::class.java) }.getOrNull()
                ktSvc?.addFunction(
                    filePath = params.getString("filePath"),
                    className = params.optString("className").takeIf { it.isNotEmpty() },
                    functionText = params.getString("functionText"),
                )?.toKtCreationJson() ?: """{"error":"Kotlin plugin not available"}"""
            }
            "create_kotlin_file" -> {
                val ktSvc = runCatching { project.getService(KotlinCreationService::class.java) }.getOrNull()
                ktSvc?.createKotlinFile(
                    packageName = params.getString("packageName"),
                    fileName = params.getString("fileName"),
                    content = params.getString("content"),
                )?.toKtCreationJson() ?: """{"error":"Kotlin plugin not available"}"""
            }
            else -> """{"error":"unknown tool $toolName"}"""
        }
    }

    private fun handleUpdate(update: ChatUpdate) {
        val line = when (update) {
            is ChatUpdate.AssistantText -> "\nClaude: ${update.text}\n"
            is ChatUpdate.ToolCall -> "  [→ calling ${update.name}(${update.params})]\n"
            is ChatUpdate.ToolResult -> "  [← ${update.result}]\n"
        }
        appendOnEdt(line)
    }

    private fun appendLine(text: String) {
        history.append(text + "\n")
        history.caretPosition = history.document.length
    }

    private fun appendOnEdt(text: String) {
        SwingUtilities.invokeLater {
            history.append(text)
            history.caretPosition = history.document.length
        }
    }

    private fun promptApiKey() {
        val current = ApiKeyService.instance().getKey() ?: ""
        val key = Messages.showInputDialog(
            project,
            "Enter your Anthropic API key (stored in system credential store):",
            "Anthropic API Key",
            Messages.getQuestionIcon(),
            current,
            null,
        ) ?: return
        if (key.isNotBlank()) ApiKeyService.instance().setKey(key)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        input.isEnabled = enabled
        sendBtn.isEnabled = enabled
    }
}

private fun RefactorService.Result.toJson() = when (this) {
    is RefactorService.Result.Ok -> JSONObject().put("ok", true).put("message", message).toString()
    is RefactorService.Result.Err -> JSONObject().put("ok", false).put("error", message).toString()
}

private fun PsiCreationService.Result.toCreationJson() = when (this) {
    is PsiCreationService.Result.Ok -> JSONObject().put("ok", true).put("message", message).toString()
    is PsiCreationService.Result.Err -> JSONObject().put("ok", false).put("error", message).toString()
}

private fun KotlinCreationService.Result.toKtCreationJson() = when (this) {
    is KotlinCreationService.Result.Ok -> JSONObject().put("ok", true).put("message", message).toString()
    is KotlinCreationService.Result.Err -> JSONObject().put("ok", false).put("error", message).toString()
}

