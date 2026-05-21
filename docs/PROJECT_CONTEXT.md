# Project Context: How This Repository Extends the State of the Art

**Imperial College London — MEng (AI & Machine Learning) Final Year Project, 2025–2026**
**Author:** Ibrahim (ii83326@gmail.com)

---

## Problem

Large language models can reason about code and propose refactorings. But when they *apply* those refactorings by generating modified code text, they routinely:

- Miss usages in other files (scope blindness)
- Break import statements
- Introduce compilation errors
- Produce syntactically valid but semantically wrong results

The empirical ceiling for text-generation approaches on real-world Java refactoring is **41.6%** (SWE-Refactor, 2025). LLMs are good at *what* to refactor; they are unreliable at *how* when "how" means writing correct code text.

---

## The Gap in Prior Work

| Approach | Example Systems | LLM Role | Execution | Gap |
|---|---|---|---|---|
| LLM generates code text | SWE-agent, Aider, MANTRA | Proposes + writes code | Text diff applied to file | No correctness guarantee |
| Single-op IDE plugin | EM-Assist (extract method), Move Method tool | Proposes code range or target | IntelliJ PSI executes | One operation type; no external API |
| Read-only AST + text patch | AutoCodeRover | Context retrieval | Text patch | AST only used for search, not for write |

No prior system exposes a **multi-operation**, **externally callable**, **IDE-backed** refactoring API that decouples LLM reasoning from AST-safe execution.

---

## This Project's Contribution

The structured-refactoring-agent introduces:

### 1. Decoupled Architecture
The LLM decides *what* to do; IntelliJ decides *how* to do it safely.

```
External LLM Agent  (or in-IDE chat panel)
      │
      │  POST /tools  {"tool":"rename_symbol","params":{"qualifiedName":"com.example.Foo#bar","newName":"baz"}}
      ▼
 AgentToolServer (localhost:6473)
      │
      │  project.service<RefactorService>().renameByQualifiedName(...)
      ▼
 IntelliJ PSI / RenameProcessor
      │
      │  Updates all usages, imports, comments atomically across the project
      ▼
 Source files (correct-by-construction)
```

### 2. Complete Multi-Operation Tool API (18 tools)

All operations expose a Claude-compatible JSON schema at `GET /tools/schema`.

**Symbol lookup (new — solves the offset problem):**
| Tool | Operation | Why it matters |
|---|---|---|
| `find_symbol_by_name` | Resolve symbol by qualified name e.g. `com.example.Foo#bar` | Agents never need to guess byte offsets |
| `list_symbols` | Enumerate all classes/methods/fields in a file | Enables discovery before modification |

**Refactor (Java + Kotlin):**
| Tool | Operation | Correctness Basis |
|---|---|---|
| `find_symbol` | Locate element at byte offset | PSI symbol resolution |
| `rename_symbol` | Rename across all usages (by name OR offset) | `RenameProcessor` |
| `safe_delete` | Delete only if no usages (by name OR offset) | `SafeDeleteProcessor` |
| `move_class` | Move class to a different package | `MoveClassesOrPackagesProcessor` |
| `change_signature` | Rename, reorder, add, remove parameters | `ChangeSignatureProcessor` |

**Creation (Java):**
| Tool | Operation | Correctness Basis |
|---|---|---|
| `add_field` | Add field to existing class | `PsiElementFactory` + auto-format |
| `add_method` | Add method to existing class | `PsiElementFactory` + auto-format |
| `add_inner_class` | Add inner type (class/interface/enum) | `PsiElementFactory` + auto-format |
| `create_java_file` | Create new `.java` file in a package | `PsiFileFactory` |

**Creation (Kotlin — optional, loaded when Kotlin plugin present):**
| Tool | Operation | Correctness Basis |
|---|---|---|
| `add_kt_property` | Add property to Kotlin class | `KtPsiFactory` |
| `add_kt_function` | Add function to Kotlin class | `KtPsiFactory` |
| `create_kotlin_file` | Create new `.kt` file in a package | `PsiFileFactory` + `KotlinFileType` |

**Extract (Java):**
| Tool | Operation | Correctness Basis |
|---|---|---|
| `extract_method` | Extract statements in byte-offset range into a new named method | IntelliJ `MethodExtractor` (newImpl) — no editor or dialog required |
| `extract_variable` | Extract expression into a new local variable | PSI-level insertion + replacement via `PsiElementFactory` |

**Read / inspection (language-agnostic):**
| Tool | Operation | Why it matters |
|---|---|---|
| `read_file` | Read file content as numbered lines with optional range | Agent can inspect code before acting; `startLine`/`endLine` bound token usage |
| `find_usages` | Enumerate all project-scope references to a symbol | Agent understands blast radius before rename/delete; eliminates guesswork |

### 3. In-IDE LLM Chat Panel with Project Context Injection

An "Agent Refactor" tool window where the developer describes a refactoring in natural language. Claude uses tool calls to inspect and transform the codebase, with full multi-turn tool-use loop support.

**Project context injection (new — improvement over all prior work):**
On every chat turn, a `ProjectContextProvider` builds a system prompt containing:
- Project name and all source root paths
- The open file(s) with their full symbol listings (names, kinds, signatures, offsets)
- Tool usage instructions and qualified-name format guide
- Server port for external access

This means Claude can operate on a project it has never seen without the user needing to paste file paths or look up offsets manually.

### 4. Caret-Driven Actions (9 Refactor menu items)

- Rename at Caret
- Extract Method (selection)
- Extract Variable (selection)
- Inline at Caret
- Safe Delete at Caret
- Add Member to Class at Caret (field / method / inner class)
- Move Class to Package
- Change Signature at Caret (delegates to IntelliJ's dialog)

### 5. Benchmark Harness (`benchmarks/`)

A Python-based benchmark runner (`benchmarks/run_benchmarks.py`) that:
- Connects to the running IDE's tool server
- Drives Claude via the Anthropic API with the tool schema loaded
- Records all tool calls, results, and timing
- Validates correctness (tool call success, expected operations performed)

Task definitions in `benchmarks/tasks.json` cover rename, move, safe-delete, create-file, add-method, and change-signature.

---

## Novelty Claims (updated)

1. **First multi-operation externally callable refactoring API over a live IDE.** EM-Assist hardwires extract-method inside IntelliJ with no external API. No prior system publishes a localhost tool server backed by IntelliJ's PSI engine.

2. **Correct-by-construction execution.** By delegating all write operations to IntelliJ's refactoring engine, the output is correct with respect to the IDE's semantics model — independently of LLM output quality.

3. **Symbol resolution by qualified name eliminates the offset problem.** Prior work (AutoCodeRover, SWE-agent) that uses AST for reads still requires byte offsets or file positions for writes. `find_symbol_by_name` and `renameByQualifiedName` let the agent operate purely in the semantic domain of names.

4. **Creation + transformation in a single API.** Prior work focuses exclusively on transforming existing code. This project adds 7 creation tools so an agent can implement features from scratch, not just clean up existing ones.

5. **Kotlin + Java in one plugin.** The Kotlin creation tools (`add_kt_property`, `add_kt_function`, `create_kotlin_file`) extend the same architecture to Kotlin via `KtPsiFactory`, with no code duplication in the tool schema or HTTP surface.

6. **Automatic project context injection.** Unlike all prior systems (Copilot Chat, EM-Assist, SWE-agent) which require the user to supply context (file names, class names, offsets), this plugin injects a complete semantic snapshot of the open project into every Claude API call, making the agent immediately productive on unfamiliar codebases.

7. **Agent-agnostic surface.** The JSON tool schema follows the Claude API `tools` format but is not Claude-specific. Any LLM with function-calling support (GPT-4o, Gemini, etc.) can drive the same operations.

8. **Read-before-write inspection tools.** `read_file` and `find_usages` let the agent inspect code content and understand the full impact of a refactoring before issuing write commands. No prior IDE-backed system exposes both read and write operations through the same external API, enabling genuine read-plan-act loops without out-of-band file access.

9. **Headless extract-method without an editor.** Prior systems that expose extract-method (e.g. EM-Assist) are editor-bound — they require an open IntelliJ editor with a caret selection. This project uses IntelliJ's `newImpl.MethodExtractor.extractMethod(ExtractOptions)` API, which drives the extraction from a PSI file and text range without any UI interaction. This is the only known production plugin exposing extract-method over an HTTP API.

---

## Relation to EM-Assist (Closest Prior Work)

EM-Assist (FSE 2024) is the most architecturally similar prior system:

| | EM-Assist | This project |
|---|---|---|
| IDE | IntelliJ IDEA | IntelliJ IDEA |
| Execution engine | IntelliJ PSI | IntelliJ PSI |
| Operations | Extract Method only | 18 tools (rename, delete, move, change-sig, add-field, add-method, create-file, Kotlin equivalents, extract-method, extract-variable, read-file, find-usages) |
| Languages | Java | Java + Kotlin |
| LLM integration | Internal only | Internal (chat panel) + External (HTTP tool server) |
| Agent API | None | `POST /tools` — callable by any external LLM agent |
| Symbol resolution | Not applicable | By qualified name (`com.example.Foo#bar`) — no offset required |
| Project context | Not applicable | Auto-injected into every API call |
| Workflow | Single-step | Multi-step agentic loop |
| Benchmark harness | Evaluated post-hoc against mined corpus | Ships with benchmark runner (`benchmarks/`) |

---

## Evaluation Plan

See [EVALUATION.md](EVALUATION.md) for the full research questions, benchmark task definitions, runner script, and developer study design.

---

## References

See [RELATED_WORK.md](RELATED_WORK.md) for full citations and per-paper analysis.
