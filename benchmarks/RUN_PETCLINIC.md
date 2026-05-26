# Petclinic Benchmark Runbook

The sample-java-project benchmark (`tasks.json`) reached 8/8 vs 8/8. On a
10-file project, the structural advantage mainly appears as efficiency: fewer
tool calls and fewer agent turns. The petclinic suite (`tasks_petclinic.json`)
tests the same idea on a real Spring Boot codebase.

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
    --out results/structured-petclinic-direct-1.json
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
    --out results/text-edit-petclinic-direct-1.json
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

## Expected Outcomes

| Task | Structured API | No-API direct | Text-edit | Differentiator |
|---|---|---|---|---|
| `pc-rename-001` (`Owner.telephone` to `phoneNumber`) | PASS | PASS | PASS | Efficiency / scale |
| `pc-add-method-001` (`Owner.getFullName`) | PASS | PASS | PASS | Tool-layer persistence |
| `pc-find-usages-001` (`Pet` usages) | PASS | PASS | FAIL or N/A | Structural reference index |
| `pc-read-file-001` (`Owner.java`) | PASS | PASS | PASS | Basic inspection |
| `pc-rename-002` (`CrashController` to `PanicController`) | PASS | PASS or reveals class-file rename gap | PASS/FAIL | Class rename + file rename |

If direct mode fails, the failure is in the structured tool implementation or
IntelliJ project state. If direct mode passes but API mode fails, the issue is
agent tool choice rather than the underlying structured operation.

## Actual No-API Results

Latest direct no-API run:

- Structured direct: `results/structured-petclinic-direct-4.json` -> **5/5**
- Scripted text-edit direct: `results/text-edit-petclinic-direct-1.json` -> **4/5**

The structured-only win is `pc-rename-002` (`CrashController` to
`PanicController`). The structured tool performs the class rename successfully;
the scripted text-edit baseline fails to make the equivalent class/file change.

Comparison command:

```powershell
python benchmarks/compare_results.py `
    results/structured-petclinic-direct-4.json `
    results/text-edit-petclinic-direct-1.json
```

## After The Run

If results are roughly as expected, add harder cross-file tasks such as:

- Rename `Pet` across controllers, repositories, tests, and templates.
- Move `Owner` or `Pet` to a new package.
- Rename a field used by validation, persistence, templates, and tests.

If text-edit unexpectedly matches structured on petclinic, step up to a larger
Spring project where context-window limits and manual reference enumeration are
more likely to bite.
