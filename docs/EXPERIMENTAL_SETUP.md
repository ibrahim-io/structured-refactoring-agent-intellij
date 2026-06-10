# Experimental Setup & Reproducibility

This documents the exact environment, models, harness, and commands used in the
evaluation (Chapter 5), so every result can be reproduced. Distilled into the
report's "Experimental Setup" section.

## Environment
- **OS:** Microsoft Windows 11 Home, build 26200
- **Machine (local-model arm):** AMD Ryzen 7 4800H, 15 GB RAM, NVIDIA GeForce RTX 2060 (6 GB VRAM)
- **IDE:** IntelliJ IDEA Community **2025.1.4** (sandbox `IC-2025.1.4.1`, platform build 251)
- **Plugin:** structured-refactoring-agent **v0.2.0**, launched via Gradle **8.14.3** `runIde` (IntelliJ Platform Gradle plugin), running on the bundled JBR
- **Headless flag (required for unattended refactoring):** JVM arg `-Dide.performance.skip.refactoring.dialogs=true` (suppresses preview / conflicts / auto-rename dialogs)
- **Compile validator:** Apache **Maven 3.8.5**, Oracle **JDK 24.0.2**
- **Python (harness):** 3.10.5

## Tool interface
- Plugin exposes refactoring ops over HTTP on `localhost:6473`: `POST /tools` (body `{"tool":..,"params":{..}}`), `GET /tools/schema`, `GET /status`. The MCP bridge exposes the **same** `RefactorService` operations to MCP clients (Claude Code / Desktop / Cursor); HTTP and MCP are functionally identical.

## Models, per evaluation arm
| Arm | Action space | Model / driver |
|---|---|---|
| Capable agent + structured | structured tools | **Claude Opus 4.8** (`claude-opus-4-8`), driven as blind Claude Code subagents (one fresh agent per task) |
| Capable agent + text-edit (control) | direct file editing | **Claude Opus 4.8** (`claude-opus-4-8`), same blind-subagent harness, native read/edit/grep, no structured tools |
| Weak model + structured | structured tools | **qwen2.5:7b** via **Ollama 0.30.2** (WSL2, `OLLAMA_HOST=0.0.0.0:11434`, reached from Windows at `localhost:11434`), `--provider ollama`, OpenAI-compatible tool-calling, `--max-turns 20` |
| Weak model + text-edit | text-edit tools | Groq **`meta-llama/llama-4-scout-17b-16e-instruct`** (30k TPM), `--provider groq`, temperature 0, `--max-turns 20` |
| No-LLM structured (ceiling) | structured tools | scripted (`run_petclinic_direct.py`) |
| No-LLM scripted text-edit | regex edits | scripted (`run_text_edit_direct.py`) |

> The Claude arms ran on the orchestrating Claude Code session's model (Opus 4.8, `claude-opus-4-8`); subagent calls inherited it (no per-agent model override). Disclosed in the GenAI declaration.

## Benchmark projects
- **spring-petclinic** — 9 tasks (`benchmarks/tasks_petclinic.json`). Made **Maven-only** (removed redundant `build.gradle`/`settings.gradle`, commit `da6f5f0`) so the IDE imports unambiguously as Maven.
- **Apache Commons Lang** `rel/commons-lang-3.14.0` (~246 source files) — 4 tasks (`benchmarks/tasks_commons_lang.json`). Maven-only.

## Blind-agent harness (Claude arms)
Per task, strictly sequential (single shared IDE + one project git):
1. Establish clean baseline commit **C0** on the project git.
2. **Drive (blind):** a fresh Claude subagent receives ONLY the task text + `GET /tools/schema` (no expected answers). Structured arm: all code changes via the tools; may `mvn compile`, read, and iterate to self-correct, but no hand-editing. Text-edit arm: native file editing only, no structured tools; may compile + iterate.
3. **Validate (independent):** reuse the same oracle as the other arms — `validate_one.py` (wraps `run_benchmarks.validate()`) for structured, `validate_one_textedit.py` (wraps `run_benchmarks_text_edit.validate()`) for text-edit. Oracle = Maven compile + disk-state checks (symbol renamed everywhere / file exists / cross-file old-name absent).
4. `git reset --hard C0 && git clean -fd`.

**Validator fairness fix (disclosed):** for the structured arm, only the *mutating* operations are required to have been called; read-only/exploratory tools are optional (an agent may reach the correct outcome without them). Correctness is graded by compile + disk-state. The scripted direct runner still passes, so the 9/9 ceiling is unaffected.

## Reproduce
```
# Weak-model structured (local, free):
python benchmarks/run_benchmarks.py --provider ollama --model qwen2.5:7b \
  --tasks benchmarks/tasks_petclinic.json --max-turns 20 --out results/structured-petclinic-ollama.json
# Weak-model text-edit:
python benchmarks/run_benchmarks_text_edit.py --provider groq \
  --model meta-llama/llama-4-scout-17b-16e-instruct \
  --tasks benchmarks/tasks_petclinic.json --max-turns 20 --out results/text-edit-petclinic-llama4scout.json
# Capable-agent arms: blind Claude-Code subagent workflows (see report appendix / workflow scripts).
# Standalone validators (reused for the Claude arms):
python benchmarks/validate_one.py          --tasks <tasks.json> --task-id <id> --calls <calls.json> --projects-dir benchmarks/projects
python benchmarks/validate_one_textedit.py --tasks <tasks.json> --task-id <id> --project-dir benchmarks/projects/<project>
```
