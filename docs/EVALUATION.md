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

The suite now has 9 tasks across 7 operation types:

| Task ID | Operation | Cross-file challenge |
|---|---|---|
| `pc-rename-001` | Rename field `Owner.telephone` → `phoneNumber` | Within single file |
| `pc-add-method-001` | Add `Owner.getFullName()` | Symbol creation |
| `pc-find-usages-001` | Find all usages of `Pet` class | Structural index query |
| `pc-read-file-001` | Read first 50 lines of `Owner.java` | Inspection only |
| `pc-rename-002` | Rename class `CrashController` → `PanicController` + file rename | File rename: structured renames `CrashController.java` → `PanicController.java`; text-edit must rename the file AND update the class declaration |
| `pc-rename-method-001` | Rename `Owner.addVisit` → `recordVisit` | Cross-file: `VisitController` calls `owner.addVisit(petId, visit)` |
| `pc-move-001` | Move `PetValidator` from `owner` → `system` package | Cross-file import: `PetController` uses `PetValidator` (same package, no import); after move it needs `import ...system.PetValidator` |
| `pc-rename-method-002` | Rename `Owner.getPet(String, boolean)` → `findPet` | Overload disambiguation: 3 overloads of `getPet` exist; only the `(String, boolean)` variant and its 2 callers in `PetController` are renamed |
| `pc-change-sig-001` | Add `boolean fromController` param to `Owner.addPet` | Cross-file: 3 call sites in `PetController` all need the default arg inserted |

### 2c. No-API petclinic backup runner

`benchmarks/run_petclinic_direct.py` executes the predefined operations in
`tasks_petclinic.json` directly against the IntelliJ localhost tool API. It does
not import Anthropic, read `.env`, or call an LLM.

This runner is intentionally not a replacement for the final agent comparison.
It separates two claims:

| Runner | What it tests | API credits |
|---|---|---|
| `run_benchmarks.py` | Full Claude agent loop + structured IntelliJ tools | Yes |
| `run_benchmarks_text_edit.py` | Full Claude agent loop + raw text-edit tools | Yes |
| `run_petclinic_direct.py` | Structured IntelliJ tool layer only | No |
| `run_text_edit_direct.py` | Scripted regex/string edit baseline | No |

The direct runner is useful when credits are unavailable and as a diagnostic
step before spending credits on a full run. If direct mode fails, the problem is
in the tool implementation or IntelliJ project state. If direct mode passes but
API mode fails, the problem is in LLM planning/tool choice.

The scripted text-edit runner is deliberately weaker than a modern LLM agent. It
is a lower-bound baseline that isolates the edit primitive: raw text rewriting
without a reference index, PSI tree, compiler-aware symbol resolution, or IDE
refactoring semantics.

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

## 6. Actual Results (Sample Project)

Both agents reach 8/8 on the 10-file sample project. The structured agent uses
~3× fewer tool calls per task and ~1.3× fewer agent turns on average.

### Correctness (both agents): 8/8

| Task ID | Structured | Text-edit |
|---|---|---|
| rename-001 | PASS | PASS |
| move-001 | PASS | PASS |
| safe-delete-001 | PASS | PASS |
| create-class-001 | PASS | PASS |
| add-method-001 | PASS | PASS |
| rename-method-002 | PASS | PASS |
| inline-001 | PASS | PASS |
| change-sig-001 | PASS | PASS |

### Efficiency (structured vs text-edit)

Source: `results/structured-4.json` vs `results/text-edit-1.json`.

| Task ID | S.turns | TE.turns | S.tools | TE.tools | S.time | TE.time |
|---|---|---|---|---|---|---|
| rename-001         | 3 | 4 | 3  | 9  | 13.2s | 24.8s |
| move-001           | 4 | 5 | 3  | 12 | 13.6s | 34.4s |
| safe-delete-001    | 3 | 4 | 2  | 9  | 11.5s | 24.8s |
| create-class-001   | 2 | 4 | 1  | 9  | 9.8s  | 25.1s |
| add-method-001     | 4 | 4 | 3  | 9  | 26.4s | 26.0s |
| rename-method-002  | 3 | 4 | 3  | 10 | 22.4s | 27.5s |
| inline-001         | 5 | 5 | 13 | 10 | 62.7s | 28.6s |
| change-sig-001     | 3 | 4 | 2  | 10 | 25.9s | 24.3s |
| **Average / Total**| **3.4** | **4.2** | **3.8** | **9.8** | **185.6s** | **215.6s** |

**Interpretation**: A capable LLM can enumerate a small project's files manually
and update cross-file references correctly, so binary pass/fail does not separate
the agents on a 10-file project. The structural advantage manifests instead as:

- **3× fewer tool calls per task** (3.8 avg vs 9.8 avg) — the structured agent
  invokes one refactoring tool; the text-edit agent reads each file, greps for
  references, writes substitutions.
- **Faster wall-clock** even with one tool call per task being more expensive
  (the structured agent skips multi-file read passes entirely).
- **Single-shot symbol resolution** (`find_symbol_by_name` + qualifiedName)
  replaces the text-edit agent's exploration phase.
- The `inline-001` outlier (13 structured tool calls) is the agent verifying the
  inline result via `read_file` on every modified file — an artifact of the
  agent's caution, not a structural requirement.

### Class rename now guarantees file rename (2026-05-26)

The initial `rename()` implementation in `RefactorService.kt` used manual PSI text
replacement (`handleElementRename` + `setName`), which updates references in memory
but does not invoke IntelliJ's file-rename side effect. When renaming a public class
`Foo`, IntelliJ's `RenameProcessor` is responsible for also renaming `Foo.java` →
`Bar.java`. The manual path silently skipped this step.

Fix: replaced the manual implementation with `RenameProcessor(project, element, newName,
searchInComments, searchTextOccurrences).run()` inside `runProcessorOnEdt`, the same
dispatcher already used by `MoveClassesOrPackagesProcessor` and `ChangeSignatureProcessor`.
This guarantees the file rename happens as part of the same atomic refactoring.

Affected task: `pc-rename-002` (CrashController → PanicController). This task now uses
`compile_and_file_exists` validation (checking `PanicController.java` exists at the right
path) rather than RefactoringMiner, giving a direct, observable signal of whether the file
rename happened.

### Plugin robustness fix discovered during benchmarking

Initial structured runs scored 6/8 due to a cross-task contamination bug:
git resets between tasks updated the disk, but IntelliJ's in-memory `Document`s
retained the previous task's state. When the next task ran, `saveAllDocuments()`
silently failed for files with a stale Document (IntelliJ detected an external
change conflict and refused to overwrite). Fix: each modifying entry point
(`renameByQualifiedName`, `safeDeleteByQualifiedName`, `moveClass`,
`changeSignature`, `inlineMethod`, `extractMethod`, `extractVariable`) and each
PSI-creation entry point (`addField`, `addMethod`, `addInnerClass`) now calls
a synchronous `refresh(false, true)` on source roots before reading PSI. This
is a property a production plugin needs anyway — robustness against external
file changes (git operations, external editors, CI).

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

## 7b. Actual Results (spring-petclinic, No-API Direct)

Source files:

- Structured: `results/structured-petclinic-direct-4.json`
- Scripted text-edit: `results/text-edit-petclinic-direct-1.json`

This run used **zero LLM/API calls**. It compares the structured IntelliJ tool
layer directly against a deterministic regex/string-edit baseline.

| Task ID | Structured direct | Scripted text-edit | Note |
|---|---|---|---|
| `pc-rename-001` | PASS | PASS | Field rename compiles in both modes |
| `pc-add-method-001` | PASS | PASS | Method insertion compiles in both modes |
| `pc-find-usages-001` | PASS | PASS | Structured returns PSI/index-backed usages; text baseline returns grep matches |
| `pc-read-file-001` | PASS | PASS | Basic file inspection |
| `pc-rename-002` | **PASS** | **FAIL** | Structured class rename succeeds; scripted text replacement fails to perform the class/file rename |

Summary:

| Metric | Structured direct | Scripted text-edit |
|---|---:|---:|
| Correctness | **5/5** | **4/5** |
| Total time | 42.9s | 45.6s |
| LLM turns | 0 | 0 |
| API cost | £0 | £0 |

Interpretation: this is the first petclinic result showing a binary correctness
gap without spending API credits. The structured layer succeeds on class rename
because it operates through PSI/IDE semantics. The scripted baseline operates on
raw text and failed to carry out the equivalent class/file rename.

The `find_usages` task also demonstrates an important qualitative distinction:
the structured path exposes a reference-resolution operation, while the text
baseline can only approximate this with grep. For the direct runner, a textual
fallback is used only when the IDE index is unavailable during project import;
the final thesis should report whether a result is index-backed or fallback.

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

Based on observed results on the 10-file sample project:

| Metric | Structured (IntelliJ AST) | Text-edit (raw file I/O) |
|---|---|---|
| Correctness — 10-file project | **8/8** (measured) | **8/8** (measured) |
| Avg turns per task | **3.4** (measured) | **4.2** (measured) |
| Avg tool calls per task | **3.8** (measured) | **9.8** (measured) |
| Correctness — larger project (100+ files) | **8/8** (hypothesised) | ~3/8 (hypothesised — context-window limited) |
| Guarantee of syntactic validity | **Yes** (AST operations) | No (text substitution) |

The primary thesis claim has been refined: **on a small project, a capable LLM can
compensate for lack of AST tools by reading all files; the structural advantage of
IntelliJ-backed refactoring manifests as (a) efficiency (fewer turns/API calls — 3×
fewer measured), and (b) correctness guarantees at scale — both of which are
critical for production use in large codebases.** The petclinic suite
(`tasks_petclinic.json`) is the next benchmark to test the scalability claim
directly.

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
