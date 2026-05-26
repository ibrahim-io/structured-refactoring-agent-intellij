#!/usr/bin/env python3
"""
No-API scripted text-edit baseline.

This runner applies deterministic regex/string edits directly to Java source
files. It intentionally has no IntelliJ PSI, no reference index, no AST parser,
and no LLM. It is a zero-cost lower-bound baseline for raw textual editing.

Usage:
    python benchmarks/run_text_edit_direct.py ^
        --tasks benchmarks/tasks.json ^
        --projects-dir benchmarks/projects ^
        --out results/text-edit-direct-1.json
"""

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path


def java_src(project_dir: Path) -> Path:
    return project_dir / "src" / "main" / "java"


def class_qn_to_path(project_dir: Path, qualified_name: str) -> Path:
    class_name = qualified_name.split("#", 1)[0]
    return java_src(project_dir) / (class_name.replace(".", "/") + ".java")


def package_to_dir(project_dir: Path, package_name: str) -> Path:
    return java_src(project_dir) / package_name.replace(".", "/")


def simple_member_name(qualified_name: str) -> str:
    return qualified_name.rsplit("#", 1)[-1].split("(", 1)[0]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def java_files(project_dir: Path) -> list[Path]:
    return sorted(java_src(project_dir).rglob("*.java"))


def replace_word_in_java(project_dir: Path, old: str, new: str) -> int:
    regex = re.compile(r"\b" + re.escape(old) + r"\b")
    count = 0
    for path in java_files(project_dir):
        content = read_text(path)
        updated, replacements = regex.subn(new, content)
        if replacements:
            write_text(path, updated)
            count += replacements
    return count


def find_symbol_by_name(project_dir: Path, qualified_name: str) -> dict:
    file_path = class_qn_to_path(project_dir, qualified_name)
    name = simple_member_name(qualified_name) if "#" in qualified_name else qualified_name.rsplit(".", 1)[-1]
    if file_path.exists():
        return {"ok": True, "name": name, "filePath": str(file_path), "kind": "guessed-text-symbol"}
    return {"ok": False, "error": f"Could not derive source file for {qualified_name}: {file_path}"}


def read_file(params: dict) -> dict:
    path = Path(params["filePath"])
    if not path.exists():
        return {"ok": False, "error": f"File not found: {path}"}
    lines = read_text(path).splitlines()
    start = max(int(params.get("startLine", 1)), 1)
    end = min(int(params.get("endLine", len(lines))), len(lines))
    numbered = [f"{idx}: {lines[idx - 1]}" for idx in range(start, end + 1)]
    return {"ok": True, "filePath": str(path), "content": "\n".join(numbered)}


def find_usages(project_dir: Path, qualified_name: str) -> dict:
    name = simple_member_name(qualified_name) if "#" in qualified_name else qualified_name.rsplit(".", 1)[-1]
    regex = re.compile(r"\b" + re.escape(name) + r"\b")
    usages = []
    for path in java_files(project_dir):
        for line_no, line in enumerate(read_text(path).splitlines(), 1):
            if regex.search(line):
                usages.append(
                    {
                        "filePath": str(path),
                        "line": line_no,
                        "preview": line.strip(),
                        "kind": "text-match",
                    }
                )
    return {"usages": usages, "count": len(usages)}


def rename_symbol(project_dir: Path, params: dict) -> dict:
    qualified_name = params["qualifiedName"]
    old_name = simple_member_name(qualified_name)
    new_name = params["newName"]
    replacements = replace_word_in_java(project_dir, old_name, new_name)

    class_path = class_qn_to_path(project_dir, qualified_name)
    if class_path.exists() and class_path.stem == old_name:
        new_path = class_path.with_name(f"{new_name}.java")
        class_path.rename(new_path)

    return {"ok": replacements > 0, "message": f"Replaced {replacements} textual occurrence(s)"}


def move_class(project_dir: Path, params: dict) -> dict:
    qualified_name = params["qualifiedClassName"]
    target_package = params["targetPackage"]
    old_class = qualified_name.rsplit(".", 1)[-1]
    old_package = qualified_name.rsplit(".", 1)[0]
    old_path = class_qn_to_path(project_dir, qualified_name)
    if not old_path.exists():
        return {"ok": False, "error": f"Source file not found: {old_path}"}

    new_path = package_to_dir(project_dir, target_package) / f"{old_class}.java"
    content = read_text(old_path)
    content = re.sub(
        r"^\s*package\s+" + re.escape(old_package) + r"\s*;",
        f"package {target_package};",
        content,
        count=1,
        flags=re.MULTILINE,
    )
    write_text(new_path, content)
    old_path.unlink()

    replace_word_in_java(project_dir, f"{old_package}.{old_class}", f"{target_package}.{old_class}")
    return {"ok": True, "message": f"Moved file textually to {new_path}"}


def safe_delete(project_dir: Path, params: dict) -> dict:
    qualified_name = params["qualifiedName"]
    method_name = simple_member_name(qualified_name)
    file_path = class_qn_to_path(project_dir, qualified_name)
    if not file_path.exists():
        return {"ok": False, "error": f"Source file not found: {file_path}"}

    content = read_text(file_path)
    pattern = re.compile(
        r"\n\s*(?:public|protected|private)?\s*(?:static\s+)?[\w<>\[\], ?]+\s+"
        + re.escape(method_name)
        + r"\s*\([^)]*\)\s*\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}\s*",
        re.MULTILINE,
    )
    updated, replacements = pattern.subn("\n", content, count=1)
    if replacements:
        write_text(file_path, updated)
    return {"ok": replacements > 0, "message": f"Deleted {replacements} textual method block(s)"}


def create_java_file(project_dir: Path, params: dict) -> dict:
    target = package_to_dir(project_dir, params["packageName"]) / params["fileName"]
    if target.exists():
        return {"ok": False, "error": f"File already exists: {target}"}
    write_text(target, params["content"])
    return {"ok": True, "message": f"Created {target}"}


def add_method(project_dir: Path, params: dict) -> dict:
    file_path = Path(params["filePath"])
    if not file_path.exists():
        return {"ok": False, "error": f"File not found: {file_path}"}
    content = read_text(file_path)
    insert_at = content.rfind("}")
    if insert_at == -1:
        return {"ok": False, "error": "Could not find class closing brace"}
    method_text = params["methodText"].strip()
    updated = content[:insert_at].rstrip() + "\n\n    " + method_text + "\n" + content[insert_at:]
    write_text(file_path, updated)
    return {"ok": True, "message": "Inserted method before final brace"}


def change_signature(project_dir: Path, params: dict) -> dict:
    qualified_name = params["qualifiedName"]
    method_name = simple_member_name(qualified_name)
    changes = params.get("parameterChanges", [])
    new_params = []
    added_params = []
    for change in changes:
        declaration = f"{change['type']} {change['name']}"
        new_params.append(declaration)
        if str(change.get("oldIndex", "")) == "-1":
            added_params.append(change.get("defaultValue", "0"))

    file_path = class_qn_to_path(project_dir, qualified_name)
    if not file_path.exists():
        return {"ok": False, "error": f"Source file not found: {file_path}"}

    declaration_regex = re.compile(r"(" + re.escape(method_name) + r"\s*)\([^)]*\)")
    content = read_text(file_path)
    updated, declaration_replacements = declaration_regex.subn(
        r"\1(" + ", ".join(new_params) + ")",
        content,
        count=1,
    )
    write_text(file_path, updated)

    call_regex = re.compile(r"\b" + re.escape(method_name) + r"\s*\(([^)]*)\)")
    call_replacements = 0
    for path in java_files(project_dir):
        content = read_text(path)

        def rewrite_call(match: re.Match) -> str:
            nonlocal call_replacements
            args = match.group(1).strip()
            if path == file_path and match.start() < len(updated):
                return match.group(0)
            suffix = ", " + ", ".join(added_params) if added_params else ""
            call_replacements += 1
            return f"{method_name}({args}{suffix})"

        new_content = call_regex.sub(rewrite_call, content)
        if new_content != content:
            write_text(path, new_content)

    return {
        "ok": declaration_replacements > 0,
        "message": f"Changed declaration and rewrote {call_replacements} textual call(s)",
    }


def inline_method(project_dir: Path, params: dict) -> dict:
    qualified_name = params["qualifiedName"]
    method_name = simple_member_name(qualified_name)
    file_path = class_qn_to_path(project_dir, qualified_name)
    if not file_path.exists():
        return {"ok": False, "error": f"Source file not found: {file_path}"}

    content = read_text(file_path)
    method_regex = re.compile(
        r"\n\s*(?:public|protected|private)?\s*(?:static\s+)?[\w<>\[\], ?]+\s+"
        + re.escape(method_name)
        + r"\s*\(([^)]*)\)\s*\{\s*return\s+([^;]+);\s*\}\s*",
        re.MULTILINE,
    )
    method_match = method_regex.search(content)
    if not method_match:
        return {"ok": False, "error": "Only single-return methods are supported by text direct inline"}

    params_text = method_match.group(1).strip()
    return_expr = method_match.group(2).strip()
    param_names = []
    for param in [p.strip() for p in params_text.split(",") if p.strip()]:
        param_names.append(param.split()[-1])

    call_regex = re.compile(r"\b[A-Za-z_][A-Za-z0-9_]*\." + re.escape(method_name) + r"\s*\(([^)]*)\)")
    replacements = 0
    for path in java_files(project_dir):
        content = read_text(path)

        def rewrite_call(match: re.Match) -> str:
            nonlocal replacements
            args = [arg.strip() for arg in match.group(1).split(",")]
            expr = return_expr
            for formal, actual in zip(param_names, args):
                expr = re.sub(r"\b" + re.escape(formal) + r"\b", actual, expr)
            replacements += 1
            return "(" + expr + ")"

        updated = call_regex.sub(rewrite_call, content)
        if path == file_path and params.get("deleteOriginal", True):
            updated = method_regex.sub("\n", updated, count=1)
        if updated != content:
            write_text(path, updated)

    return {"ok": replacements > 0, "message": f"Inlined {replacements} textual call(s)"}


def resolve_placeholders(value, context: dict):
    if isinstance(value, str):
        if value in {"__resolved__", "__resolved_from_find_symbol__"}:
            return context.get("last_symbol_file_path", value)
        return value
    if isinstance(value, list):
        return [resolve_placeholders(item, context) for item in value]
    if isinstance(value, dict):
        return {key: resolve_placeholders(val, context) for key, val in value.items()}
    return value


def dispatch_operation(project_dir: Path, operation: dict, context: dict) -> dict:
    tool = operation["tool"]
    params = resolve_placeholders(operation.get("params", {}), context)

    if tool == "find_symbol_by_name":
        result = find_symbol_by_name(project_dir, params["qualifiedName"])
        if result.get("filePath"):
            context["last_symbol_file_path"] = result["filePath"]
        return result
    if tool == "find_usages":
        return find_usages(project_dir, params["qualifiedName"])
    if tool == "read_file":
        return read_file(params)
    if tool == "rename_symbol":
        return rename_symbol(project_dir, params)
    if tool == "move_class":
        return move_class(project_dir, params)
    if tool == "safe_delete":
        return safe_delete(project_dir, params)
    if tool == "create_java_file":
        return create_java_file(project_dir, params)
    if tool == "add_method":
        return add_method(project_dir, params)
    if tool == "change_signature":
        return change_signature(project_dir, params)
    if tool == "inline_method":
        return inline_method(project_dir, params)
    return {"ok": False, "error": f"Unsupported scripted text operation: {tool}"}


def run_task_direct_text(task: dict, project_dir: Path) -> dict:
    context = {}
    calls = []
    for operation in task.get("operations", []):
        params = resolve_placeholders(operation.get("params", {}), context)
        result = dispatch_operation(project_dir, operation, context)
        calls.append({"tool": operation["tool"], "params": params, "result": result})
    return {"tool_calls": calls, "turns": 0}


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
        return {"ok": False, "error": ((result.stdout or "") + "\n" + (result.stderr or ""))[-4000:]}
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def grep_in_java_sources(project_dir: Path, pattern: str) -> list[str]:
    regex = re.compile(r"\b" + re.escape(pattern) + r"\b")
    hits = []
    for path in java_files(project_dir):
        try:
            for line_no, line in enumerate(read_text(path).splitlines(), 1):
                stripped = line.strip()
                if stripped.startswith("*") or stripped.startswith("//"):
                    continue
                if regex.search(line):
                    hits.append(f"{path.name}:{line_no}: {stripped}")
        except Exception:
            pass
    return hits


def validate(task: dict, result: dict, project_dir: Path) -> dict:
    passed = True
    notes = []
    validation = task.get("validation", {})
    vtype = validation.get("type", "")

    read_only = {"find_symbol_by_name", "find_usages", "read_file"}
    failed = [
        call
        for call in result["tool_calls"]
        if call["tool"] not in read_only and not call["result"].get("ok", False)
    ]
    for call in failed:
        passed = False
        notes.append(f"Tool '{call['tool']}' failed: {call['result'].get('error', call['result'])}")

    if vtype == "find_usages_non_empty":
        calls = [call for call in result["tool_calls"] if call["tool"] == "find_usages"]
        count = calls[-1]["result"].get("count", 0) if calls else 0
        if count > 0:
            notes.append(f"text grep returned {count} textual usage(s)")
        else:
            passed = False
            notes.append("text grep returned no usages")
        notes.append("Note: this is grep, not structural reference resolution")
        return {"passed": passed, "notes": notes, "validation_type": vtype}

    if vtype == "tool_called":
        expected = validation.get("expectedTool", "")
        actual = [call["tool"] for call in result["tool_calls"]]
        if expected in actual:
            notes.append(f"Tool '{expected}' was called")
        else:
            passed = False
            notes.append(f"Tool '{expected}' was NOT called")
        return {"passed": passed, "notes": notes, "validation_type": vtype}

    if vtype.startswith("compile"):
        compile_result = run_maven_compile(project_dir)
        if compile_result["ok"]:
            notes.append("Compile: PASS")
        else:
            passed = False
            notes.append(f"Compile: FAIL -- {compile_result['error'][-800:].replace(chr(10), ' ')}")

        if vtype == "compile_and_file_exists":
            expected = validation.get("expectedFile", "")
            target = java_src(project_dir) / expected
            if target.exists():
                notes.append(f"File exists: {expected}")
            else:
                passed = False
                notes.append(f"File NOT found: {expected}")

        elif vtype == "compile_and_no_reference":
            symbol = validation.get("deletedSymbol", "")
            hits = grep_in_java_sources(project_dir, symbol) if symbol else []
            if hits:
                passed = False
                notes.append(f"Symbol '{symbol}' still present: {hits[:3]}")
            else:
                notes.append(f"Symbol '{symbol}' absent from source files")

        elif vtype == "compile_and_symbol_exists":
            expected = validation.get("expectedSymbol", "")
            name = expected.split("#")[-1] if "#" in expected else expected
            hits = grep_in_java_sources(project_dir, name) if name else []
            if hits:
                notes.append(f"Symbol '{name}' found: {hits[0]}")
            else:
                passed = False
                notes.append(f"Symbol '{name}' NOT found in source files")

            if validation.get("crossFileCheck"):
                old_name = None
                for operation in task.get("operations", []):
                    if operation.get("tool") == "rename_symbol":
                        old_name = simple_member_name(operation.get("params", {}).get("qualifiedName", ""))
                stale = grep_in_java_sources(project_dir, old_name) if old_name else []
                if stale:
                    passed = False
                    notes.append(f"Cross-file: old name '{old_name}' still in sources: {stale[:5]}")
                elif old_name:
                    notes.append(f"Cross-file: old name '{old_name}' absent from all source files")

        elif vtype == "compile_and_refactoringminer":
            notes.append("RefactoringMiner: N/A for direct text-edit baseline")

    return {"passed": passed, "notes": notes, "validation_type": vtype}


def ensure_git_repo(project_dir: Path) -> None:
    if (project_dir / ".git").exists():
        return
    subprocess.run(["git", "init"], cwd=project_dir, capture_output=True)
    subprocess.run(["git", "config", "user.email", "benchmark@example.com"], cwd=project_dir, capture_output=True)
    subprocess.run(["git", "config", "user.name", "Benchmark Runner"], cwd=project_dir, capture_output=True)


def git_commit_all(project_dir: Path, message: str) -> str:
    subprocess.run(["git", "add", "-A"], cwd=project_dir, capture_output=True)
    subprocess.run(["git", "commit", "-m", message, "--allow-empty"], cwd=project_dir, capture_output=True)
    result = subprocess.run(["git", "rev-parse", "HEAD"], cwd=project_dir, capture_output=True, text=True)
    return result.stdout.strip()


def git_reset_to_commit(project_dir: Path, sha: str) -> None:
    subprocess.run(["git", "reset", "--hard", sha], cwd=project_dir, capture_output=True)
    subprocess.run(["git", "clean", "-fd"], cwd=project_dir, capture_output=True)


def print_summary(results: list[dict]) -> None:
    passed = sum(1 for result in results if result["status"] == "PASS")
    print(f"\n{'=' * 60}")
    print(f"Scripted text-edit direct results: {passed}/{len(results)} tasks passed")
    print(f"{'=' * 60}")
    for result in results:
        print(f"  [{result['status']}] [{result['id']}] ({result['elapsed_s']}s)")
        for note in result["validation"]["notes"]:
            print(f"      * {note}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Run no-API scripted text-edit baseline benchmarks")
    parser.add_argument("--tasks", default="benchmarks/tasks.json")
    parser.add_argument("--projects-dir", default="benchmarks/projects")
    parser.add_argument("--out", default="results/text-edit-direct-1.json")
    parser.add_argument("--task-id", help="Run one task by ID")
    args = parser.parse_args()

    tasks = json.loads(Path(args.tasks).read_text(encoding="utf-8"))
    if args.task_id:
        tasks = [task for task in tasks if task["id"] == args.task_id]
        if not tasks:
            print(f"Task '{args.task_id}' not found.")
            sys.exit(1)

    projects_root = Path(args.projects_dir)
    seen_projects = set()
    for task in tasks:
        project_name = task.get("project", "")
        if project_name and project_name not in seen_projects:
            project_dir = projects_root / project_name
            if project_dir.exists():
                ensure_git_repo(project_dir)
            seen_projects.add(project_name)

    results = []
    for task in tasks:
        print(f"\nRunning [{task['id']}]: {task['description'][:70]}...")
        project_dir = projects_root / task.get("project", "")
        if not project_dir.exists():
            print(f"  -> ERROR: project dir not found: {project_dir}")
            continue

        before_sha = git_commit_all(project_dir, f"text-direct-baseline-before-{task['id']}")
        started = time.time()
        try:
            direct_result = run_task_direct_text(task, project_dir)
            validation = validate(task, direct_result, project_dir)
            status = "PASS" if validation["passed"] else "FAIL"
        except Exception as exc:
            direct_result = {"tool_calls": [], "turns": 0}
            validation = {"passed": False, "notes": [str(exc)], "validation_type": "error"}
            status = "ERROR"

        elapsed = round(time.time() - started, 2)
        print(f"  -> {status} in {elapsed}s ({len(direct_result['tool_calls'])} scripted operation(s))")
        results.append(
            {
                "id": task["id"],
                "description": task["description"],
                "status": status,
                "elapsed_s": elapsed,
                "turns": direct_result["turns"],
                "tool_calls": direct_result["tool_calls"],
                "validation": validation,
            }
        )
        git_reset_to_commit(project_dir, before_sha)

    print_summary(results)
    output = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "agent": "text-edit-direct-no-api",
        "tasks": results,
    }
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(f"\nFull results written to {out_path}")


if __name__ == "__main__":
    main()
