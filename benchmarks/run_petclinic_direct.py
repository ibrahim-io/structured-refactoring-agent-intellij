#!/usr/bin/env python3
"""
No-API direct runner for the spring-petclinic structured tool benchmark.

This runner executes the operations already listed in tasks_petclinic.json
directly against the IntelliJ plugin's localhost tool API. It does not import
Anthropic, read .env, or make any model calls. Its purpose is to validate the
structured IntelliJ tool layer when API credits are unavailable.

Usage:
    python benchmarks/run_petclinic_direct.py ^
        --tasks benchmarks/tasks_petclinic.json ^
        --projects-dir benchmarks/projects ^
        --agent-port 6473 ^
        --out results/structured-petclinic-direct-1.json
"""

import argparse
import copy
import json
import re
import subprocess
import sys
import time
from pathlib import Path

import requests


TOOL_CALL_URL = "http://127.0.0.1:{port}/tools"
STATUS_URL = "http://127.0.0.1:{port}/status"


def check_server(port: int) -> tuple[bool, dict]:
    try:
        response = requests.get(STATUS_URL.format(port=port), timeout=5)
        if response.status_code != 200:
            return False, {"error": f"status returned HTTP {response.status_code}"}
        return True, response.json()
    except Exception as exc:
        return False, {"error": str(exc)}


def call_tool(port: int, tool_name: str, params: dict) -> dict:
    response = requests.post(
        TOOL_CALL_URL.format(port=port),
        json={"tool": tool_name, "params": params},
        timeout=60,
    )
    return response.json()


def run_maven_compile(project_dir: Path) -> dict:
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
        combined = (result.stdout or "") + "\n" + (result.stderr or "")
        error_lines = [l for l in combined.splitlines() if "[ERROR]" in l or "error:" in l.lower()]
        summary = "\n".join(error_lines[:30]) if error_lines else combined[-2000:]
        return {"ok": False, "error": summary}
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def grep_in_java_sources(project_dir: Path, pattern: str) -> list[str]:
    src_dir = project_dir / "src" / "main" / "java"
    regex = re.compile(r"\b" + re.escape(pattern) + r"\b")
    hits = []
    for java_file in sorted(src_dir.rglob("*.java")):
        try:
            for line_no, line in enumerate(
                java_file.read_text(encoding="utf-8").splitlines(), 1
            ):
                stripped = line.strip()
                if stripped.startswith("*") or stripped.startswith("//"):
                    continue
                if regex.search(line):
                    hits.append(f"{java_file.name}:{line_no}: {stripped}")
        except Exception:
            pass
    return hits


def ensure_git_repo(project_dir: Path) -> None:
    if (project_dir / ".git").exists():
        return
    subprocess.run(["git", "init"], cwd=project_dir, capture_output=True)
    subprocess.run(
        ["git", "config", "user.email", "benchmark@example.com"],
        cwd=project_dir,
        capture_output=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "Benchmark Runner"],
        cwd=project_dir,
        capture_output=True,
    )


def git_commit_all(project_dir: Path, message: str) -> str:
    subprocess.run(["git", "add", "-A"], cwd=project_dir, capture_output=True)
    subprocess.run(
        ["git", "commit", "-m", message, "--allow-empty"],
        cwd=project_dir,
        capture_output=True,
    )
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=project_dir,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def git_reset_to_commit(project_dir: Path, sha: str) -> None:
    subprocess.run(["git", "reset", "--hard", sha], cwd=project_dir, capture_output=True)
    subprocess.run(["git", "clean", "-fd"], cwd=project_dir, capture_output=True)


def run_refactoring_miner(
    project_dir: Path,
    rm_jar: str | None,
    before_sha: str | None,
    expected_type: str,
) -> dict:
    if not rm_jar:
        return {
            "passed": None,
            "notes": ["RefactoringMiner: jar not configured (use --rm-jar to enable)"],
        }

    rm_jar_path = Path(rm_jar)
    if not rm_jar_path.exists():
        return {"passed": None, "notes": [f"RefactoringMiner: jar not found at {rm_jar}"]}

    after_result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=project_dir,
        capture_output=True,
        text=True,
    )
    after_sha = after_result.stdout.strip()
    if not before_sha or not after_sha:
        return {"passed": False, "notes": ["RefactoringMiner: missing before/after SHA"]}

    rm_out = project_dir / "rm_output_tmp.json"
    try:
        result = subprocess.run(
            [
                "java",
                "-jar",
                str(rm_jar_path),
                "-bc",
                str(project_dir),
                before_sha,
                after_sha,
                "-json",
                str(rm_out),
            ],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if not rm_out.exists():
            return {
                "passed": False,
                "notes": [f"RefactoringMiner: no output produced. stderr: {result.stderr[:400]}"],
            }

        rm_data = json.loads(rm_out.read_text(encoding="utf-8"))
        found_types = [
            ref.get("type", "")
            for commit in rm_data.get("commits", [])
            for ref in commit.get("refactorings", [])
        ]
        if expected_type in found_types:
            return {
                "passed": True,
                "notes": [f"RefactoringMiner: detected '{expected_type}'"],
            }
        return {
            "passed": False,
            "notes": [
                f"RefactoringMiner: expected '{expected_type}' but detected: "
                f"{found_types or ['(none)']}"
            ],
        }
    except Exception as exc:
        return {"passed": False, "notes": [f"RefactoringMiner error: {exc}"]}
    finally:
        if rm_out.exists():
            rm_out.unlink()


def validate(
    task: dict,
    agent_result: dict,
    project_root: Path,
    before_sha: str | None = None,
    rm_jar: str | None = None,
) -> dict:
    passed = True
    notes = []
    validation = task.get("validation", {})
    vtype = validation.get("type", "")

    read_only_tools = {
        "find_symbol_by_name",
        "find_symbol",
        "list_symbols",
        "read_file",
        "find_usages",
    }
    failed_calls = [
        call
        for call in agent_result["tool_calls"]
        if call["tool"] not in read_only_tools and not call["result"].get("ok", False)
    ]
    for call in failed_calls:
        passed = False
        notes.append(f"Tool '{call['tool']}' failed: {call['result'].get('error', call['result'])}")

    expected_tools = [op["tool"] for op in task.get("operations", [])]
    actual_tools = [call["tool"] for call in agent_result["tool_calls"]]
    missing_tools = [tool for tool in expected_tools if tool not in actual_tools]
    if missing_tools:
        passed = False
        notes.append(f"Expected tools not called: {missing_tools}")
    else:
        notes.append(f"All expected tools called: {expected_tools}")

    if vtype == "find_usages_non_empty":
        find_calls = [call for call in agent_result["tool_calls"] if call["tool"] == "find_usages"]
        if not find_calls:
            passed = False
            notes.append("find_usages was not called")
        else:
            count = find_calls[-1]["result"].get("count", 0)
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

    project_name = task.get("project", "")
    project_dir = project_root / project_name if project_name else None
    if project_dir and project_dir.exists():
        compile_result = run_maven_compile(project_dir)
        if compile_result["ok"]:
            notes.append("Compile: PASS")
        else:
            passed = False
            err_snippet = compile_result["error"][-800:].replace("\n", " ")
            notes.append(f"Compile: FAIL -- {err_snippet}")
    else:
        project_dir = None
        passed = False
        notes.append(f"Compile: skipped (project dir not found: {project_dir})")

    if project_dir:
        if vtype == "compile_and_file_exists":
            expected_file = validation.get("expectedFile", "")
            target = project_dir / "src" / "main" / "java" / expected_file
            if expected_file and target.exists():
                notes.append(f"File exists on disk: {expected_file}")
            else:
                passed = False
                notes.append(f"File NOT found on disk: {expected_file}")

        elif vtype == "compile_and_no_reference":
            deleted_symbol = validation.get("deletedSymbol", "")
            hits = grep_in_java_sources(project_dir, deleted_symbol) if deleted_symbol else []
            if hits:
                passed = False
                notes.append(f"Symbol '{deleted_symbol}' still present in sources: {hits[:3]}")
            elif deleted_symbol:
                notes.append(f"Symbol '{deleted_symbol}' absent from all source files")

        elif vtype == "compile_and_symbol_exists":
            expected_symbol = validation.get("expectedSymbol", "")
            name = expected_symbol.split("#")[-1] if "#" in expected_symbol else expected_symbol
            hits = grep_in_java_sources(project_dir, name) if name else []
            if hits:
                notes.append(f"Symbol '{name}' found in sources: {hits[0]}")
            else:
                passed = False
                notes.append(f"Symbol '{name}' NOT found in source files")

            surviving = validation.get("survivingSymbol", "")
            if surviving:
                surviving_name = surviving.split("#")[-1] if "#" in surviving else surviving.rsplit(".", 1)[-1]
                surviving_hits = grep_in_java_sources(project_dir, surviving_name) if surviving_name else []
                if surviving_hits:
                    notes.append(f"Surviving symbol '{surviving_name}' still present (good): {surviving_hits[0]}")
                else:
                    passed = False
                    notes.append(f"Surviving symbol '{surviving_name}' was incorrectly renamed (overload collision)")

        elif vtype == "compile_and_refactoringminer":
            expected_type = validation.get("expectedRefactoringType", "")
            rm_result = run_refactoring_miner(project_dir, rm_jar, before_sha, expected_type)
            notes.extend(rm_result["notes"])
            if rm_result["passed"] is False:
                passed = False

    return {"passed": passed, "notes": notes, "validation_type": vtype}


def replace_placeholders(value, context: dict):
    if isinstance(value, str):
        if value in {"__resolved__", "__resolved_from_find_symbol__"}:
            return context.get("last_symbol_file_path", value)
        return value
    if isinstance(value, dict):
        return {key: replace_placeholders(val, context) for key, val in value.items()}
    if isinstance(value, list):
        return [replace_placeholders(item, context) for item in value]
    return value


def run_task_direct(task: dict, port: int) -> dict:
    context = {}
    tool_calls = []

    for operation in task.get("operations", []):
        tool_name = operation["tool"]
        params = replace_placeholders(copy.deepcopy(operation.get("params", {})), context)
        result = call_tool(port, tool_name, params)
        tool_calls.append({"tool": tool_name, "params": params, "result": result})

        if tool_name == "find_symbol_by_name" and isinstance(result, dict):
            file_path = result.get("filePath")
            if file_path:
                context["last_symbol_file_path"] = file_path

    return {"tool_calls": tool_calls, "turns": 0}


def print_summary(results: list[dict]) -> None:
    passed = sum(1 for result in results if result["status"] == "PASS")
    print(f"\n{'=' * 60}")
    print(f"No-API direct results: {passed}/{len(results)} tasks passed")
    print(f"{'=' * 60}")
    for result in results:
        print(f"  [{result['status']}] [{result['id']}] ({result['elapsed_s']}s)")
        for note in result["validation"]["notes"]:
            print(f"      * {note}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run spring-petclinic tasks directly against the IntelliJ tool API without Anthropic."
    )
    parser.add_argument("--tasks", default="benchmarks/tasks_petclinic.json")
    parser.add_argument("--agent-port", type=int, default=6473)
    parser.add_argument("--projects-dir", default="benchmarks/projects")
    parser.add_argument("--out", default="results/structured-petclinic-direct-1.json")
    parser.add_argument("--task-id", help="Run a single task by ID")
    parser.add_argument(
        "--rm-jar",
        default="",
        help="Path to RefactoringMiner JAR (optional; enables refactoring type classification)",
    )
    parser.add_argument(
        "--allow-project-mismatch",
        action="store_true",
        help="Run even if /status reports a project other than spring-petclinic.",
    )
    args = parser.parse_args()

    server_ok, status = check_server(args.agent_port)
    if not server_ok:
        print(f"ERROR: No agent server on port {args.agent_port}: {status.get('error')}")
        print("       Launch IntelliJ with BENCHMARK_PROJECT=spring-petclinic first.")
        sys.exit(1)

    project_name = status.get("project", "")
    if project_name != "spring-petclinic":
        print(f"ERROR: Agent server reports project '{project_name}', expected 'spring-petclinic'.")
        print("       Close the current sandbox and relaunch with:")
        print('       $env:BENCHMARK_PROJECT = "spring-petclinic"')
        print("       .\\gradlew.bat runIde")
        if not args.allow_project_mismatch:
            sys.exit(1)
        print("       Continuing anyway because --allow-project-mismatch was set.")

    tasks = json.loads(Path(args.tasks).read_text(encoding="utf-8"))
    if args.task_id:
        tasks = [task for task in tasks if task["id"] == args.task_id]
        if not tasks:
            print(f"Task '{args.task_id}' not found.")
            sys.exit(1)

    projects_root = Path(args.projects_dir)
    seen_projects = set()
    for task in tasks:
        pname = task.get("project", "")
        if pname and pname not in seen_projects:
            project_dir = projects_root / pname
            if project_dir.exists():
                ensure_git_repo(project_dir)
            seen_projects.add(pname)

    results = []
    for task in tasks:
        print(f"\nRunning [{task['id']}]: {task['description'][:70]}...")
        pname = task.get("project", "")
        project_dir = projects_root / pname if pname else None
        before_sha = None
        if project_dir and project_dir.exists():
            before_sha = git_commit_all(project_dir, f"direct-baseline-before-{task['id']}")

        started = time.time()
        try:
            agent_result = run_task_direct(task, args.agent_port)

            if project_dir and project_dir.exists():
                git_commit_all(project_dir, f"direct-after-{task['id']}")

            validation = validate(
                task,
                agent_result,
                projects_root,
                before_sha=before_sha,
                rm_jar=args.rm_jar or None,
            )
            status_text = "PASS" if validation["passed"] else "FAIL"
        except Exception as exc:
            agent_result = {"tool_calls": [], "turns": 0}
            validation = {"passed": False, "notes": [str(exc)], "validation_type": "error"}
            status_text = "ERROR"

        elapsed = round(time.time() - started, 2)
        print(f"  -> {status_text} in {elapsed}s ({len(agent_result['tool_calls'])} tool calls)")
        results.append(
            {
                "id": task["id"],
                "description": task["description"],
                "status": status_text,
                "elapsed_s": elapsed,
                "turns": agent_result["turns"],
                "tool_calls": agent_result["tool_calls"],
                "validation": validation,
            }
        )

        if project_dir and before_sha:
            git_reset_to_commit(project_dir, before_sha)
            time.sleep(2)

    print_summary(results)
    output = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "agent": "structured-direct-no-api",
        "tasks": results,
    }
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(f"\nFull results written to {out_path}")


if __name__ == "__main__":
    main()
