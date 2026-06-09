package com.example

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler

/**
 * GET /tools/schema — returns the JSON tool schema for this server.
 * Shaped to be directly usable as Claude API tool definitions.
 */
class SchemaHandler : HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val bytes = SCHEMA.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        val SCHEMA = """
        [
          {
            "name": "find_symbol_by_name",
            "description": "Resolve a symbol by its qualified name without needing a file offset. Preferred over find_symbol when you know the class/member name. Returns the file path and offset needed for refactoring tools. Format: 'com.example.MyClass' for a class, 'com.example.MyClass#fieldName' for a field, 'com.example.MyClass#methodName' or 'com.example.MyClass#methodName(int,String)' for a method.",
            "input_schema": {
              "type": "object",
              "properties": {
                "qualifiedName": { "type": "string", "description": "Qualified name of the symbol, e.g. 'com.example.UserService#getName' or 'com.example.UserService'." }
              },
              "required": ["qualifiedName"]
            }
          },
          {
            "name": "list_symbols",
            "description": "List all named symbols (classes, fields, methods, inner types) declared in a source file. Use this to discover what exists in a file before adding or modifying members.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath": { "type": "string", "description": "Absolute path to the source file." }
              },
              "required": ["filePath"]
            }
          },
          {
            "name": "find_symbol",
            "description": "Locate the PSI symbol at a given file offset and return its name, kind, and canonical offset. Prefer find_symbol_by_name when you know the qualified name.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath": { "type": "string",  "description": "Absolute path to the source file." },
                "offset":   { "type": "integer", "description": "Character offset in the file (0-based)." }
              },
              "required": ["filePath", "offset"]
            }
          },
          {
            "name": "rename_symbol",
            "description": "AST-aware rename of a symbol. Updates all usages, imports, and optionally comments/string occurrences across the project. Provide EITHER qualifiedName OR (filePath + offset).",
            "input_schema": {
              "type": "object",
              "properties": {
                "qualifiedName":         { "type": "string",  "description": "Qualified name, e.g. 'com.example.Foo#bar'. If provided, filePath/offset are not needed." },
                "filePath":              { "type": "string",  "description": "Absolute path to the source file (alternative to qualifiedName)." },
                "offset":                { "type": "integer", "description": "Character offset of the symbol (alternative to qualifiedName)." },
                "newName":               { "type": "string",  "description": "The replacement identifier." },
                "searchInComments":      { "type": "boolean", "description": "Also rename occurrences in comments. Default true." },
                "searchTextOccurrences": { "type": "boolean", "description": "Also rename occurrences in string literals. Default true." }
              },
              "required": ["newName"]
            }
          },
          {
            "name": "safe_delete",
            "description": "Delete a symbol only after verifying it has no usages. Aborts if usages exist. Provide EITHER qualifiedName OR (filePath + offset).",
            "input_schema": {
              "type": "object",
              "properties": {
                "qualifiedName":              { "type": "string",  "description": "Qualified name, e.g. 'com.example.Foo#bar'. If provided, filePath/offset are not needed." },
                "filePath":                   { "type": "string",  "description": "Absolute path to the source file (alternative to qualifiedName)." },
                "offset":                     { "type": "integer", "description": "Character offset of the symbol (alternative to qualifiedName)." },
                "searchInCommentsAndStrings": { "type": "boolean", "description": "Check comment/string occurrences too. Default true." },
                "searchNonJava":              { "type": "boolean", "description": "Check non-Java/Kotlin files. Default true." }
              },
              "required": []
            }
          },
          {
            "name": "add_field",
            "description": "Add a field to an existing Java class. The field is formatted and imports are shortened automatically.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":  { "type": "string", "description": "Absolute path to the Java source file." },
                "className": { "type": "string", "description": "Name of the class to add the field to. Omit to use the first class in the file." },
                "fieldText": { "type": "string", "description": "Full field declaration, e.g. \"private final List<String> names = new ArrayList<>();\"" }
              },
              "required": ["filePath", "fieldText"]
            }
          },
          {
            "name": "add_method",
            "description": "Add a method to an existing Java class. The method is formatted and imports are shortened automatically.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":   { "type": "string", "description": "Absolute path to the Java source file." },
                "className":  { "type": "string", "description": "Name of the class. Omit to use the first class in the file." },
                "methodText": { "type": "string", "description": "Full method declaration including body, e.g. \"public String getName() { return this.name; }\"" }
              },
              "required": ["filePath", "methodText"]
            }
          },
          {
            "name": "add_inner_class",
            "description": "Add an inner class, inner interface, or inner enum to an existing Java class.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":       { "type": "string", "description": "Absolute path to the Java source file." },
                "className":      { "type": "string", "description": "Name of the outer class. Omit to use the first class in the file." },
                "innerClassText": { "type": "string", "description": "Full inner type declaration, e.g. \"public interface Callback { void onDone(String result); }\"" }
              },
              "required": ["filePath", "innerClassText"]
            }
          },
          {
            "name": "create_java_file",
            "description": "Create a new Java source file (class, interface, enum, or annotation type) in a given package. The package directory must already exist in the project's source roots.",
            "input_schema": {
              "type": "object",
              "properties": {
                "packageName": { "type": "string", "description": "Dot-separated package name, e.g. \"com.example.service\"." },
                "fileName":    { "type": "string", "description": "File name ending in .java, e.g. \"UserService.java\"." },
                "content":     { "type": "string", "description": "Full file content including package statement and class/interface/enum body." }
              },
              "required": ["packageName", "fileName", "content"]
            }
          },
          {
            "name": "move_class",
            "description": "Move a top-level Java class to a different package. Updates all import statements and references across the project. The target package directory must already exist.",
            "input_schema": {
              "type": "object",
              "properties": {
                "qualifiedClassName": { "type": "string", "description": "Fully qualified class name, e.g. 'com.example.old.MyService'." },
                "targetPackage":      { "type": "string", "description": "Target package, e.g. 'com.example.service'." }
              },
              "required": ["qualifiedClassName", "targetPackage"]
            }
          },
          {
            "name": "change_signature",
            "description": "Change the signature of a Java method: rename it, change its return type, and/or reorder/add/remove parameters. All call sites are updated.",
            "input_schema": {
              "type": "object",
              "properties": {
                "qualifiedName":   { "type": "string",  "description": "Qualified name of the method, e.g. 'com.example.Foo#doThing' or 'com.example.Foo#doThing(int,String)'." },
                "newMethodName":   { "type": "string",  "description": "New method name. Omit to keep current name." },
                "newReturnType":   { "type": "string",  "description": "New return type text, e.g. 'void', 'String', 'java.util.List<Integer>'. Omit to keep current." },
                "parameterChanges": {
                  "type": "array",
                  "description": "New ordered parameter list. Each item maps old parameters (by oldIndex) or introduces new ones (oldIndex=-1 or absent).",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name":         { "type": "string",  "description": "Parameter name." },
                      "type":         { "type": "string",  "description": "Parameter type text." },
                      "oldIndex":     { "type": "string",  "description": "0-based index of the original parameter. Omit or use -1 for new parameters." },
                      "defaultValue": { "type": "string",  "description": "Default value expression added at existing call sites for new parameters." }
                    },
                    "required": ["name", "type"]
                  }
                }
              },
              "required": ["qualifiedName"]
            }
          },
          {
            "name": "pull_up_member",
            "description": "Pull a member (method or field) UP from its declaring class to a superclass, using IntelliJ's PullUpProcessor. A high-level design refactoring made correct-by-construction: the member is moved, and overrides, references, and required imports are handled across the type hierarchy. Defaults to the direct superclass when targetSuperClass is omitted.",
            "input_schema": {
              "type": "object",
              "properties": {
                "memberQualifiedName": { "type": "string", "description": "Qualified name of the member to pull up, e.g. 'com.example.Dog#makeSound' or 'com.example.Dog#legs'." },
                "targetSuperClass":    { "type": "string", "description": "Fully qualified name of the destination superclass. Omit to use the direct (non-Object) superclass." }
              },
              "required": ["memberQualifiedName"]
            }
          },
          {
            "name": "push_down_member",
            "description": "Push a member (method or field) DOWN from its declaring class into its subclasses, using IntelliJ's PushDownProcessor. The inverse of pull_up_member: the member is copied into each subclass and removed from the parent, with references handled across the hierarchy.",
            "input_schema": {
              "type": "object",
              "properties": {
                "memberQualifiedName": { "type": "string", "description": "Qualified name of the member to push down, e.g. 'com.example.Animal#makeSound'." }
              },
              "required": ["memberQualifiedName"]
            }
          },
          {
            "name": "inline_method",
            "description": "Inline a Java method: replace every call site with the method body (with correct parameter substitution) and optionally delete the original declaration. Uses IntelliJ's InlineMethodProcessor, which handles edge cases such as parameter shadowing, complex expressions, and multiple return paths that text-edit approaches cannot do reliably.",
            "input_schema": {
              "type": "object",
              "properties": {
                "qualifiedName":  { "type": "string",  "description": "Qualified name of the method to inline, e.g. 'com.example.Foo#helperMethod'." },
                "deleteOriginal": { "type": "boolean", "description": "If true (default), delete the original method declaration after inlining all calls." }
              },
              "required": ["qualifiedName"]
            }
          },
          {
            "name": "add_kt_property",
            "description": "Add a property to an existing Kotlin class or object. Requires the Kotlin plugin.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":      { "type": "string", "description": "Absolute path to the .kt source file." },
                "className":     { "type": "string", "description": "Name of the class. Omit to use the first class in the file." },
                "propertyText":  { "type": "string", "description": "Full property declaration, e.g. \"var count: Int = 0\" or \"val name: String\"." }
              },
              "required": ["filePath", "propertyText"]
            }
          },
          {
            "name": "add_kt_function",
            "description": "Add a function to an existing Kotlin class or object. Requires the Kotlin plugin.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":      { "type": "string", "description": "Absolute path to the .kt source file." },
                "className":     { "type": "string", "description": "Name of the class. Omit to use the first class in the file." },
                "functionText":  { "type": "string", "description": "Full function declaration, e.g. fun greet(name: String): String." }
              },
              "required": ["filePath", "functionText"]
            }
          },
          {
            "name": "create_kotlin_file",
            "description": "Create a new Kotlin source file (.kt) in a given package. Requires the Kotlin plugin.",
            "input_schema": {
              "type": "object",
              "properties": {
                "packageName": { "type": "string", "description": "Dot-separated package name." },
                "fileName":    { "type": "string", "description": "File name ending in .kt, e.g. \"UserService.kt\"." },
                "content":     { "type": "string", "description": "Full file content including package statement and declarations." }
              },
              "required": ["packageName", "fileName", "content"]
            }
          },
          {
            "name": "extract_method",
            "description": "Extract statements in a byte-offset range into a new named method in the same class. Uses IntelliJ's refactoring engine — the method signature and return type are inferred automatically. Provide the absolute byte offsets of the first and last characters of the code to extract.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":    { "type": "string",  "description": "Absolute path to the Java source file." },
                "startOffset": { "type": "integer", "description": "Byte offset of the first character of the selection (0-based, inclusive)." },
                "endOffset":   { "type": "integer", "description": "Byte offset just past the last character of the selection (exclusive)." },
                "methodName":  { "type": "string",  "description": "Name for the new extracted method." }
              },
              "required": ["filePath", "startOffset", "endOffset", "methodName"]
            }
          },
          {
            "name": "extract_variable",
            "description": "Extract an expression in a byte-offset range into a new local variable. Inserts a declaration immediately before the enclosing statement and replaces the expression with the variable name. Works on Java files only.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":    { "type": "string",  "description": "Absolute path to the Java source file." },
                "startOffset": { "type": "integer", "description": "Byte offset of the first character of the expression (0-based, inclusive)." },
                "endOffset":   { "type": "integer", "description": "Byte offset just past the last character of the expression (exclusive)." },
                "varName":     { "type": "string",  "description": "Name for the new local variable." }
              },
              "required": ["filePath", "startOffset", "endOffset", "varName"]
            }
          },
          {
            "name": "read_file",
            "description": "Read the content of a source file with 1-based line numbers. Use startLine and endLine to read a specific range and avoid loading entire large files. Returns lines in the format '1: <line text>'.",
            "input_schema": {
              "type": "object",
              "properties": {
                "filePath":  { "type": "string",  "description": "Absolute path to the file." },
                "startLine": { "type": "integer", "description": "First line to include (1-based, default 1)." },
                "endLine":   { "type": "integer", "description": "Last line to include (1-based, inclusive). Omit to read to end of file." }
              },
              "required": ["filePath"]
            }
          },
          {
            "name": "find_usages",
            "description": "Find all project-scope references to a symbol by qualified name. Returns each usage with its file path, 1-based line number, and the surrounding line of code. Use this before safe_delete or rename_symbol to understand impact.",
            "input_schema": {
              "type": "object",
              "properties": {
                "qualifiedName": { "type": "string", "description": "Qualified name of the symbol, e.g. 'com.example.Foo#bar' or 'com.example.Foo#doThing(int)'." }
              },
              "required": ["qualifiedName"]
            }
          }
        ]
        """.trimIndent()
    }
}
