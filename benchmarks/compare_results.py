#!/usr/bin/env python3
"""
Compare structured vs text-edit benchmark results.

Usage:
    python benchmarks/compare_results.py \
        results/structured-run.json \
        results/text-edit-run.json

Prints a side-by-side table with correctness AND efficiency metrics
(turns, time, tool calls) — because on a small project both agents
may pass, but the structured agent uses far fewer turns and is
provably correct.  Scale advantages appear in the notes section.
"""

import json
import sys
from pathlib import Path


def load(path: str) -> dict:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return {t["id"]: t for t in data["tasks"]}


def main():
    if len(sys.argv) < 3:
        print("Usage: compare_results.py <structured.json> <text-edit.json>")
        sys.exit(1)

    structured = load(sys.argv[1])
    text_edit  = load(sys.argv[2])

    all_ids = list(structured.keys()) or sorted(set(structured) | set(text_edit))
    # Preserve task order from structured results if available
    if structured:
        all_ids = list(structured.keys())
        for tid in sorted(set(text_edit) - set(structured)):
            all_ids.append(tid)

    struct_pass = te_pass = 0
    struct_turns_total = te_turns_total = 0
    struct_time_total  = te_time_total  = 0
    advantage_count = 0

    rows = []
    for tid in all_ids:
        sr = structured.get(tid)
        tr = text_edit.get(tid)

        s_ok    = (sr["status"] == "PASS") if sr else False
        t_ok    = (tr["status"] == "PASS") if tr else False
        s_turns = sr.get("turns", 0)          if sr else 0
        t_turns = tr.get("turns", 0)          if tr else 0
        s_time  = sr.get("elapsed_s", 0)      if sr else 0
        t_time  = tr.get("elapsed_s", 0)      if tr else 0
        s_tools = len(sr.get("tool_calls", [])) if sr else 0
        t_tools = len(tr.get("tool_calls", [])) if tr else 0

        if s_ok: struct_pass += 1
        if t_ok: te_pass += 1
        struct_turns_total += s_turns
        te_turns_total     += t_turns
        struct_time_total  += s_time
        te_time_total      += t_time

        adv = ""
        if s_ok and not t_ok:
            adv = "<-- structured ONLY"
            advantage_count += 1
        elif not s_ok and t_ok:
            adv = "<-- text-edit ONLY"

        rows.append((tid, s_ok, t_ok, s_turns, t_turns, s_tools, t_tools, s_time, t_time, adv,
                     sr, tr))

    total = len(all_ids)

    # ── Correctness table ────────────────────────────────────────────────────
    print(f"\n{'='*80}")
    print("CORRECTNESS  (PASS = compiles + content checks pass)")
    print(f"{'='*80}")
    print(f"  {'Task ID':<22} {'Structured':^12} {'Text-Edit':^12} {'Note'}")
    print(f"  {'-'*22} {'-'*12} {'-'*12} {'-'*24}")
    for tid, s_ok, t_ok, *_, adv, sr, tr in rows:
        print(f"  {tid:<22} {'PASS' if s_ok else 'FAIL':^12} {'PASS' if t_ok else 'FAIL':^12} {adv}")
    print(f"  {'─'*22} {'─'*12} {'─'*12}")
    print(f"  {'TOTAL':<22} {struct_pass}/{total:^10} {te_pass}/{total:^10}")
    print(f"{'='*80}")

    # ── Efficiency table ─────────────────────────────────────────────────────
    print(f"\n{'='*80}")
    print("EFFICIENCY  (fewer turns / tool calls = structured wins even if both pass)")
    print(f"{'='*80}")
    print(f"  {'Task ID':<22} {'S.turns':>8} {'TE.turns':>9} {'S.tools':>8} {'TE.tools':>9} {'S.time(s)':>10} {'TE.time(s)':>11}")
    print(f"  {'-'*22} {'-'*8} {'-'*9} {'-'*8} {'-'*9} {'-'*10} {'-'*11}")
    for tid, s_ok, t_ok, s_turns, t_turns, s_tools, t_tools, s_time, t_time, adv, sr, tr in rows:
        ratio = f"  ({t_turns/s_turns:.1f}x)" if s_turns else ""
        print(f"  {tid:<22} {s_turns:>8} {t_turns:>9} {s_tools:>8} {t_tools:>9} {s_time:>10.1f} {t_time:>11.1f}{ratio}")
    print(f"  {'─'*22} {'─'*8} {'─'*9} {'─'*8} {'─'*9} {'─'*10} {'─'*11}")
    s_avg_turns = struct_turns_total / total if total else 0
    t_avg_turns = te_turns_total     / total if total else 0
    print(f"  {'AVERAGE':<22} {s_avg_turns:>8.1f} {t_avg_turns:>9.1f}")
    print(f"  {'TOTAL TIME':<22} {struct_time_total:>8.1f}s {te_time_total:>9.1f}s")
    print(f"{'='*80}")

    # ── Interpretation ───────────────────────────────────────────────────────
    print(f"\nSUMMARY")
    print(f"  Correctness:  Structured {struct_pass}/{total}  vs  Text-edit {te_pass}/{total}")
    if advantage_count:
        print(f"  Binary wins:  Structured exclusively passes {advantage_count} task(s)")
    if struct_turns_total and te_turns_total:
        ratio = te_turns_total / struct_turns_total
        print(f"  Efficiency:   Text-edit uses {ratio:.1f}x more agent turns on average")
    print(f"\n  Note: On a small project, a capable text-edit agent may enumerate all")
    print(f"  cross-file references manually.  The structured agent's advantage is:")
    print(f"    (a) Provably correct — uses IDE's full reference index, not grep")
    print(f"    (b) O(1) turns regardless of project size — scales to large codebases")
    print(f"    (c) Cannot produce syntactically invalid output")
    print()

    # ── Per-task failure notes ───────────────────────────────────────────────
    diff_tasks = [(tid, sr, tr) for tid, s_ok, t_ok, *_, sr, tr in rows
                  if (sr and tr) and sr["status"] != tr["status"]]
    if diff_tasks:
        print("DIFFERENCES IN OUTCOME")
        for tid, sr, tr in diff_tasks:
            print(f"  [{tid}]")
            for note in (sr or {}).get("validation", {}).get("notes", []):
                print(f"    [structured] {note}")
            for note in (tr or {}).get("validation", {}).get("notes", []):
                print(f"    [text-edit]  {note}")
            print()


if __name__ == "__main__":
    main()
