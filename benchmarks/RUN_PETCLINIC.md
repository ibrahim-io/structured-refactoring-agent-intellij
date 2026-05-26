# Petclinic Benchmark Runbook

The sample-java-project benchmark (`tasks.json`) reached 8/8 vs 8/8. On a
10-file project, the structural advantage mainly appears as efficiency: fewer
tool calls and fewer agent turns. The petclinic suite (`tasks_petclinic.json`)
tests the same idea on a real Spring Boot codebase (30+ classes, ~4000 LOC).

## State After Preparation

- Repo cloned: `benchmarks/projects/spring-petclinic/` on the main branch.
- HEAD SHA recorded: `benchmarks/projects/spring-petclinic.sha`.
- `build.gradle.kts` reads `BENCHMARK_PROJECT` and defaults to
  `sample-java-project`.
- `run_benchmarks.py`, `run_benchmarks_text_edit.py`, and
  `run_petclinic_direct.py` support the petclinic validation types.
- Maven Java compilation was verified on the baseline. Benchmark runners skip
  Checkstyle/nohttp and Spring Java Format during compile validation so the
  measurement captures Java correctness rather than style or IDE lock-file
  noise while the sandbox IDE is open.

## Launch Petclinic In IntelliJ

If IntelliJ is open with `sample-java-project`, close it first. Then launch the
sandbox IDE on petclinic:

```powershell
$env:BENCHMARK_PROJECT = "spring-petclinic"
.\gradlew.bat runIde
```

Wait for:

1. Build to finish.
2. IntelliJ window to appear.
3. Maven import and indexing to complete.
4. Status check to report `"project":"spring-petclinic"`:

```powershell
Invoke-RestMethod http://127.0.0.1:6473/status
```

## Option A: API Mode

This is the full agent evaluation: Claude reads the task description and chooses
which structured IntelliJ tools to call. Use this when API credits are available.

```powershell
python benchmarks/run_benchmarks.py `
    --tasks benchmarks/tasks_petclinic.json `
    --projects-dir benchmarks/projects `
    --out results/structured-petclinic-1.json
```

## Option B: No-API Backup Mode

This validates the structured IntelliJ tool layer with zero Anthropic usage. It
executes the predefined `operations` in `tasks_petclinic.json` directly against
`POST /tools`, resolves placeholders such as `__resolved__` from earlier
`find_symbol_by_name` results, and runs the same compile/content validation.

Use this mode when you are out of credits, or before spending credits on the
full agent loop.

```powershell
python benchmarks/run_petclinic_direct.py `
    --tasks benchmarks/tasks_petclinic.json `
    --projects-dir benchmarks/projects `
    --agent-port 6473 `
    --out results/structured-petclinic-direct-5.json
```

Cheap smoke tests:

```powershell
python benchmarks/run_petclinic_direct.py `
    --tasks benchmarks/tasks_petclinic.json `
    --task-id pc-read-file-001 `
    --projects-dir benchmarks/projects `
    --out results/structured-petclinic-direct-read-file.json

python benchmarks/run_petclinic_direct.py `
    --tasks benchmarks/tasks_petclinic.json `
    --task-id pc-find-usages-001 `
    --projects-dir benchmarks/projects `
    --out results/structured-petclinic-direct-find-usages.json
```

Interpretation:

- `run_benchmarks.py` evaluates the full LLM + structured tools workflow.
- `run_petclinic_direct.py` evaluates only the structured IntelliJ tool layer.
- Direct mode is a backup and diagnostic path, not a replacement for the final
  agent-vs-agent comparison.

## Option C: Text-Edit Baseline

The text-edit runner does not need IntelliJ. It only reads and writes files. Use
it when API credits are available and you want the direct comparison against a
raw file-editing agent.

```powershell
python benchmarks/run_benchmarks_text_edit.py `
    --tasks benchmarks/tasks_petclinic.json `
    --projects-dir benchmarks/projects `
    --out results/text-edit-petclinic-1.json
```

## Option D: No-API Scripted Text-Edit Baseline

This is the zero-cost lower-bound baseline for textual editing. It applies
deterministic regex/string edits directly to `.java` files without IntelliJ,
without an AST, without an LLM, and without API credits.

```powershell
python benchmarks/run_text_edit_direct.py `
    --tasks benchmarks/tasks_petclinic.json `
    --projects-dir benchmarks/projects `
    --out results/text-edit-petclinic-direct-5.json
```

Interpretation:

- If structured direct passes and scripted text-edit fails, the difference is
  caused by the edit primitive itself: AST/reference-aware tools vs raw text.
- If both pass, the task is probably too easy and should be scaled up.
- If both fail, the task or validation needs debugging before spending API
  credits.

## Compare API Results

```powershell
$env:PYTHONIOENCODING = "utf-8"
python benchmarks/compare_results.py `
    results/structured-petclinic-1.json `
    results/text-edit-petclinic-1.json
```

## Expected Outcomes (9-task suite)

| Task | Structured API | No-API direct | Text-edit direct | Differentiator |
|---|---|---|---|---|
| `pc-rename-001` (field rename) | PASS | PASS | PASS | Efficiency / scale |
| `pc-add-method-001` (add method) | PASS | PASS | PASS | Tool-layer persistence |
| `pc-find-usages-001` (Pet usages) | PASS | PASS | PASS (grep) | Index vs grep precision |
| `pc-read-file-001` (read Owner) | PASS | PASS | PASS | Basic inspection |
| `pc-rename-002` (class+file rename) | PASS | PASS | PASS | Both rename file correctly |
| `pc-rename-method-001` (addVisit→recordVisit) | PASS | PASS | **FAIL** | Cross-class name collision: Pet.addVisit renamed too |
| `pc-move-001` (PetValidator→system) | PASS | PASS | **FAIL** | Implicit same-package deps |
| `pc-rename-method-002` (getPet overload) | PASS | PASS | **FAIL** | Overload-specific rename |
| `pc-change-sig-001` (addPet +param) | PASS | PASS | PASS | Both update call sites |

**Predicted: Structured 9/9, Text-edit 6/9**

The three text-edit failures expose fundamental limitations of text editing:

1. **pc-rename-method-001**: `Pet.addVisit(Visit)` shares the name `addVisit`
   with `Owner.addVisit(Integer, Visit)`. Text replacement renames all occurrences
   of the word `addVisit`, incorrectly renaming `Pet.addVisit` too. IntelliJ's
   `RenameProcessor` uses the PSI reference graph to distinguish calls to
   `owner.addVisit(...)` from calls to `pet.addVisit(...)`.

2. **pc-move-001**: `PetValidator` references `Pet` from the same `owner` package,
   and `PetController` uses `PetValidator` also from the same package. After moving
   `PetValidator` to `system`, both callers need new import statements. Text editing
   can only update *explicit* imports; IntelliJ's `MoveClassesOrPackagesProcessor`
   resolves the full reference graph including implicit same-package dependencies.

3. **pc-rename-method-002**: Asks to rename only the two-parameter overload
   `getPet(String, boolean)`. Text replacement is name-only: it renames all three
   `getPet` overloads (`(String)`, `(Integer)`, `(String, boolean)`) to `findPet`.
   IntelliJ's `RenameProcessor` resolves the specific PSI method node for that
   overload, leaving the other overloads untouched.

## Actual No-API Results

### Structured direct (last full run: `results/structured-petclinic-direct-4.json`)

5 tasks were run before the 4 new tasks were added. IntelliJ must be relaunched
on petclinic to run the full 9-task suite:

```powershell
$env:BENCHMARK_PROJECT = "spring-petclinic"
.\gradlew.bat runIde
# after indexing completes:
python benchmarks/run_petclinic_direct.py `
    --tasks benchmarks/tasks_petclinic.json `
    --projects-dir benchmarks/projects `
    --out results/structured-petclinic-direct-5.json
```

### Scripted text-edit direct (last full run: `results/text-edit-petclinic-direct-6.json`)

Canonical 9-task result: **6/9**

- PASS (6): pc-rename-001, pc-add-method-001, pc-find-usages-001,
  pc-read-file-001, pc-rename-002, pc-change-sig-001
- FAIL (3): pc-rename-method-001 (cross-class name collision — Pet.addVisit renamed
  too), pc-move-001 (implicit same-package deps), pc-rename-method-002
  (overload collision — all getPet variants renamed to findPet)

Comparison command:

```powershell
python benchmarks/compare_results.py `
    results/structured-petclinic-direct-5.json `
    results/text-edit-petclinic-direct-6.json
```

## After The Run

If results are roughly as expected (9/9 vs 6/9), the next step is the full LLM
agent comparison (Option A vs Option C) to measure how the structural advantage
manifests in agent-generated refactoring decisions.

Harder tasks to add if the gap is too small:

- Rename `Pet` across controllers, repositories, tests, and templates (10+
  same-package and cross-package references).
- Move `Owner` to a new package (many callers in the same package).
- Rename a field used by Bean Validation annotations and Thymeleaf templates.
