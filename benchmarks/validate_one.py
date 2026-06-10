"""Standalone validator for the blind-Claude-agent benchmark.

Reuses run_benchmarks.validate() on the CURRENT working tree, given a task id and
a captured agent_result (tool_calls) JSON. This guarantees the Claude-driven run
is scored by the IDENTICAL oracle (compile + disk-state + mutating-tool checks) as
the qwen2.5 and text-edit runs, so the numbers are directly comparable.

Usage:
  python benchmarks/validate_one.py --tasks benchmarks/tasks_petclinic.json \
      --task-id pc-move-001 --calls results/_claude_calls_pc-move-001.json \
      --projects-dir benchmarks/projects
"""
import os
import sys
import json
import argparse
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_benchmarks import validate  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", required=True)
    ap.add_argument("--task-id", required=True)
    ap.add_argument("--calls", required=True, help='JSON file: {"tool_calls":[...]}')
    ap.add_argument("--projects-dir", default="benchmarks/projects")
    ap.add_argument("--rm-jar", default=None)
    a = ap.parse_args()

    tasks = json.loads(Path(a.tasks).read_text(encoding="utf-8"))
    task = next((t for t in tasks if t["id"] == a.task_id), None)
    if task is None:
        print(json.dumps({"passed": False, "notes": [f"task {a.task_id} not found"]}))
        return

    try:
        calls = json.loads(Path(a.calls).read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        calls = {"tool_calls": []}
    if not isinstance(calls, dict) or "tool_calls" not in calls:
        calls = {"tool_calls": calls if isinstance(calls, list) else []}

    res = validate(task, calls, Path(a.projects_dir), rm_jar=a.rm_jar)
    print(json.dumps(res))


if __name__ == "__main__":
    main()
