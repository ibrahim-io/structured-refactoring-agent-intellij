# Cross-Agent Evaluation Strategy

The main research claim is model-agnostic:

> AI coding assistants should use structured AST/refactoring operations for
> large codebase changes, rather than relying only on textual patches.

Claude is one way to drive the tool API, but the structured backend is not
Claude-specific. Codex, Gemini, Copilot, or any future agent could call the same
IntelliJ tools.

## Evaluation Modes

| Mode | Runner / Tool | Extra API cost | Purpose |
|---|---|---:|---|
| Structured direct | `benchmarks/run_petclinic_direct.py` | No | Tests IntelliJ tool correctness directly |
| Scripted text-edit direct | `benchmarks/run_text_edit_direct.py` | No | Lower-bound raw text baseline |
| Claude structured | `benchmarks/run_benchmarks.py` | Yes | Tests LLM planning with structured tools |
| Claude text-edit | `benchmarks/run_benchmarks_text_edit.py` | Yes | Tests LLM planning with raw file tools |
| Codex text-edit | Codex CLI / app manually | Usually subscription quota | Tests another agent's text-edit behavior |
| Gemini text-edit | Gemini CLI / Code Assist manually | Free/subscription quota where available | Tests another agent's text-edit behavior |
| Copilot text-edit | Copilot Chat / agent mode manually | Plan quota / premium requests | Tests IDE-native text-edit behavior |

## Zero-Cost First Policy

When credits are low, run evaluations in this order:

1. Structured direct petclinic smoke test:

   ```powershell
   python benchmarks/run_petclinic_direct.py `
       --tasks benchmarks/tasks_petclinic.json `
       --task-id pc-find-usages-001 `
       --projects-dir benchmarks/projects `
       --out results/structured-petclinic-direct-find-usages.json
   ```

2. Full structured direct petclinic suite:

   ```powershell
   python benchmarks/run_petclinic_direct.py `
       --tasks benchmarks/tasks_petclinic.json `
       --projects-dir benchmarks/projects `
       --out results/structured-petclinic-direct-1.json
   ```

3. Scripted text-edit direct suite:

   ```powershell
   python benchmarks/run_text_edit_direct.py `
       --tasks benchmarks/tasks_petclinic.json `
       --projects-dir benchmarks/projects `
       --out results/text-edit-petclinic-direct-1.json
   ```

Only spend LLM/API credits after the direct structured tools are known to work
on the target project.

## Running Subscription Agents Without Anthropic API Cost

For Codex, Gemini, or Copilot manual comparisons:

1. Use the same task descriptions from `benchmarks/tasks_petclinic.json`.
2. Start each task from a clean git baseline.
3. Let the agent edit files normally, without the IntelliJ structured tool API.
4. Run Maven compile and the same content checks afterwards.
5. Record:
   - agent name and model, if visible
   - subscription/free/API source
   - task status
   - elapsed time
   - files modified
   - compile result
   - qualitative failure mode

Important: do not launch these tools from a shell that contains paid API keys
unless the tool explicitly confirms it will use subscription quota rather than
API billing.

For PowerShell, start a clean shell session and avoid setting:

```powershell
$env:ANTHROPIC_API_KEY
$env:OPENAI_API_KEY
$env:GEMINI_API_KEY
$env:GOOGLE_API_KEY
```

## What To Compare

The comparison should not be "Claude vs Codex vs Gemini" as the main claim. The
comparison should be:

| Edit primitive | Examples | Expected behavior |
|---|---|---|
| Structured refactoring | IntelliJ PSI/refactoring tools | Fewer search steps, project-wide symbol updates, compile-safe transformations |
| Raw text editing | Codex/Gemini/Copilot/Claude file edits | More file reads, manual reference enumeration, higher risk on wide changes |

This keeps the FYP focused: the contribution is the structured editing interface,
not a leaderboard of proprietary models.
