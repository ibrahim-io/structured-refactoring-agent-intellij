#!/usr/bin/env python3
"""
Benchmark runner for the structured-refactoring-agent.

Usage:
    python benchmarks/run_benchmarks.py \
        --tasks benchmarks/tasks.json \
        --agent-port 6473 \
        --api-key $ANTHROPIC_API_KEY \
        --projects-dir benchmarks/projects \
        --rm-jar tools/RefactoringMiner.jar \
        --out results/run.json

Requirements:
    pip install anthropic requests

The IntelliJ IDE with the plugin must be running with a project open before
running this script (so the agent server is started on port 6473).

Validation types (in tasks.json):
  compile_and_file_exists      -- project compiles + expected file exists on disk
  compile_and_no_reference     -- project compiles + deletedSymbol absent from sources
  compile_and_symbol_exists    -- project compiles + expectedSymbol present in sources
  compile_and_refactoringminer -- project compiles + RefactoringMiner detects expectedRefactoringType

RefactoringMiner (optional):
  Download from https://github.com/tsantalis/RefactoringMiner/releases
  Pass the jar path via --rm-jar. Without it, compile+content checks still run.
"""

import json
import os
import re
import sys
import time
import argparse
import subprocess
import requests
import anthropic

try:
    import openai as _openai_mod
    HAS_OPENAI = True
except ImportError:
    HAS_OPENAI = False
from pathlib import Path

# Load .env from repo root if present
_env = Path(__file__).parent.parent / ".env"
if _env.exists():
    for line in _env.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())

TOOL_SCHEMA_URL = "http://127.0.0.1:{port}/tools/schema"
TOOL_CALL_URL   = "http://127.0.0.1:{port}/tools"
STATUS_URL      = "http://127.0.0.1:{port}/status"


# ── Rate-limit back-off (free-tier providers throttle aggressively) ──────────
def _retry_seconds(msg: str):
    """Extract a wait hint like 'try again in 367.5ms' / 'in 9m49.68s' from a 429 message."""
    m = re.search(r"try again in\s+(?:(\d+)m)?\s*(\d+(?:\.\d+)?)\s*(ms|s)", msg)
    if not m:
        return None
    mins = int(m.group(1)) if m.group(1) else 0
    val  = float(m.group(2))
    secs = val / 1000.0 if m.group(3) == "ms" else val
    return mins * 60 + secs


def _chat_with_backoff(client, max_retries: int = 6, **kwargs):
    """chat.completions.create with retry on short (per-minute) rate limits.
    Long/daily limits are re-raised so the suite fails fast instead of stalling for minutes."""
    delay = 2.0
    for attempt in range(max_retries):
        try:
            return client.chat.completions.create(**kwargs)
        except _openai_mod.RateLimitError as e:
            wait = _retry_seconds(str(e))
            if wait is None:
                wait = delay
                delay = min(delay * 2, 30)
            if wait > 90:           # daily/long limit -> don't block the whole run
                raise
            print(f"    rate-limited; waiting {wait:.1f}s (retry {attempt + 1}/{max_retries})", flush=True)
            time.sleep(wait + 0.5)
    return client.chat.completions.create(**kwargs)  # final attempt; let it raise


# ── Schema conversion ────────────────────────────────────────────────────────

def anthropic_to_openai_tools(tools: list) -> list:
    """Convert Anthropic tool schema format to OpenAI function-calling format."""
    return [
        {
            "type": "function",
            "function": {
                "name": t["name"],
                "description": t.get("description", ""),
                "parameters": t.get("input_schema", {"type": "object", "properties": {}}),
            },
        }
        for t in tools
    ]


# ── Agent interaction ────────────────────────────────────────────────────────

def check_server(port: int) -> bool:
    try:
        r = requests.get(STATUS_URL.format(port=port), timeout=5)
        return r.status_code == 200
    except Exception:
        return False


def call_tool(port: int, tool_name: str, params: dict) -> dict:
    r = requests.post(
        TOOL_CALL_URL.format(port=port),
        json={"tool": tool_name, "params": params},
        timeout=60,
    )
    return r.json()


def run_task_with_agent(task: dict, port: int, api_key: str,
                        model: str = "claude-sonnet-4-6",
                        max_turns: int = 12) -> dict:
    """Drive Claude with a natural-language instruction and collect tool calls."""
    schema_r = requests.get(TOOL_SCHEMA_URL.format(port=port), timeout=5)
    tools = schema_r.json()

    status = requests.get(STATUS_URL.format(port=port), timeout=5).json()
    system_prompt = (
        "You are an expert software engineering assistant. "
        f"You are connected to an IntelliJ project called '{status.get('project', '?')}' "
        f"via a structured refactoring tool API on port {port}. "
        "Use find_symbol_by_name to locate symbols before operating on them. "
        "Use read_file to inspect source code before adding or modifying members. "
        "Use find_usages to check impact before renaming or deleting. "
        "Always use qualifiedName parameters instead of filePath+offset when possible. "
        "Only call the tools provided to you; do not invent tool names."
    )

    client = anthropic.Anthropic(api_key=api_key)
    messages = [{"role": "user", "content": task["description"]}]
    tool_calls_made = []
    turns = 0

    while turns < max_turns:
        response = client.messages.create(
            model=model,
            max_tokens=2048,
            system=system_prompt,
            tools=tools,
            messages=messages,
        )
        turns += 1

        tool_results = []
        for block in response.content:
            if block.type == "tool_use":
                result = call_tool(port, block.name, block.input)
                tool_calls_made.append({
                    "tool": block.name,
                    "params": block.input,
                    "result": result,
                })
                tool_results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": json.dumps(result),
                })

        messages.append({"role": "assistant", "content": response.content})

        if response.stop_reason != "tool_use" or not tool_results:
            break
        messages.append({"role": "user", "content": tool_results})

    return {"tool_calls": tool_calls_made, "turns": turns}


def run_task_with_agent_openai(task: dict, port: int, api_key: str,
                               model: str = "gpt-4o",
                               max_turns: int = 12,
                               base_url: str = None) -> dict:
    """Drive an OpenAI-compatible model (OpenAI, Groq, Gemini) with the structured IntelliJ tool API."""
    if not HAS_OPENAI:
        raise RuntimeError("openai package not installed. Run: pip install openai")

    schema_r = requests.get(TOOL_SCHEMA_URL.format(port=port), timeout=5)
    tools = anthropic_to_openai_tools(schema_r.json())

    status = requests.get(STATUS_URL.format(port=port), timeout=5).json()
    system_prompt = (
        "You are an expert software engineering assistant. "
        f"You are connected to an IntelliJ project called '{status.get('project', '?')}' "
        f"via a structured refactoring tool API on port {port}. "
        "Use find_symbol_by_name to locate symbols before operating on them. "
        "Use read_file to inspect source code before adding or modifying members. "
        "Use find_usages to check impact before renaming or deleting. "
        "Always use qualifiedName parameters instead of filePath+offset when possible. "
        "Only call the tools provided to you; do not invent tool names."
    )

    client = _openai_mod.OpenAI(api_key=api_key, base_url=base_url)
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": task["description"]},
    ]
    tool_calls_made = []
    turns = 0
    valid_tool_names = [t["function"]["name"] for t in tools]
    consecutive_tool_errors = 0

    while turns < max_turns:
        try:
            response = _chat_with_backoff(
                client,
                model=model,
                messages=messages,
                tools=tools,
                tool_choice="auto",
            )
        except _openai_mod.BadRequestError as e:
            # Some OpenAI-compatible providers (notably Groq) hard-reject a turn when the
            # model calls a tool not in the provided list. Recover by reminding the model of
            # the valid tools and retrying, rather than failing the whole task.
            if "tool" not in str(e).lower():
                raise
            consecutive_tool_errors += 1
            if consecutive_tool_errors > 3:
                raise
            turns += 1
            messages.append({
                "role": "user",
                "content": (
                    "Your previous response called a tool that is not available. "
                    "You may ONLY call these tools: " + ", ".join(valid_tool_names) + ". "
                    "Do not invent tool names; use only the provided structured tools."
                ),
            })
            continue
        consecutive_tool_errors = 0
        turns += 1

        msg = response.choices[0].message
        # Append assistant turn (with tool_calls if present)
        assistant_entry = {"role": "assistant", "content": msg.content}
        if msg.tool_calls:
            assistant_entry["tool_calls"] = [
                {
                    "id": tc.id,
                    "type": "function",
                    "function": {"name": tc.function.name, "arguments": tc.function.arguments},
                }
                for tc in msg.tool_calls
            ]
        messages.append(assistant_entry)

        if not msg.tool_calls:
            break

        for tc in msg.tool_calls:
            params = json.loads(tc.function.arguments)
            result = call_tool(port, tc.function.name, params)
            tool_calls_made.append({
                "tool": tc.function.name,
                "params": params,
                "result": result,
            })
            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": json.dumps(result),
            })

    return {"tool_calls": tool_calls_made, "turns": turns}


# ── Validation helpers ───────────────────────────────────────────────────────

def run_maven_compile(project_dir: Path) -> dict:
    """Compile the project. Returns {"ok": True} or {"ok": False, "error": "..."}."""
    try:
        mvn = "mvn.cmd" if sys.platform == "win32" else "mvn"
        result = subprocess.run(
            [mvn, "-Dcheckstyle.skip=true", "-Dspring-javaformat.skip=true", "compile", "-q", "--no-transfer-progress"],
            cwd=project_dir,
            capture_output=True,
            text=True,
            timeout=120,
        )
        if result.returncode == 0:
            return {"ok": True}
        err = ((result.stdout or "") + "\n" + (result.stderr or ""))[-4000:]
        return {"ok": False, "error": err}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def grep_in_java_sources(project_dir: Path, pattern: str) -> list:
    """Return matches (file:line: text) in non-comment .java source lines.

    Uses word-boundary matching so 'normalize' does not match 'normalizeInput'.
    """
    src_dir = project_dir / "src" / "main" / "java"
    regex = re.compile(r'\b' + re.escape(pattern) + r'\b')
    hits = []
    for java_file in sorted(src_dir.rglob("*.java")):
        try:
            for i, line in enumerate(java_file.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if stripped.startswith("*") or stripped.startswith("//"):
                    continue
                if regex.search(line):
                    hits.append(f"{java_file.name}:{i}: {stripped}")
        except Exception:
            pass
    return hits


def ensure_git_repo(project_dir: Path) -> None:
    """Initialize a standalone git repo in project_dir if not already present."""
    if not (project_dir / ".git").exists():
        subprocess.run(["git", "init"], cwd=project_dir, capture_output=True)
        subprocess.run(
            ["git", "config", "user.email", "benchmark@example.com"],
            cwd=project_dir, capture_output=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "Benchmark Runner"],
            cwd=project_dir, capture_output=True,
        )
        print(f"  [git] Initialized repo in {project_dir}")


def git_commit_all(project_dir: Path, message: str) -> str:
    """Stage all changes and create a commit. Returns the new HEAD SHA."""
    subprocess.run(["git", "add", "-A"], cwd=project_dir, capture_output=True)
    subprocess.run(
        ["git", "commit", "-m", message, "--allow-empty"],
        cwd=project_dir, capture_output=True,
    )
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=project_dir, capture_output=True, text=True,
    )
    return result.stdout.strip()


def git_reset_to_commit(project_dir: Path, sha: str) -> None:
    """Hard-reset the project back to a specific commit (for task isolation)."""
    subprocess.run(["git", "reset", "--hard", sha], cwd=project_dir, capture_output=True)
    subprocess.run(["git", "clean", "-fd"], cwd=project_dir, capture_output=True)


def run_refactoring_miner(
    project_dir: Path,
    rm_jar: str,
    before_sha: str,
    after_sha: str,
    expected_type: str,
) -> dict:
    """
    Run RefactoringMiner between two commits and check for the expected
    refactoring type. Returns {"passed": bool|None, "notes": [...]}.

    Download RefactoringMiner from:
      https://github.com/tsantalis/RefactoringMiner/releases
    Pass its path via --rm-jar.
    """
    if not rm_jar:
        return {
            "passed": None,
            "notes": ["RefactoringMiner: jar not configured (use --rm-jar to enable)"],
        }
    rm_jar_path = Path(rm_jar)
    if not rm_jar_path.exists():
        return {"passed": None, "notes": [f"RefactoringMiner: jar not found at {rm_jar}"]}

    rm_out = project_dir / "rm_output_tmp.json"
    try:
        result = subprocess.run(
            ["java", "-jar", str(rm_jar_path),
             "-bc", str(project_dir), before_sha, after_sha,
             "-json", str(rm_out)],
            capture_output=True, text=True, timeout=120,
        )
        if not rm_out.exists():
            return {
                "passed": False,
                "notes": [f"RefactoringMiner: no output produced. stderr: {result.stderr[:400]}"],
            }

        rm_data = json.loads(rm_out.read_text(encoding="utf-8"))
        found_types = []
        for commit in rm_data.get("commits", []):
            for ref in commit.get("refactorings", []):
                found_types.append(ref.get("type", ""))

        if expected_type in found_types:
            return {
                "passed": True,
                "notes": [
                    f"RefactoringMiner: detected '{expected_type}' "
                    f"(all types: {found_types})"
                ],
            }
        else:
            return {
                "passed": False,
                "notes": [
                    f"RefactoringMiner: expected '{expected_type}' "
                    f"but detected: {found_types or ['(none)']}"
                ],
            }
    except Exception as e:
        return {"passed": False, "notes": [f"RefactoringMiner error: {e}"]}
    finally:
        if rm_out.exists():
            rm_out.unlink()


# ── Main validation ──────────────────────────────────────────────────────────

def validate(
    task: dict,
    agent_result: dict,
    project_root: Path,
    before_sha: str = None,
    rm_jar: str = None,
) -> dict:
    """
    Multi-layer validation:
      1. Tool-call layer  — all mutating tools returned ok:true, expected tools called
      2. Compile layer    — Maven compile succeeds after changes
      3. Content layer    — file/symbol presence or absence on disk
      4. RM layer         — RefactoringMiner confirms expected refactoring type
    """
    passed = True
    notes = []
    validation = task.get("validation", {})
    vtype = validation.get("type", "")

    # ── Layer 1: tool-call checks ────────────────────────────────────────────
    READ_ONLY_TOOLS = {
        "find_symbol_by_name", "find_symbol", "list_symbols", "read_file", "find_usages",
    }
    failed_calls = [
        c for c in agent_result["tool_calls"]
        if c["tool"] not in READ_ONLY_TOOLS and not c["result"].get("ok", False)
    ]
    for fc in failed_calls:
        passed = False
        notes.append(
            f"Tool '{fc['tool']}' failed: {fc['result'].get('error', fc['result'])}"
        )

    # The `operations` list is the CANONICAL tool sequence (it is what the
    # scripted direct-runner executes). When grading an *agent*, require only the
    # MUTATING operations: read-only/exploratory tools (find_symbol_by_name,
    # read_file, find_usages, ...) describe one valid path to the result, but an
    # agent may reach the same outcome without them (e.g. calling rename_symbol
    # directly by qualified name). Correctness is graded by the compile and
    # disk-state layers below, so demanding a specific exploration strategy would
    # penalise efficiency rather than incorrectness. (Disclosed in the report's
    # evaluation methodology; the scripted runner still satisfies this because it
    # calls the mutating tools too.)
    expected_tools = [op["tool"] for op in task.get("operations", [])]
    required_tools = [t for t in expected_tools if t not in READ_ONLY_TOOLS]
    actual_tools   = [c["tool"] for c in agent_result["tool_calls"]]
    missing = [t for t in required_tools if t not in actual_tools]
    if missing:
        passed = False
        notes.append(f"Required (mutating) tools not called: {missing}")
    else:
        notes.append(f"Required tools called: {required_tools}")

    # Non-compile validation types — handle here and return early
    if vtype == "find_usages_non_empty":
        find_calls = [c for c in agent_result["tool_calls"] if c["tool"] == "find_usages"]
        if not find_calls:
            passed = False
            notes.append("find_usages was not called")
        else:
            last = find_calls[-1]["result"]
            count = last.get("count", 0) if isinstance(last, dict) else 0
            if count <= 0:
                passed = False
                notes.append(f"find_usages returned no usages (count={count})")
            else:
                notes.append(f"find_usages returned {count} usage(s)")
        return {"passed": passed, "notes": notes, "validation_type": vtype}

    if vtype == "tool_called":
        expected = validation.get("expectedTool", "")
        if expected and expected in actual_tools:
            notes.append(f"Tool '{expected}' was called")
        elif expected:
            passed = False
            notes.append(f"Tool '{expected}' was NOT called")
        return {"passed": passed, "notes": notes, "validation_type": vtype}

    if not vtype.startswith("compile"):
        return {"passed": passed, "notes": notes, "validation_type": vtype}

    # ── Layer 2: compile check ───────────────────────────────────────────────
    project_name = task.get("project", "")
    project_dir  = (project_root / project_name) if project_name else None

    if project_dir and project_dir.exists():
        cr = run_maven_compile(project_dir)
        if cr["ok"]:
            notes.append("Compile: PASS")
        else:
            passed = False
            err_snippet = cr["error"][-800:].replace("\n", " ")
            notes.append(f"Compile: FAIL -- {err_snippet}")
    else:
        notes.append(f"Compile: skipped (project dir not found: {project_dir})")
        project_dir = None

    # ── Layer 3: disk-state checks ───────────────────────────────────────────
    if project_dir:
        if vtype == "compile_and_file_exists":
            expected_file = validation.get("expectedFile", "")
            if expected_file:
                target = project_dir / "src" / "main" / "java" / expected_file
                if target.exists():
                    notes.append(f"File exists on disk: {expected_file}")
                else:
                    passed = False
                    notes.append(f"File NOT found on disk: {expected_file}")

        elif vtype == "compile_and_no_reference":
            deleted_sym = validation.get("deletedSymbol", "")
            if deleted_sym:
                hits = grep_in_java_sources(project_dir, deleted_sym)
                if hits:
                    passed = False
                    notes.append(
                        f"Symbol '{deleted_sym}' still present in sources: "
                        f"{hits[:3]}"
                    )
                else:
                    notes.append(
                        f"Symbol '{deleted_sym}' absent from all source files"
                    )

        elif vtype == "compile_and_symbol_exists":
            expected_sym = validation.get("expectedSymbol", "")
            if expected_sym:
                name = expected_sym.split("#")[-1] if "#" in expected_sym else expected_sym
                hits = grep_in_java_sources(project_dir, name)
                if hits:
                    notes.append(f"Symbol '{name}' found in sources: {hits[0]}")
                else:
                    passed = False
                    notes.append(f"Symbol '{name}' NOT found in source files")

            # Cross-file check: verify old name is gone from ALL files.
            # Catches text-edit agents that only edit the declaration file.
            if validation.get("crossFileCheck"):
                old_name = None
                for op in task.get("operations", []):
                    if op.get("tool") == "rename_symbol":
                        qn = op.get("params", {}).get("qualifiedName", "")
                        old_name = qn.split("#")[-1] if "#" in qn else None
                        break
                if old_name:
                    stale = grep_in_java_sources(project_dir, old_name)
                    if stale:
                        passed = False
                        notes.append(
                            f"Cross-file: old name '{old_name}' still in sources: "
                            f"{stale[:5]}"
                        )
                    else:
                        notes.append(
                            f"Cross-file: old name '{old_name}' absent from all source files"
                        )

        # ── Layer 4: RefactoringMiner ────────────────────────────────────────
        if vtype == "compile_and_refactoringminer":
            expected_type = validation.get("expectedRefactoringType", "")
            if before_sha and project_dir:
                # Get the after-task commit SHA
                r = subprocess.run(
                    ["git", "rev-parse", "HEAD"],
                    cwd=project_dir, capture_output=True, text=True,
                )
                after_sha = r.stdout.strip()
                rm_result = run_refactoring_miner(
                    project_dir, rm_jar, before_sha, after_sha, expected_type
                )
            else:
                rm_result = run_refactoring_miner(
                    project_dir, rm_jar, "", "", expected_type
                )
            notes.extend(rm_result["notes"])
            if rm_result["passed"] is False:
                passed = False

    return {"passed": passed, "notes": notes, "validation_type": vtype}


# ── Output ───────────────────────────────────────────────────────────────────

def print_summary(results: list) -> None:
    passed = sum(1 for r in results if r["status"] == "PASS")
    print(f"\n{'='*60}")
    print(f"Results: {passed}/{len(results)} tasks passed")
    print(f"{'='*60}")
    for r in results:
        icon = "PASS" if r["status"] == "PASS" else "FAIL"
        print(f"  [{icon}] [{r['id']}]  ({r['turns']} turns, {r['elapsed_s']}s)")
        for note in r["validation"]["notes"]:
            print(f"      * {note}")


# ── Entry point ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Run structured-refactoring-agent benchmarks"
    )
    parser.add_argument("--tasks",        default="benchmarks/tasks.json")
    parser.add_argument("--agent-port",   type=int, default=6473)
    parser.add_argument("--provider",     default="anthropic",
                        choices=["anthropic", "openai", "groq", "gemini", "ollama"],
                        help="LLM provider (anthropic, openai, groq, gemini, ollama). "
                             "groq/gemini use their OpenAI-compatible endpoints; "
                             "ollama drives a local model at localhost:11434 (no rate limit, no key).")
    parser.add_argument("--api-key",      default="",
                        help="API key (defaults to ANTHROPIC_API_KEY or OPENAI_API_KEY env var)")
    parser.add_argument("--model",        default="",
                        help="Model name (default: claude-sonnet-4-6 for anthropic, gpt-4o for openai)")
    parser.add_argument("--max-turns",    type=int, default=12)
    parser.add_argument("--out",          default="results/run.json")
    parser.add_argument("--task-id",      help="Run a single task by ID")
    parser.add_argument(
        "--projects-dir", default="benchmarks/projects",
        help="Root directory containing benchmark project subdirectories",
    )
    parser.add_argument(
        "--rm-jar", default="",
        help="Path to RefactoringMiner JAR (enables refactoring type classification)",
    )
    args = parser.parse_args()

    # Resolve API key, default model, and (for OpenAI-compatible providers) base URL.
    PROVIDER_CONFIG = {
        "anthropic": {"env": "ANTHROPIC_API_KEY", "model": "claude-sonnet-4-6",     "base_url": None},
        "openai":    {"env": "OPENAI_API_KEY",    "model": "gpt-4o",                 "base_url": None},
        "groq":      {"env": "GROQ_API_KEY",      "model": "openai/gpt-oss-120b",
                      "base_url": "https://api.groq.com/openai/v1"},
        "gemini":    {"env": "GEMINI_API_KEY",    "model": "gemini-2.0-flash",
                      "base_url": "https://generativelanguage.googleapis.com/v1beta/openai/"},
        "ollama":    {"env": "OLLAMA_API_KEY",    "model": "qwen2.5-coder:7b",
                      "base_url": "http://localhost:11434/v1"},
    }
    cfg = PROVIDER_CONFIG[args.provider]
    api_key = args.api_key or os.environ.get(cfg["env"], "")
    if not api_key and args.provider == "gemini":
        api_key = os.environ.get("GOOGLE_API_KEY", "")
    if not api_key and args.provider == "ollama":
        api_key = "ollama"  # local server ignores the key, but the OpenAI client requires a non-empty value
    model    = args.model or cfg["model"]
    base_url = cfg["base_url"]

    if not api_key:
        print(f"ERROR: No API key. Set {cfg['env']} (in .env or environment) or use --api-key")
        sys.exit(1)

    if not check_server(args.agent_port):
        print(f"ERROR: No agent server on port {args.agent_port}.")
        print("       Open IntelliJ with the plugin installed and a project loaded.")
        sys.exit(1)

    tasks = json.loads(Path(args.tasks).read_text(encoding="utf-8"))
    if args.task_id:
        tasks = [t for t in tasks if t["id"] == args.task_id]
        if not tasks:
            print(f"Task '{args.task_id}' not found.")
            sys.exit(1)

    projects_root = Path(args.projects_dir)
    rm_jar = args.rm_jar or None

    # Ensure every project used by the task list has a standalone git repo
    # (required for RefactoringMiner's between-commit analysis)
    seen_projects = set()
    for task in tasks:
        pname = task.get("project", "")
        if pname and pname not in seen_projects:
            pdir = projects_root / pname
            if pdir.exists():
                ensure_git_repo(pdir)
            seen_projects.add(pname)

    results = []
    for task in tasks:
        print(f"\nRunning [{task['id']}]: {task['description'][:70]}...")

        # Commit the current (pre-task) state as the baseline snapshot
        before_sha = None
        pname = task.get("project", "")
        project_dir = (projects_root / pname) if pname else None
        if project_dir and project_dir.exists():
            before_sha = git_commit_all(project_dir, f"baseline-before-{task['id']}")

        t0 = time.time()
        try:
            if args.provider == "anthropic":
                agent_result = run_task_with_agent(
                    task, args.agent_port, api_key,
                    model=model, max_turns=args.max_turns,
                )
            else:
                agent_result = run_task_with_agent_openai(
                    task, args.agent_port, api_key,
                    model=model, max_turns=args.max_turns,
                    base_url=base_url,
                )

            # Commit the post-task state so RefactoringMiner can diff the two commits
            if project_dir and project_dir.exists():
                git_commit_all(project_dir, f"after-{task['id']}")

            validation = validate(
                task, agent_result, projects_root,
                before_sha=before_sha, rm_jar=rm_jar,
            )
            status = "PASS" if validation["passed"] else "FAIL"

        except Exception as e:
            agent_result = {"tool_calls": [], "turns": 0}
            validation   = {"passed": False, "notes": [str(e)], "validation_type": "error"}
            status = "ERROR"

        elapsed = round(time.time() - t0, 2)
        print(f"  -> {status} in {elapsed}s ({agent_result['turns']} turns)")
        results.append({
            "id": task["id"],
            "description": task["description"],
            "status": status,
            "elapsed_s": elapsed,
            "turns": agent_result["turns"],
            "tool_calls": agent_result["tool_calls"],
            "validation": validation,
        })

        # Reset project to pre-task state so each task starts from clean baseline.
        # IntelliJ detects the file changes via its VFS watcher and reindexes.
        if project_dir and before_sha:
            git_reset_to_commit(project_dir, before_sha)
            time.sleep(2)  # Allow IntelliJ VFS watcher to pick up the revert

    print_summary(results)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    output = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "agent": "structured",
        "provider": args.provider,
        "model": model,
        "tasks": results,
    }
    Path(args.out).write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(f"\nFull results written to {args.out}")


if __name__ == "__main__":
    main()
