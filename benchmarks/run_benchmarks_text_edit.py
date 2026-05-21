#!/usr/bin/env python3
"""
BASELINE: Text-edit refactoring agent for comparison against the structured agent.

This agent gives Claude ONLY raw file I/O tools — no IntelliJ structural APIs,
no AST awareness, no reference index.  It finds and modifies files by reading
their text content and writing replacements.

Run on the same tasks.json and compare compile pass-rates against run_benchmarks.py.

Usage:
    python benchmarks/run_benchmarks_text_edit.py \
        --tasks benchmarks/tasks.json \
        --api-key $ANTHROPIC_API_KEY \
        --projects-dir benchmarks/projects \
        --out results/text-edit-run.json

Requirements:
    pip install anthropic
"""

import json
import os
import re
import sys
import time
import argparse
import subprocess
from pathlib import Path

import anthropic

# Load .env from repo root if present
_env = Path(__file__).parent.parent / ".env"
if _env.exists():
    for line in _env.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())


# ── File-I/O tools for the text-edit agent ──────────────────────────────────

TEXT_EDIT_TOOLS = [
    {
        "name": "list_java_files",
        "description": (
            "List all .java source files under a project directory. "
            "Returns a list of absolute file paths."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "projectDir": {
                    "type": "string",
                    "description": "Absolute path to the project root directory.",
                }
            },
            "required": ["projectDir"],
        },
    },
    {
        "name": "read_file",
        "description": "Read the full text content of a file.",
        "input_schema": {
            "type": "object",
            "properties": {
                "filePath": {"type": "string", "description": "Absolute path to the file."}
            },
            "required": ["filePath"],
        },
    },
    {
        "name": "write_file",
        "description": (
            "Overwrite a file with new content. "
            "Use this to apply text edits after reading the file."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "filePath": {"type": "string", "description": "Absolute path to the file."},
                "content":  {"type": "string", "description": "Full new file content."},
            },
            "required": ["filePath", "content"],
        },
    },
    {
        "name": "create_file",
        "description": "Create a new file with the given content.",
        "input_schema": {
            "type": "object",
            "properties": {
                "filePath": {"type": "string", "description": "Absolute path for the new file."},
                "content":  {"type": "string", "description": "Content for the new file."},
            },
            "required": ["filePath", "content"],
        },
    },
    {
        "name": "delete_file",
        "description": "Delete a file from disk.",
        "input_schema": {
            "type": "object",
            "properties": {
                "filePath": {"type": "string", "description": "Absolute path to the file."}
            },
            "required": ["filePath"],
        },
    },
]


def dispatch_tool(name: str, params: dict, project_dir: Path) -> dict:
    """Execute a text-edit tool call and return a result dict."""
    try:
        if name == "list_java_files":
            src = project_dir / "src" / "main" / "java"
            files = sorted(str(p) for p in src.rglob("*.java"))
            return {"ok": True, "files": files}

        elif name == "read_file":
            p = Path(params["filePath"])
            if not p.exists():
                return {"ok": False, "error": f"File not found: {p}"}
            return {"ok": True, "content": p.read_text(encoding="utf-8")}

        elif name == "write_file":
            p = Path(params["filePath"])
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(params["content"], encoding="utf-8")
            return {"ok": True, "message": f"Written {p.name}"}

        elif name == "create_file":
            p = Path(params["filePath"])
            if p.exists():
                return {"ok": False, "error": f"File already exists: {p}"}
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(params["content"], encoding="utf-8")
            return {"ok": True, "message": f"Created {p.name}"}

        elif name == "delete_file":
            p = Path(params["filePath"])
            if not p.exists():
                return {"ok": False, "error": f"File not found: {p}"}
            p.unlink()
            return {"ok": True, "message": f"Deleted {p.name}"}

        else:
            return {"ok": False, "error": f"Unknown tool: {name}"}

    except Exception as e:
        return {"ok": False, "error": str(e)}


def run_task_text_edit(task: dict, project_dir: Path, api_key: str,
                       model: str = "claude-sonnet-4-6",
                       max_turns: int = 12) -> dict:
    """Run the text-edit agent on a single task."""
    client = anthropic.Anthropic(api_key=api_key)

    system_prompt = (
        "You are a software engineering assistant performing code refactoring "
        "using ONLY text-based file operations. "
        f"The project is located at: {project_dir}\n\n"
        "You have no IDE support, no AST parser, and no reference index. "
        "You must read files, understand their content, and write updated versions. "
        "Be thorough: always check ALL files in the project for cross-file references "
        "that may need updating (imports, usages, call sites). "
        "Use list_java_files to discover all source files before making changes."
    )

    messages = [{"role": "user", "content": task["description"]}]
    tool_calls_made = []
    turns = 0

    while turns < max_turns:
        response = client.messages.create(
            model=model,
            max_tokens=4096,
            system=system_prompt,
            tools=TEXT_EDIT_TOOLS,
            messages=messages,
        )
        turns += 1

        tool_results = []
        for block in response.content:
            if block.type == "tool_use":
                result = dispatch_tool(block.name, block.input, project_dir)
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


# ── Validation (same logic as structured runner) ─────────────────────────────

def run_maven_compile(project_dir: Path) -> dict:
    try:
        mvn = "mvn.cmd" if sys.platform == "win32" else "mvn"
        result = subprocess.run(
            [mvn, "compile", "-q", "--no-transfer-progress"],
            cwd=project_dir, capture_output=True, text=True, timeout=120,
        )
        if result.returncode == 0:
            return {"ok": True}
        err = (result.stderr or result.stdout or "")[-2000:]
        return {"ok": False, "error": err}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def grep_in_java_sources(project_dir: Path, pattern: str) -> list:
    """Match pattern in non-comment Java source lines.

    Uses word-boundary matching so 'normalize' does not match 'normalizeInput'.
    """
    src_dir = project_dir / "src" / "main" / "java"
    regex = re.compile(r'\b' + re.escape(pattern) + r'\b')
    hits = []
    for java_file in sorted(src_dir.rglob("*.java")):
        try:
            for i, line in enumerate(java_file.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                # Skip Javadoc and single-line comments
                if stripped.startswith("*") or stripped.startswith("//"):
                    continue
                if regex.search(line):
                    hits.append(f"{java_file.name}:{i}: {stripped}")
        except Exception:
            pass
    return hits


def validate(task: dict, agent_result: dict, project_dir: Path) -> dict:
    passed = True
    notes = []
    validation = task.get("validation", {})
    vtype = validation.get("type", "")

    # Tool-call layer: mutating tools must succeed
    READ_ONLY = {"list_java_files", "read_file"}
    failed = [
        c for c in agent_result["tool_calls"]
        if c["tool"] not in READ_ONLY and not c["result"].get("ok", False)
    ]
    for fc in failed:
        passed = False
        notes.append(f"Tool '{fc['tool']}' failed: {fc['result'].get('error', fc['result'])}")

    if vtype.startswith("compile"):
        # Compile layer
        cr = run_maven_compile(project_dir)
        if cr["ok"]:
            notes.append("Compile: PASS")
        else:
            passed = False
            snippet = cr["error"][:300].replace("\n", " ")
            notes.append(f"Compile: FAIL -- {snippet}")

        # Content layer
        if vtype == "compile_and_file_exists":
            expected_file = validation.get("expectedFile", "")
            if expected_file:
                target = project_dir / "src" / "main" / "java" / expected_file
                if target.exists():
                    notes.append(f"File exists: {expected_file}")
                else:
                    passed = False
                    notes.append(f"File NOT found: {expected_file}")

        elif vtype == "compile_and_no_reference":
            deleted_sym = validation.get("deletedSymbol", "")
            if deleted_sym:
                hits = grep_in_java_sources(project_dir, deleted_sym)
                if hits:
                    passed = False
                    notes.append(f"Symbol '{deleted_sym}' still present: {hits[:3]}")
                else:
                    notes.append(f"Symbol '{deleted_sym}' absent from all source files")

        elif vtype == "compile_and_symbol_exists":
            expected_sym = validation.get("expectedSymbol", "")
            if expected_sym:
                name = expected_sym.split("#")[-1] if "#" in expected_sym else expected_sym
                hits = grep_in_java_sources(project_dir, name)
                if hits:
                    notes.append(f"Symbol '{name}' found: {hits[0]}")
                else:
                    passed = False
                    notes.append(f"Symbol '{name}' NOT found in source files")

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

        elif vtype == "compile_and_refactoringminer":
            # Text-edit agent has no structural classification
            notes.append("RefactoringMiner: N/A for text-edit agent")

    return {"passed": passed, "notes": notes, "validation_type": vtype}


def print_summary(results: list) -> None:
    passed = sum(1 for r in results if r["status"] == "PASS")
    print(f"\n{'='*60}")
    print(f"Text-Edit Agent Results: {passed}/{len(results)} tasks passed")
    print(f"{'='*60}")
    for r in results:
        icon = "PASS" if r["status"] == "PASS" else "FAIL"
        print(f"  [{icon}] [{r['id']}]  ({r['turns']} turns, {r['elapsed_s']}s)")
        for note in r["validation"]["notes"]:
            print(f"      * {note}")


# ── Git helpers (same as structured runner) ──────────────────────────────────

def ensure_git_repo(project_dir: Path) -> None:
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


def git_reset_to_commit(project_dir: Path, sha: str) -> None:
    """Reset the project back to a specific commit (hard reset)."""
    subprocess.run(["git", "reset", "--hard", sha], cwd=project_dir, capture_output=True)
    subprocess.run(["git", "clean", "-fd"], cwd=project_dir, capture_output=True)


def git_commit_all(project_dir: Path, message: str) -> str:
    subprocess.run(["git", "add", "-A"], cwd=project_dir, capture_output=True)
    subprocess.run(
        ["git", "commit", "-m", message, "--allow-empty"],
        cwd=project_dir, capture_output=True,
    )
    r = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=project_dir, capture_output=True, text=True,
    )
    return r.stdout.strip()


# ── Entry point ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Run TEXT-EDIT baseline agent benchmarks (no IntelliJ/AST)"
    )
    parser.add_argument("--tasks",        default="benchmarks/tasks.json")
    parser.add_argument("--api-key",      default=os.environ.get("ANTHROPIC_API_KEY", ""), help="Anthropic API key (defaults to ANTHROPIC_API_KEY env var or .env)")
    parser.add_argument("--model",        default="claude-sonnet-4-6")
    parser.add_argument("--max-turns",    type=int, default=12)
    parser.add_argument("--out",          default="results/text-edit-run.json")
    parser.add_argument("--task-id",      help="Run a single task by ID")
    parser.add_argument(
        "--projects-dir", default="benchmarks/projects",
        help="Root directory containing benchmark project subdirectories",
    )
    args = parser.parse_args()

    if not args.api_key:
        print("ERROR: No API key. Set ANTHROPIC_API_KEY, use --api-key, or add it to .env")
        sys.exit(1)

    tasks = json.loads(Path(args.tasks).read_text(encoding="utf-8"))
    if args.task_id:
        tasks = [t for t in tasks if t["id"] == args.task_id]
        if not tasks:
            print(f"Task '{args.task_id}' not found.")
            sys.exit(1)

    projects_root = Path(args.projects_dir)

    # One git repo per project for state isolation between tasks
    seen = set()
    for task in tasks:
        pname = task.get("project", "")
        if pname and pname not in seen:
            pdir = projects_root / pname
            if pdir.exists():
                ensure_git_repo(pdir)
                # Commit the current clean state as the absolute baseline
                git_commit_all(pdir, "text-edit-baseline")
            seen.add(pname)

    results = []
    for task in tasks:
        print(f"\nRunning [{task['id']}]: {task['description'][:70]}...")

        pname = task.get("project", "")
        project_dir = (projects_root / pname) if pname else None
        if project_dir is None or not project_dir.exists():
            print(f"  ERROR: project dir not found for task {task['id']}")
            continue

        # Snapshot state before this task so we can reset between tasks
        before_sha = git_commit_all(project_dir, f"baseline-before-{task['id']}")

        t0 = time.time()
        try:
            agent_result = run_task_text_edit(
                task, project_dir, args.api_key,
                model=args.model, max_turns=args.max_turns,
            )
            validation = validate(task, agent_result, project_dir)
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

        # Reset project to the pre-task state so each task starts clean
        git_reset_to_commit(project_dir, before_sha)

    print_summary(results)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    output = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "agent": "text-edit",
        "tasks": results,
    }
    Path(args.out).write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(f"\nFull results written to {args.out}")


if __name__ == "__main__":
    main()
