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

Requires Python 3.10+ and an Anthropic API key. Start IntelliJ with the plugin first.

```bash
pip install anthropic requests

# Set up benchmark projects (sample project is already committed;
# add --petclinic to also clone spring-petclinic 3.3.0)
python benchmarks/setup.py

# Open benchmarks/projects/sample-java-project in the sandbox IntelliJ instance,
# then run the sample-project benchmark suite
python benchmarks/run_benchmarks.py \
  --tasks benchmarks/tasks.json \
  --api-key $ANTHROPIC_API_KEY \
  --out results/run.json

# Or target spring-petclinic (open that project in IntelliJ first)
python benchmarks/run_benchmarks.py \
  --tasks benchmarks/tasks_petclinic.json \
  --api-key $ANTHROPIC_API_KEY \
  --model claude-opus-4-7 \
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
  tasks.json                   # 6 benchmark tasks targeting sample-java-project
  tasks_petclinic.json         # 5 benchmark tasks targeting spring-petclinic 3.3.0
  run_benchmarks.py            # Benchmark runner: drives Claude via API, records results
  setup.py                     # Project setup: verifies sample project, clones petclinic
  projects/
    sample-java-project/       # Minimal 4-class Maven project (committed)
    spring-petclinic/          # Cloned by setup.py --petclinic (gitignored)
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
