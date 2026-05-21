#!/usr/bin/env python3
"""
Compare structured vs text-edit benchmark results.

Usage:
    python benchmarks/compare_results.py \
        results/structured-run.json \
        results/text-edit-run.json

Prints a side-by-side table and highlights where the structured
agent passes but the text-edit agent fails (the thesis argument).
"""

import json
import sys
from pathlib import Path


def load(path: str) -> dict:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return {t["id"]: t for t in data["tasks"]}


def icon(status: str) -> str:
    return "PASS" if status == "PASS" else "FAIL"


def main():
    if len(sys.argv) < 3:
        print("Usage: compare_results.py <structured.json> <text-edit.json>")
        sys.exit(1)

    structured = load(sys.argv[1])
    text_edit  = load(sys.argv[2])

    all_ids = sorted(set(structured) | set(text_edit))

    print(f"\n{'='*72}")
    print(f"{'Task ID':<20} {'Structured':^12} {'Text-Edit':^12} {'Advantage':<20}")
    print(f"{'='*72}")

    struct_pass = 0
    te_pass = 0
    advantage_count = 0

    for tid in all_ids:
        sr = structured.get(tid)
        tr = text_edit.get(tid)

        s_status = sr["status"] if sr else "MISSING"
        t_status = tr["status"] if tr else "MISSING"

        if s_status == "PASS":
            struct_pass += 1
        if t_status == "PASS":
            te_pass += 1

        adv = ""
        if s_status == "PASS" and t_status != "PASS":
            adv = "<-- structured wins"
            advantage_count += 1
        elif s_status != "PASS" and t_status == "PASS":
            adv = "<-- text-edit wins"

        print(f"  {tid:<18} {icon(s_status):^12} {icon(t_status):^12} {adv}")

    total = len(all_ids)
    print(f"{'='*72}")
    print(f"  {'TOTAL':<18} {struct_pass}/{total:^10} {te_pass}/{total:^10}")
    print(f"{'='*72}")

    if advantage_count:
        print(f"\nStructured agent wins on {advantage_count}/{total} tasks where")
        print("text-edit fails — demonstrating AST-safety advantage.")
    print()

    # Print failure details for tasks where they differ
    for tid in all_ids:
        sr = structured.get(tid)
        tr = text_edit.get(tid)
        if not sr or not tr:
            continue
        if sr["status"] != tr["status"]:
            print(f"  [{tid}] Difference:")
            for note in sr["validation"]["notes"]:
                print(f"    [structured] {note}")
            for note in tr["validation"]["notes"]:
                print(f"    [text-edit]  {note}")
            print()


if __name__ == "__main__":
    main()
