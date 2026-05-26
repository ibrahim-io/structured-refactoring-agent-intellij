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
| OpenAI structured | `run_benchmarks.py --provider openai` | Yes | Same structured tools, different model |
| OpenAI text-edit | `run_benchmarks_text_edit.py --provider openai` | Yes | Same text-edit tools, different model |
| Gemini / Codex / Copilot via MCP | `benchmarks/mcp_bridge.py` | Subscription quota | Any MCP client calls the structured backend |
| Copilot / other text-edit | Manual | Plan quota | Tests IDE-native text-edit behavior |

## MCP Bridge (model-agnostic structured access)

`benchmarks/mcp_bridge.py` exposes the IntelliJ plugin's HTTP tool API as an
MCP server over stdio transport. Any MCP-compatible agent can call the same
structured refactoring tools without any changes to the plugin or the agent's
own architecture.

### Setup

```powershell
pip install mcp requests
```

### Connect Claude Desktop

Add to `%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "refactoring": {
      "command": "python",
      "args": ["C:/path/to/benchmarks/mcp_bridge.py", "--port", "6473"]
    }
  }
}
```

### Connect Gemini CLI

Add to `~/.gemini/settings.json`:

```json
{
  "mcpServers": {
    "refactoring": {
      "command": "python",
      "args": ["path/to/benchmarks/mcp_bridge.py"]
    }
  }
}
```

### What it exposes

The bridge fetches the tool schema live from `/tools/schema` each time a client
requests the tool list, so it automatically reflects any new tools added to the
plugin. It also adds a `get_project_status` tool (not in the plugin HTTP schema)
that returns the currently open project name — useful as a first call to confirm
the right project is loaded.

### Thesis significance

The MCP bridge is the concrete implementation of the paper's core architectural
claim: the structured refactoring backend is **model-agnostic**. Any agent that
speaks MCP can call it. The LLM (Claude, Gemini, Codex, GPT-4o) is a planning
layer; the IntelliJ PSI is the execution layer. These are decoupled.

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
