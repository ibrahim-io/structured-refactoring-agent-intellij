package com.example

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles POST /tools  with body { "tool": "<name>", "params": { ... } }
 *
 * Symbol lookup (no offset required):
 *   find_symbol_by_name  qualifiedName  e.g. "com.example.Foo#bar"
 *   list_symbols         filePath
 *
 * Refactor tools (by offset OR by qualifiedName):
 *   find_symbol          filePath, offset
 *   rename_symbol        (filePath+offset | qualifiedName), newName, [...]
 *   safe_delete          (filePath+offset | qualifiedName), [...]
 *
 * Creation tools:
 *   add_field            filePath, [className], fieldText
 *   add_method           filePath, [className], methodText
 *   add_inner_class      filePath, [className], innerClassText
 *   create_java_file     packageName, fileName, content
 */
class ToolsHandler(private val project: Project) : HttpHandler {

    override fun handle(exchange: HttpExchange) {
        cors(exchange)
        if (exchange.requestMethod == "OPTIONS") {
            exchange.sendResponseHeaders(204, -1)
            return
        }
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, """{"error":"method not allowed"}""")
            return
        }
        val body = exchange.requestBody.readBytes().decodeToString()
        val result = runCatching {
            val json = JSONObject(body)
            val tool = json.getString("tool")
            val params = json.optJSONObject("params") ?: JSONObject()
            dispatch(tool, params)
        }.getOrElse { e -> """{"error":${JSONObject.quote(e.message ?: e.javaClass.simpleName)}}""" }
        respond(exchange, 200, result)
    }

    private fun dispatch(tool: String, params: JSONObject): String {
        val refactor = project.service<RefactorService>()
        val create = project.service<PsiCreationService>()
        return when (tool) {
            // ── Symbol lookup (no offset needed) ─────────────────────────────
            "find_symbol_by_name" -> {
                val info = refactor.findSymbolByName(params.getString("qualifiedName"))
                if (info == null) """{"error":"symbol not found"}"""
                else info.toJson()
            }

            "list_symbols" -> {
                val symbols = refactor.listSymbols(params.getString("filePath"))
                JSONObject().put("symbols", JSONArray(symbols.map { it.toJsonObj() })).toString()
            }

            // ── Refactor tools ───────────────────────────────────────────────
            "find_symbol" -> {
                val info = refactor.findSymbolAtOffset(
                    filePath = params.getString("filePath"),
                    offset = params.getInt("offset"),
                )
                if (info == null) """{"error":"no symbol found"}"""
                else info.toJson()
            }

            "rename_symbol" -> {
                val qn = params.optString("qualifiedName").takeIf { it.isNotEmpty() }
                if (qn != null) {
                    refactor.renameByQualifiedName(
                        qualifiedName = qn,
                        newName = params.getString("newName"),
                        searchInComments = params.optBoolean("searchInComments", true),
                        searchTextOccurrences = params.optBoolean("searchTextOccurrences", true),
                    ).toJson()
                } else {
                    refactor.renameAtOffset(
                        filePath = params.getString("filePath"),
                        offset = params.getInt("offset"),
                        newName = params.getString("newName"),
                        searchInComments = params.optBoolean("searchInComments", true),
                        searchTextOccurrences = params.optBoolean("searchTextOccurrences", true),
                    ).toJson()
                }
            }

            "safe_delete" -> {
                val qn = params.optString("qualifiedName").takeIf { it.isNotEmpty() }
                if (qn != null) {
                    refactor.safeDeleteByQualifiedName(
                        qualifiedName = qn,
                        searchInCommentsAndStrings = params.optBoolean("searchInCommentsAndStrings", true),
                        searchNonJava = params.optBoolean("searchNonJava", true),
                    ).toJson()
                } else {
                    refactor.safeDeleteAtOffset(
                        filePath = params.getString("filePath"),
                        offset = params.getInt("offset"),
                        searchInCommentsAndStrings = params.optBoolean("searchInCommentsAndStrings", true),
                        searchNonJava = params.optBoolean("searchNonJava", true),
                    ).toJson()
                }
            }

            // ── Creation tools ───────────────────────────────────────────────
            "add_field" -> create.addField(
                filePath = params.getString("filePath"),
                className = params.optString("className").takeIf { it.isNotEmpty() },
                fieldText = params.getString("fieldText"),
            ).toJson()

            "add_method" -> create.addMethod(
                filePath = params.getString("filePath"),
                className = params.optString("className").takeIf { it.isNotEmpty() },
                methodText = params.getString("methodText"),
            ).toJson()

            "add_inner_class" -> create.addInnerClass(
                filePath = params.getString("filePath"),
                outerClassName = params.optString("className").takeIf { it.isNotEmpty() },
                innerClassText = params.getString("innerClassText"),
            ).toJson()

            "create_java_file" -> create.createJavaFile(
                packageName = params.getString("packageName"),
                fileName = params.getString("fileName"),
                content = params.getString("content"),
            ).toJson()

            // ── Structural refactor tools ────────────────────────────────────
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

            // ── Inline tools ─────────────────────────────────────────────────
            "inline_method" -> refactor.inlineMethod(
                qualifiedName  = params.getString("qualifiedName"),
                deleteOriginal = params.optBoolean("deleteOriginal", true),
            ).toJson()

            // ── Extract tools ────────────────────────────────────────────────
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

            // ── Read / find-usages tools ────────────────────────────────────
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

            // ── Kotlin creation tools ────────────────────────────────────────
            "add_kt_property" -> kotlinCreate(project)?.addProperty(
                filePath = params.getString("filePath"),
                className = params.optString("className").takeIf { it.isNotEmpty() },
                propertyText = params.getString("propertyText"),
            )?.toKtJson() ?: """{"error":"Kotlin plugin not available"}"""

            "add_kt_function" -> kotlinCreate(project)?.addFunction(
                filePath = params.getString("filePath"),
                className = params.optString("className").takeIf { it.isNotEmpty() },
                functionText = params.getString("functionText"),
            )?.toKtJson() ?: """{"error":"Kotlin plugin not available"}"""

            "create_kotlin_file" -> kotlinCreate(project)?.createKotlinFile(
                packageName = params.getString("packageName"),
                fileName = params.getString("fileName"),
                content = params.getString("content"),
            )?.toKtJson() ?: """{"error":"Kotlin plugin not available"}"""

            else -> """{"error":${JSONObject.quote("unknown tool: $tool")}}"""
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

private fun RefactorService.SymbolInfo.toJsonObj() = JSONObject()
    .put("name", name)
    .put("kind", kind)
    .put("filePath", filePath)
    .put("offset", offset)
    .put("signature", signature)

private fun RefactorService.SymbolInfo.toJson() = toJsonObj().toString()

private fun RefactorService.Result.toJson(): String = when (this) {
    is RefactorService.Result.Ok -> JSONObject().put("ok", true).put("message", message).toString()
    is RefactorService.Result.Err -> JSONObject().put("ok", false).put("error", message).toString()
}

private fun PsiCreationService.Result.toJson(): String = when (this) {
    is PsiCreationService.Result.Ok -> JSONObject().put("ok", true).put("message", message).toString()
    is PsiCreationService.Result.Err -> JSONObject().put("ok", false).put("error", message).toString()
}

private fun KotlinCreationService.Result.toKtJson(): String = when (this) {
    is KotlinCreationService.Result.Ok -> JSONObject().put("ok", true).put("message", message).toString()
    is KotlinCreationService.Result.Err -> JSONObject().put("ok", false).put("error", message).toString()
}

private fun kotlinCreate(project: com.intellij.openapi.project.Project): KotlinCreationService? =
    runCatching { project.getService(KotlinCreationService::class.java) }.getOrNull()
