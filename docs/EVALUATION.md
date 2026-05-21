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

A Maven project (Java 17) with ten classes, designed to expose structural advantages of
AST-aware refactoring over text-edit approaches. Every task in `benchmarks/tasks.json`
targets this project.

| Class | Qualified name | Role |
|---|---|---|
| `User.java` | `com.example.User` | Has poorly-named field `usrNm`; rename + add-method targets |
| `LegacyHelper.java` | `com.example.LegacyHelper` | Has unused method `parseOldFormat` (safe-delete), `parseNewFormat` (cross-file rename target), and `normalize` (inline target) |
| `Notifier.java` | `com.example.Notifier` | Has method `send(String)`; change-signature target |
| `utils/DateHelper.java` | `com.example.utils.DateHelper` | move-class target |
| `OrderProcessor.java` | `com.example.OrderProcessor` | **Cross-file**: imports `com.example.utils.DateHelper`. After move-001, `MoveClassesOrPackagesProcessor` updates this import automatically; a text-edit agent leaves it stale → compile failure. |
| `NotificationController.java` | `com.example.NotificationController` | **Cross-file**: calls `notifier.send(message)`. After change-sig-001 adds `int priority`, `ChangeSignatureProcessor` updates this call to `send(message, 0)`; a text-edit agent leaves it broken. |
| `ServiceLayer.java` | `com.example.ServiceLayer` | **Cross-file**: calls `LegacyHelper.parseNewFormat` and `LegacyHelper.normalize`. After rename-method-002 and inline-001 respectively. |
| `SearchService.java` | `com.example.SearchService` | **inline-001 call site**: calls `LegacyHelper.normalize(query)` — param name `query` |
| `ReportGenerator.java` | `com.example.ReportGenerator` | **inline-001 call site**: calls `normalize(fieldValue)` and `normalize(title)` — two different param names |
| `DataImporter.java` | `com.example.DataImporter` | **inline-001 call site**: calls `normalize(entry)` inside a loop — param name `entry` |

**inline-001 specifically**: `LegacyHelper.normalize` has **5 call sites** across 4 files,
each passing a differently-named local variable. `InlineMethodProcessor` substitutes the
correct variable name at each site by construction. A text-edit agent must enumerate all 4
files and manually substitute the right variable at each call — missing one site causes a
compile failure; using the wrong variable name produces a silent runtime bug.

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
| `inline-001` | Inline `LegacyHelper.normalize` — 5 call sites, 4 files, distinct param names | **Structural**: correct parameter substitution at each site; text-edit agent must enumerate all sites and substitute the right local variable name |
| `create-class-001` | Create interface `com.example.payments.PaymentGateway` | New file on disk |
| `add-method-001` | Add `User.getDisplayName()` | Symbol added to existing class |
| `change-sig-001` | Add `int priority` to `Notifier.send` signature | **Cross-file**: `NotificationController.java` calls old signature |

---

## 4. Multi-Layer Validation (`run_benchmarks.py`)

Each task validates at four layers:

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
| `compile_and_no_reference` | `deletedSymbol` absent from all `.java` files (word-boundary regex: `\b<symbol>\b`) |
| `compile_and_symbol_exists` | `expectedSymbol` present in `.java` files |
| `compile_and_symbol_exists` + `crossFileCheck` | Old symbol name absent from ALL files (catches cross-file miss) |

**Validation note**: content checks use `\b<pattern>\b` word-boundary regex to avoid
false positives (e.g., `normalize` must not match `normalizeInput`).

### Layer 4: RefactoringMiner layer (optional)
Runs [RefactoringMiner](https://github.com/tsantalis/RefactoringMiner) between two git commits to classify the refactoring type (e.g., "Rename Attribute", "Move Class", "Add Parameter"). Requires `--rm-jar` argument.

### Task isolation
Both runners commit the baseline state before each task and **reset the project** after
validation so tasks are independent. The structured runner adds a 2-second wait after
reset to allow IntelliJ's VFS watcher to re-index the restored files.

### Running the benchmark
```powershell
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

No IntelliJ. No AST. No reference index. The agent sees the same task descriptions and
must figure out which files to edit by reading their text content.

This is the **apples-to-apples comparison** for RQ1. Both agents are driven by the same
`claude-sonnet-4-6` model on the same tasks. The only difference is the tool API.

```powershell
python benchmarks/run_benchmarks_text_edit.py `
    --tasks benchmarks/tasks.json `
    --api-key $env:ANTHROPIC_API_KEY `
    --projects-dir benchmarks/projects `
    --out results/text-edit-$(Get-Date -f yyyyMMdd-HHmmss).json
```

---

## 6. Actual Results (First Run — Sample Project)

### Text-edit baseline (run 2025-05-21, `results/text-edit-1.json`)

Surprisingly, `claude-sonnet-4-6` scored **8/8** with only file I/O tools on the
initial sample project (which had only 1 call site per cross-file task). The model
was smart enough to read all files and update cross-file references manually.

| Task ID | Status | Key note |
|---|---|---|
| rename-001 | PASS | Single-file rename, trivial |
| move-001 | PASS | Agent found and updated OrderProcessor.java import |
| safe-delete-001 | PASS | Deleted unused method correctly |
| create-class-001 | PASS | Created new interface |
| add-method-001 | PASS | Added method to existing class |
| rename-method-002 | PASS | Agent found and updated ServiceLayer.java call site |
| inline-001 | PASS | Agent correctly inlined with parameter substitution (1 call site) |
| change-sig-001 | PASS | Agent found and updated NotificationController.java call |

**Interpretation**: A capable LLM can enumerate a small project's files manually and
update cross-file references correctly. The binary pass/fail metric does not differentiate
the agents on a 10-file project — the advantage lies in **efficiency** and **scalability**.

### Efficiency comparison (pending structured run)

The text-edit agent uses 5–12 turns per task (read every file, grep content, write
changes). The structured agent is expected to use 1–2 turns per task (one tool call +
optionally one verification call). The efficiency gap grows with project size.

### Why inline-001 was upgraded to 5 call sites

After the initial text-edit run, `inline-001` was upgraded from 1 to 5 call sites (across
4 new files: SearchService, ReportGenerator×2, DataImporter) to properly challenge the
text-edit agent. Each call site uses a **different local parameter name**:

| Call site | Param name | Challenge |
|---|---|---|
| `ServiceLayer.normalizeInput` | `raw` | Baseline call site |
| `SearchService.search` | `query` | Different name, straightforward |
| `ReportGenerator.formatField` | `fieldValue` | Different name |
| `ReportGenerator.formatTitle` | `title` | Different name, same file as above |
| `DataImporter.importRecords` | `entry` | Inside a for-each loop |

`InlineMethodProcessor` handles all 5 correctly by construction because it operates on the
AST and maps the formal parameter to the actual argument expression at each call site.
A text-edit agent that copies the method body verbatim (`value.toLowerCase().trim()`) at
each call site produces code that references `value` — a variable that doesn't exist at
the call site — causing a compile failure. The agent must detect the parameter mapping
itself; failing even one produces either a compile error or a silent bug.

---

## 7. Comparison (`compare_results.py`)

```powershell
python benchmarks/compare_results.py results/structured-run.json results/text-edit-run.json
```

Outputs two tables:
1. **Correctness** (PASS/FAIL per task)
2. **Efficiency** (turns, tool calls, wall time per task)

The efficiency table is the key differentiator on small projects where both agents may
score 8/8. On a larger project the correctness gap reopens (text-edit misses call sites
it cannot enumerate within the context window).

---

## 8. Metrics

| Metric | How measured |
|---|---|
| **Correctness** | `ok: true` from tool call + project compiles after operation |
| **Cross-file correctness** | Old symbol absent from all files after rename/move |
| **RefactoringMiner match** | Run RefactoringMiner on git diff; check expected type detected |
| **Task completion rate** | % of benchmark tasks where all expected tools returned `ok: true` |
| **Compile pass rate** | % of tasks where project compiles after agent's changes |
| **Turns to completion** | Number of API round-trips per task |
| **Tool calls per task** | Total tool invocations (efficiency proxy) |
| **Cross-file advantage** | Pass rate delta between structured and text-edit agents on cross-file tasks |

---

## 9. Updated Hypothesis

Based on initial text-edit results (8/8 on the small project before inline-001 upgrade):

| Metric | Structured (IntelliJ AST) | Text-edit (raw file I/O) |
|---|---|---|
| Correctness — 10-file project | 8/8 (100%) | **7/8 or 8/8** (inline-001 hard) |
| Avg turns per task | **~2** | ~6–8 |
| Avg tool calls per task | **~2** | ~10–15 |
| Correctness — larger project (100+ files) | **8/8** | ~3/8 (misses call sites) |
| Guarantee of syntactic validity | **Yes** (AST operations) | No (text substitution) |

The primary thesis claim has been refined: **on a small project, a capable LLM can
compensate for lack of AST tools by reading all files; the structural advantage of
IntelliJ-backed refactoring manifests as (a) efficiency (fewer turns/API calls), and
(b) correctness guarantees at scale — both of which are critical for production use
in large codebases.**

---

## 10. Developer Study (RQ4, Future Work)

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
