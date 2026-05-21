# Evaluation Plan & Benchmark Harness

**Imperial College London — MEng (AI & Machine Learning) Final Year Project**

---

## 1. Research Questions

| RQ | Question |
|---|---|
| RQ1 | Does the structured-refactoring-agent produce semantically correct refactorings (correct-by-construction), compared to a text-substitution baseline? |
| RQ2 | Can a Claude agent driven by the tool API complete real-world multi-step refactoring tasks without manual intervention? |
| RQ3 | How accurately does the agent resolve symbols by qualified name (without being told file paths or offsets)? |
| RQ4 | How does developer-perceived effort compare between manual IntelliJ, Copilot Chat, and the structured-refactoring-agent for maintenance tasks? |

---

## 2. Benchmark Projects

### 2a. Sample Java Project (`benchmarks/projects/sample-java-project/`)

A Maven project (Java 17) with seven classes, designed to expose structural advantages of AST-aware refactoring over text-edit approaches. Every task in `benchmarks/tasks.json` targets this project.

| Class | Qualified name | Role |
|---|---|---|
| `User.java` | `com.example.User` | Has poorly-named field `usrNm`; rename + add-method targets |
| `LegacyHelper.java` | `com.example.LegacyHelper` | Has unused method `parseOldFormat` (safe-delete) and `parseNewFormat` (cross-file rename target) |
| `Notifier.java` | `com.example.Notifier` | Has method `send(String)`; change-signature target |
| `utils/DateHelper.java` | `com.example.utils.DateHelper` | move-class target |
| `OrderProcessor.java` | `com.example.OrderProcessor` | **Cross-file dependency**: imports `com.example.utils.DateHelper`. After move-001, the structured agent's `MoveClassesOrPackagesProcessor` updates this import automatically. A text-edit agent leaves it stale → compile failure. |
| `NotificationController.java` | `com.example.NotificationController` | **Cross-file caller**: calls `notifier.send(message)` with one argument. After change-sig-001 adds `int priority`, `ChangeSignatureProcessor` updates this call to `send(message, 0)`. A text-edit agent edits `Notifier.java` but leaves this call broken → compile failure. |
| `ServiceLayer.java` | `com.example.ServiceLayer` | **Cross-file caller**: calls `LegacyHelper.parseNewFormat(raw)`. After rename-method-002, the structured agent's `ReferencesSearch` finds and renames this call site. A text-edit agent that edits only `LegacyHelper.java` leaves this call broken → compile failure. |

### 2b. spring-petclinic (`benchmarks/tasks_petclinic.json`)

A real-world Spring Boot application cloned at tag `3.3.0` via `benchmarks/setup.py --petclinic`. Used to validate that the agent can navigate unfamiliar, medium-sized codebases (~30 classes, Spring annotations, layered architecture).

Tasks cover: rename attribute, add method, find_usages inspection, read_file inspection, rename class.

---

## 3. Benchmark Tasks (`benchmarks/tasks.json`)

Eight tasks spanning seven refactoring operation types:

| Task ID | Operation | Cross-file effect |
|---|---|---|
| `rename-001` | Rename field `User.usrNm` → `username` | Within single file (field is `private`) |
| `rename-method-002` | Rename method `LegacyHelper.parseNewFormat` → `parseInput` | **Cross-file**: `ServiceLayer.java` calls the old name |
| `move-001` | Move `com.example.utils.DateHelper` → `com.example.common` | **Cross-file**: `OrderProcessor.java` and `ServiceLayer.java` import old package |
| `safe-delete-001` | Delete unused method `LegacyHelper.parseOldFormat` | Symbol removed from codebase |
| `inline-001` | Inline `LegacyHelper.normalize` with parameter substitution | **Structural**: `ServiceLayer.normalizeInput` calls it; requires correct param substitution |
| `create-class-001` | Create interface `com.example.payments.PaymentGateway` | New file on disk |
| `add-method-001` | Add `User.getDisplayName()` | Symbol added to existing class |
| `change-sig-001` | Add `int priority` to `Notifier.send` signature | **Cross-file**: `NotificationController.java` calls old signature |

Three cross-file tasks (`rename-method-002`, `move-001`, `change-sig-001`) and one structurally complex task (`inline-001`) are the **key differentiators**: the structured agent handles them via IntelliJ's refactoring APIs; the text-edit baseline is expected to fail or produce compilation errors.

---

## 4. Multi-Layer Validation (`run_benchmarks.py`)

Each task now validates at four layers:

### Layer 1: Tool-call layer
- All mutating tools (`rename_symbol`, `move_class`, etc.) returned `ok: true`
- All expected tools were invoked

### Layer 2: Compile layer
- `mvn compile` succeeds after the refactoring
- **This is the primary correctness signal**: a text-edit agent that misses cross-file references causes compilation failures here

### Layer 3: Disk-content layer
Depending on `validation.type`:
| Type | Check |
|---|---|
| `compile_and_file_exists` | Expected file exists on disk |
| `compile_and_no_reference` | `deletedSymbol` absent from all `.java` files |
| `compile_and_symbol_exists` | `expectedSymbol` present in `.java` files |
| `compile_and_symbol_exists` + `crossFileCheck` | Old symbol name absent from ALL files (catches cross-file miss) |

### Layer 4: RefactoringMiner layer (optional)
Runs [RefactoringMiner](https://github.com/tsantalis/RefactoringMiner) between two git commits to classify the refactoring type (e.g., "Rename Attribute", "Move Class", "Add Parameter"). Requires `--rm-jar` argument.

This provides independent academic classification of the operation — RefactoringMiner is a peer-reviewed tool, making its output publishable evidence.

### Running the benchmark
```powershell
# Reset project to baseline first
. benchmarks/reset_sample_project.ps1

# Run structured agent (requires IntelliJ + plugin running)
python benchmarks/run_benchmarks.py `
    --tasks benchmarks/tasks.json `
    --api-key $env:ANTHROPIC_API_KEY `
    --projects-dir benchmarks/projects `
    --rm-jar tools/RefactoringMiner.jar `
    --out results/structured-$(Get-Date -f yyyyMMdd-HHmmss).json
```

---

## 5. Text-Edit Baseline Agent (`run_benchmarks_text_edit.py`)

A second benchmark runner that drives Claude using **only raw file I/O**:

| Tool | Description |
|---|---|
| `list_java_files` | List all `.java` files under the project |
| `read_file` | Read file contents as plain text |
| `write_file` | Overwrite a file with new content |
| `create_file` | Create a new file |
| `delete_file` | Delete a file |

No IntelliJ. No AST. No reference index. The agent sees the same task descriptions and must figure out which files to edit by reading their text content.

This is the **apples-to-apples comparison** for RQ1. Both agents are driven by the same `claude-sonnet-4-6` model on the same tasks. The only difference is the tool API they have access to.

```powershell
# Text-edit agent runs standalone (no IntelliJ needed)
# Reset project first (the text-edit agent modifies disk directly)
. benchmarks/reset_sample_project.ps1

python benchmarks/run_benchmarks_text_edit.py `
    --tasks benchmarks/tasks.json `
    --api-key $env:ANTHROPIC_API_KEY `
    --projects-dir benchmarks/projects `
    --out results/text-edit-$(Get-Date -f yyyyMMdd-HHmmss).json
```

---

## 6. Comparison (`compare_results.py`)

```powershell
python benchmarks/compare_results.py results/structured-run.json results/text-edit-run.json
```

Outputs a side-by-side table:
```
====================================================================
Task ID              Structured    Text-Edit    Advantage
====================================================================
  rename-001              PASS          PASS
  rename-method-002       PASS          FAIL     <-- structured wins
  move-001                PASS          FAIL     <-- structured wins
  safe-delete-001         PASS          PASS
  create-class-001        PASS          PASS
  add-method-001          PASS          PASS
  change-sig-001          PASS          FAIL     <-- structured wins
====================================================================
  TOTAL                   7/7           4/7
====================================================================
```

Expected hypothesis: structured agent wins on all four structurally complex tasks (`rename-method-002`, `move-001`, `inline-001`, `change-sig-001`). Text-edit agent succeeds on single-file operations but fails on cross-file/structural tasks.

---

## 7. Metrics

| Metric | How measured |
|---|---|
| **Correctness** | `ok: true` from tool call + project compiles after operation |
| **Cross-file correctness** | Old symbol absent from all files after rename/move |
| **RefactoringMiner match** | Run RefactoringMiner on git diff; check expected type detected |
| **Task completion rate** | % of benchmark tasks where all expected tools were called and all returned `ok: true` |
| **Compile pass rate** | % of tasks where project compiles after agent's changes |
| **Turns to completion** | Number of API round-trips per task |
| **Cross-file advantage** | Pass rate delta between structured and text-edit agents on cross-file tasks |

---

## 8. Expected Results (Hypothesis)

| Agent | Single-file tasks | Structural/cross-file tasks | Overall |
|---|---|---|---|
| Structured (IntelliJ AST) | 4/4 (100%) | 4/4 (100%) | **8/8** |
| Text-edit (raw file I/O) | 4/4 (100%) | ~0/4 (0%) | **~4/8** |

The structural advantage manifests specifically and exclusively on cross-file refactorings. This is the core empirical claim of the thesis: **IntelliJ's refactoring APIs encode program semantics that text-edit approaches do not have access to.**

---

## 9. Developer Study (RQ4, Future Work)

**Participants:** 10–15 final-year CS/EE students or junior developers.

**Task set:** 5 maintenance tasks on an unfamiliar Java project (rename, extract, move, safe-delete, add method).

**Conditions:**
- A: Manual IntelliJ (standard refactoring menu)
- B: GitHub Copilot Chat inside IntelliJ
- C: Structured-refactoring-agent chat panel

**Measures:**
- Time to complete each task
- Number of errors requiring undo
- Post-task NASA-TLX cognitive load questionnaire
- Semi-structured interview: "Did you trust the change was correct without checking?"
