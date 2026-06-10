"""Standalone validator for the Claude-WITHOUT-tools (text-edit) arm.

Reuses run_benchmarks_text_edit.validate() on the CURRENT working tree. The agent
edited source files directly (no structured tools), so validation is compile +
disk-state only (no structured tool-call requirement). Scoring is IDENTICAL to the
weak-model text-edit run, so Claude-text-edit vs weak-text-edit vs structured are
all directly comparable.

Usage:
  python benchmarks/validate_one_textedit.py --tasks benchmarks/tasks_petclinic.json \
      --task-id pc-move-001 --project-dir benchmarks/projects/spring-petclinic
"""
import os
import sys
import json
import argparse
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_benchmarks_text_edit import validate as te_validate  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tasks", required=True)
    ap.add_argument("--task-id", required=True)
    ap.add_argument("--project-dir", required=True, help="the specific project dir")
    a = ap.parse_args()

    tasks = json.loads(Path(a.tasks).read_text(encoding="utf-8"))
    task = next((t for t in tasks if t["id"] == a.task_id), None)
    if task is None:
        print(json.dumps({"passed": False, "notes": [f"task {a.task_id} not found"]}))
        return

    # The agent edited files directly; no structured tool calls to score.
    res = te_validate(task, {"tool_calls": []}, Path(a.project_dir))
    print(json.dumps(res))


if __name__ == "__main__":
    main()
