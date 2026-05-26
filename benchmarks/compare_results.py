#!/usr/bin/env python3
"""
Compare structured vs text-edit benchmark results.

Usage:
    python benchmarks/compare_results.py results/structured-run.json results/text-edit-run.json [--csv results/table.csv]

Prints correctness and efficiency metrics. Output is ASCII-only so it works in
default Windows PowerShell terminals. Pass --csv <path> to also write CSV for
thesis tables.
"""

import csv
import json
import sys
from pathlib import Path


def load(path: str) -> dict:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return {task["id"]: task for task in data["tasks"]}


def write_csv(path: str, rows: list, structured_pass: int, text_edit_pass: int, total: int) -> None:
    """Write correctness + efficiency table to CSV for thesis inclusion."""
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["task_id", "s_pass", "te_pass", "s_turns", "te_turns",
                    "s_tools", "te_tools", "s_time_s", "te_time_s"])
        for task_id, s_ok, t_ok, s_turns, t_turns, s_tools, t_tools, s_time, t_time, *_ in rows:
            w.writerow([task_id, "PASS" if s_ok else "FAIL", "PASS" if t_ok else "FAIL",
                        s_turns, t_turns, s_tools, t_tools, f"{s_time:.1f}", f"{t_time:.1f}"])
        w.writerow(["TOTAL", f"{structured_pass}/{total}", f"{text_edit_pass}/{total}",
                    "", "", "", "", "", ""])
    print(f"\nCSV written to {path}")


def main() -> None:
    args = sys.argv[1:]
    csv_out = None
    if "--csv" in args:
        idx = args.index("--csv")
        csv_out = args[idx + 1]
        args = args[:idx] + args[idx + 2:]

    if len(args) < 2:
        print("Usage: compare_results.py <structured.json> <text-edit.json> [--csv out.csv]")
        sys.exit(1)

    structured = load(args[0])
    text_edit = load(args[1])

    task_ids = list(structured.keys())
    for task_id in sorted(set(text_edit) - set(structured)):
        task_ids.append(task_id)

    rows = []
    structured_pass = 0
    text_edit_pass = 0
    structured_turns = 0
    text_edit_turns = 0
    structured_time = 0.0
    text_edit_time = 0.0
    structured_only = 0

    for task_id in task_ids:
        sr = structured.get(task_id)
        tr = text_edit.get(task_id)
        s_ok = sr is not None and sr.get("status") == "PASS"
        t_ok = tr is not None and tr.get("status") == "PASS"
        s_turns = sr.get("turns", 0) if sr else 0
        t_turns = tr.get("turns", 0) if tr else 0
        s_tools = len(sr.get("tool_calls", [])) if sr else 0
        t_tools = len(tr.get("tool_calls", [])) if tr else 0
        s_time = float(sr.get("elapsed_s", 0)) if sr else 0.0
        t_time = float(tr.get("elapsed_s", 0)) if tr else 0.0

        if s_ok:
            structured_pass += 1
        if t_ok:
            text_edit_pass += 1
        structured_turns += s_turns
        text_edit_turns += t_turns
        structured_time += s_time
        text_edit_time += t_time

        note = ""
        if s_ok and not t_ok:
            note = "<-- structured ONLY"
            structured_only += 1
        elif t_ok and not s_ok:
            note = "<-- text-edit ONLY"

        rows.append((task_id, s_ok, t_ok, s_turns, t_turns, s_tools, t_tools, s_time, t_time, note, sr, tr))

    total = len(task_ids)

    print(f"\n{'=' * 80}")
    print("CORRECTNESS (PASS = validation checks pass)")
    print(f"{'=' * 80}")
    print(f"  {'Task ID':<22} {'Structured':^12} {'Text-Edit':^12} {'Note'}")
    print(f"  {'-' * 22} {'-' * 12} {'-' * 12} {'-' * 24}")
    for task_id, s_ok, t_ok, *_rest in rows:
        note = _rest[-3]
        print(f"  {task_id:<22} {'PASS' if s_ok else 'FAIL':^12} {'PASS' if t_ok else 'FAIL':^12} {note}")
    print(f"  {'-' * 22} {'-' * 12} {'-' * 12}")
    print(f"  {'TOTAL':<22} {structured_pass}/{total:^10} {text_edit_pass}/{total:^10}")

    print(f"\n{'=' * 80}")
    print("EFFICIENCY")
    print(f"{'=' * 80}")
    print(f"  {'Task ID':<22} {'S.turns':>8} {'TE.turns':>9} {'S.tools':>8} {'TE.tools':>9} {'S.time(s)':>10} {'TE.time(s)':>11}")
    print(f"  {'-' * 22} {'-' * 8} {'-' * 9} {'-' * 8} {'-' * 9} {'-' * 10} {'-' * 11}")
    for task_id, _s_ok, _t_ok, s_turns, t_turns, s_tools, t_tools, s_time, t_time, *_ in rows:
        print(f"  {task_id:<22} {s_turns:>8} {t_turns:>9} {s_tools:>8} {t_tools:>9} {s_time:>10.1f} {t_time:>11.1f}")
    print(f"  {'-' * 22} {'-' * 8} {'-' * 9} {'-' * 8} {'-' * 9} {'-' * 10} {'-' * 11}")
    print(f"  {'TOTAL TIME':<22} {structured_time:>8.1f}s {text_edit_time:>9.1f}s")

    print("\nSUMMARY")
    print(f"  Correctness: Structured {structured_pass}/{total} vs Text-edit {text_edit_pass}/{total}")
    if structured_only:
        print(f"  Binary wins: Structured exclusively passes {structured_only} task(s)")
    if structured_turns and text_edit_turns:
        print(f"  Agent turns: Text-edit uses {text_edit_turns / structured_turns:.1f}x more turns")
    print("  Note: direct no-API runners use 0 LLM turns; tool calls and pass/fail are the key metrics.")

    diff_rows = [row for row in rows if row[1] != row[2]]
    if diff_rows:
        print("\nDIFFERENCES IN OUTCOME")
        for task_id, _s_ok, _t_ok, *_metrics, sr, tr in diff_rows:
            print(f"  [{task_id}]")
            for note in (sr or {}).get("validation", {}).get("notes", []):
                print(f"    [structured] {note}")
            for note in (tr or {}).get("validation", {}).get("notes", []):
                print(f"    [text-edit]  {note}")

    if csv_out:
        write_csv(csv_out, rows, structured_pass, text_edit_pass, total)


if __name__ == "__main__":
    main()
