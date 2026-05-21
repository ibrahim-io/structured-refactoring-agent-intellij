# Structured Refactoring Agent for IntelliJ

An IntelliJ Platform plugin that exposes IntelliJ's **structured** (AST-aware) refactorings — rename, extract, inline, safe-delete, move, change-signature, add-member, create-file — both through caret-driven IDE actions and through a **localhost tool API** that an external LLM agent can call.

Everything goes through IntelliJ's refactoring engine (`RenameProcessor`, `MoveClassesOrPackagesProcessor`, `ChangeSignatureProcessor`, `SafeDeleteProcessor`, `PsiElementFactory`, `KtPsiFactory`, etc.), so usages, imports, comments, and string occurrences stay in sync. No text replacement.

**Imperial College London — MEng (AI & Machine Learning) Final Year Project, 2025–2026**

---

## Status

**18 tool-API operations. 9 Refactor-menu caret actions.**

### Tool API (POST /tools on localhost:6473)

| Tool | Description |
|---|---|
| `find_symbol_by_name` | Resolve symbol by qualified name — no offset needed |
| `list_symbols` | Enumerate all classes/fields/methods in a file |
| `find_symbol` | Locate symbol at byte offset |
| `rename_symbol` | AST-safe rename (by qualified name OR offset) |
| `safe_delete` | Delete only if no usages (by qualified name OR offset) |
| `move_class` | Move class to another package |
| `change_signature` | Rename, reorder, add, remove parameters |
| `add_field` | Add a Java field to an existing class |
| `add_method` | Add a Java method to an existing class |
| `add_inner_class` | Add an inner class/interface/enum |
| `create_java_file` | Create a new `.java` source file |
| `add_kt_property` | Add a Kotlin property (requires Kotlin plugin) |
| `add_kt_function` | Add a Kotlin function (requires Kotlin plugin) |
| `create_kotlin_file` | Create a new `.kt` source file (requires Kotlin plugin) |
| `extract_method` | Extract selected statements into a new named method (Java, no dialog) |
| `extract_variable` | Extract selected expression into a new local variable (Java) |
| `read_file` | Read a source file with line numbers (supports startLine/endLine range) |
| `find_usages` | Find all project-scope references to a symbol by qualified name |

### Caret actions (Refactor menu)

- Agent: Rename at Caret
- Agent: Extract Method (selection)
- Agent: Extract Variable (selection)
- Agent: Inline at Caret
- Agent: Safe Delete at Caret
- Agent: Add Member to Class at Caret
- Agent: Move Class to Package
- Agent: Change Signature at Caret

### In-IDE Chat Panel

An "Agent Refactor" tool window backed by Claude (Anthropic API). Project context — source roots, open files, and all symbols — is automatically injected into every API call so Claude can navigate the project without manual input.

---

## Build

Requires JDK 21.

```bash
./gradlew build
```

## Run in a sandbox IDE

```bash
./gradlew runIde
```

A second IntelliJ instance will launch with the plugin installed. The agent tool server starts automatically on `127.0.0.1:6473` when any project opens.

Verify it's running:
```bash
curl http://127.0.0.1:6473/status
curl http://127.0.0.1:6473/tools/schema
```

## Call a tool directly

```bash
curl -X POST http://127.0.0.1:6473/tools \
  -H 'Content-Type: application/json' \
  -d '{"tool":"find_symbol_by_name","params":{"qualifiedName":"com.example.MyClass#myMethod"}}'
```

## Run benchmarks

Requires Python 3.10+ and an Anthropic API key.

```bash
pip install anthropic requests
```

### Structured agent (requires IntelliJ running)

```powershell
# Reset project to baseline first
. benchmarks/reset_sample_project.ps1

# Open benchmarks/projects/sample-java-project in the sandbox IntelliJ instance, then:
python benchmarks/run_benchmarks.py `
  --tasks benchmarks/tasks.json `
  --api-key $env:ANTHROPIC_API_KEY `
  --projects-dir benchmarks/projects `
  --out results/structured-run.json
```

### Text-edit baseline (no IntelliJ needed)

The baseline agent uses only raw file I/O — no AST, no reference index. Run it standalone to get the comparison data for the thesis:

```powershell
. benchmarks/reset_sample_project.ps1
python benchmarks/run_benchmarks_text_edit.py `
  --tasks benchmarks/tasks.json `
  --api-key $env:ANTHROPIC_API_KEY `
  --projects-dir benchmarks/projects `
  --out results/text-edit-run.json
```

### Compare agents

```bash
python benchmarks/compare_results.py results/structured-run.json results/text-edit-run.json
```

### spring-petclinic (open that project in IntelliJ first)

```bash
python benchmarks/setup.py --petclinic
python benchmarks/run_benchmarks.py \
  --tasks benchmarks/tasks_petclinic.json \
  --api-key $ANTHROPIC_API_KEY \
  --out results/petclinic.json
```

---

## Project layout

```
src/main/kotlin/com/example/
  RefactorService.kt           # rename, safe-delete, move, change-sig, find-by-name, list-symbols
  PsiCreationService.kt        # add-field, add-method, add-inner-class, create-java-file (Java)
  KotlinCreationService.kt     # add-kt-property, add-kt-function, create-kotlin-file (Kotlin)
  ProjectContextProvider.kt    # builds system prompt from live project state
  AgentToolServer.kt           # starts HTTP server on port 6473
  ToolsHandler.kt              # POST /tools dispatch
  SchemaHandler.kt             # GET /tools/schema — Claude-compatible tool definitions
  StatusHandler.kt             # GET /status
  ClaudeClient.kt              # Anthropic Messages API client with tool-use loop
  AgentChatPanel.kt            # In-IDE LLM chat panel (Swing)
  AgentToolWindowFactory.kt    # Registers the "Agent Refactor" tool window
  ApiKeyService.kt             # Secure API key storage (IntelliJ PasswordSafe)
  Agent*Action.kt              # 9 Refactor-menu caret actions

docs/
  PROJECT_CONTEXT.md           # How this extends the state of the art; novelty claims
  RELATED_WORK.md              # Literature survey (12 papers across 6 areas)
  EVALUATION.md                # Evaluation plan, benchmark task spec, developer study design

benchmarks/
  tasks.json                        # 7 benchmark tasks (3 with cross-file dependencies)
  tasks_petclinic.json              # 5 tasks targeting spring-petclinic 3.3.0
  run_benchmarks.py                 # Structured agent runner: compile + content + RefactoringMiner validation
  run_benchmarks_text_edit.py       # TEXT-EDIT baseline: raw file I/O, no IntelliJ/AST
  compare_results.py                # Side-by-side structured vs text-edit comparison table
  setup.py                          # Verifies sample project, optionally clones petclinic
  reset_sample_project.ps1          # Restore baseline before each benchmark run
  projects/
    sample-java-project/            # 7-class Maven project with cross-file reference test cases
      ...OrderProcessor.java        # imports DateHelper (cross-file move test)
      ...NotificationController.java# calls Notifier.send (cross-file change-sig test)
      ...ServiceLayer.java          # calls LegacyHelper.parseNewFormat (cross-file rename test)
    spring-petclinic/               # Cloned by setup.py --petclinic (gitignored)
```

---

## Documentation

| Document | Contents |
|---|---|
| [docs/PROJECT_CONTEXT.md](docs/PROJECT_CONTEXT.md) | Architecture, novelty claims, EM-Assist comparison |
| [docs/RELATED_WORK.md](docs/RELATED_WORK.md) | Literature survey — 12 papers across 6 areas |
| [docs/EVALUATION.md](docs/EVALUATION.md) | Research questions, benchmark spec, developer study design |

---

## License

Not yet specified.
