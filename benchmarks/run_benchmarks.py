#!/usr/bin/env python3
"""
Benchmark runner for the structured-refactoring-agent.

Usage:
    python benchmarks/run_benchmarks.py \\
        --tasks benchmarks/tasks.json \\
        --agent-port 6473 \\
        --api-key $ANTHROPIC_API_KEY \\
        --out results/run-$(date +%Y%m%d-%H%M%S).json

Requirements:
    pip install anthropic requests

The IntelliJ IDE with the plugin must be running with a project open before
running this script (so the agent server is started on port 6473).
"""

import json
import sys
import time
import argparse
import requests
import anthropic
from pathlib import Path

TOOL_SCHEMA_URL = "http://127.0.0.1:{port}/tools/schema"
TOOL_CALL_URL   = "http://127.0.0.1:{port}/tools"
STATUS_URL      = "http://127.0.0.1:{port}/status"


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
        timeout=30,
    )
    return r.json()


def run_task_with_agent(task: dict, port: int, api_key: str, model: str = "claude-sonnet-4-6",
                        max_turns: int = 12) -> dict:
    """Drive Claude with a natural-language instruction and collect tool calls."""
    schema_r = requests.get(TOOL_SCHEMA_URL.format(port=port), timeout=5)
    tools = schema_r.json()

    # Inject project context from the running IDE
    status = requests.get(STATUS_URL.format(port=port), timeout=5).json()
    system_prompt = (
        "You are an expert software engineering assistant. "
        f"You are connected to an IntelliJ project called '{status.get('project', '?')}' "
        f"via a structured refactoring tool API on port {port}. "
        "Use find_symbol_by_name to locate symbols before operating on them. "
        "Use read_file to inspect source code before adding or modifying members. "
        "Use find_usages to check impact before renaming or deleting. "
        "Always use qualifiedName parameters instead of filePath+offset when possible."
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


def validate(task: dict, agent_result: dict) -> dict:
    validation = task.get("validation", {})
    vtype = validation.get("type", "")
    passed = True
    notes = []

    # All mutating tool calls must have returned ok: true
    # (find_symbol, list_symbols, read_file, find_usages do not return ok/error)
    READ_ONLY_TOOLS = {"find_symbol_by_name", "find_symbol", "list_symbols", "read_file", "find_usages"}
    failed_calls = [
        c for c in agent_result["tool_calls"]
        if c["tool"] not in READ_ONLY_TOOLS and not c["result"].get("ok", False)
    ]
    for fc in failed_calls:
        passed = False
        notes.append(f"Tool '{fc['tool']}' failed: {fc['result'].get('error', fc['result'])}")

    # Expected tools must all have been called
    expected_tools = [op["tool"] for op in task.get("operations", [])]
    actual_tools   = [c["tool"] for c in agent_result["tool_calls"]]
    missing = [t for t in expected_tools if t not in actual_tools]
    if missing:
        passed = False
        notes.append(f"Expected tools not called: {missing}")
    else:
        notes.append(f"All expected tools called: {expected_tools}")

    # Type-specific validation
    if vtype == "find_usages_non_empty":
        usage_calls = [c for c in agent_result["tool_calls"] if c["tool"] == "find_usages"]
        if usage_calls and usage_calls[-1]["result"].get("count", 0) == 0:
            passed = False
            notes.append("find_usages returned 0 results")
        elif usage_calls:
            notes.append(f"find_usages returned {usage_calls[-1]['result'].get('count', '?')} results")

    if vtype == "tool_called":
        expected_tool = validation.get("expectedTool", "")
        if expected_tool and expected_tool not in actual_tools:
            passed = False
            notes.append(f"Expected tool '{expected_tool}' was not called")

    return {
        "passed": passed,
        "notes": notes,
        "validation_type": vtype,
    }


def print_summary(results: list) -> None:
    passed = sum(1 for r in results if r["status"] == "PASS")
    print(f"\n{'='*60}")
    print(f"Results: {passed}/{len(results)} tasks passed")
    print(f"{'='*60}")
    for r in results:
        icon = "✓" if r["status"] == "PASS" else "✗"
        print(f"  {icon} [{r['id']}] {r['status']}  ({r['turns']} turns, {r['elapsed_s']}s)")
        for note in r["validation"]["notes"]:
            print(f"      • {note}")


def main():
    parser = argparse.ArgumentParser(description="Run structured-refactoring-agent benchmarks")
    parser.add_argument("--tasks",      default="benchmarks/tasks.json")
    parser.add_argument("--agent-port", type=int, default=6473)
    parser.add_argument("--api-key",    required=True, help="Anthropic API key")
    parser.add_argument("--model",      default="claude-sonnet-4-6", help="Claude model ID")
    parser.add_argument("--max-turns",  type=int, default=12, help="Max tool-use turns per task")
    parser.add_argument("--out",        default="results/run.json")
    parser.add_argument("--task-id",    help="Run a single task by ID")
    args = parser.parse_args()

    if not check_server(args.agent_port):
        print(f"ERROR: No agent server on port {args.agent_port}.")
        print("       Open IntelliJ with the plugin installed and a project loaded.")
        sys.exit(1)

    tasks = json.loads(Path(args.tasks).read_text())
    if args.task_id:
        tasks = [t for t in tasks if t["id"] == args.task_id]
        if not tasks:
            print(f"Task '{args.task_id}' not found.")
            sys.exit(1)

    results = []
    for task in tasks:
        print(f"\nRunning [{task['id']}]: {task['description'][:70]}...")
        t0 = time.time()
        try:
            agent_result = run_task_with_agent(task, args.agent_port, args.api_key,
                                               model=args.model, max_turns=args.max_turns)
            validation   = validate(task, agent_result)
            status = "PASS" if validation["passed"] else "FAIL"
        except Exception as e:
            agent_result = {"tool_calls": [], "turns": 0}
            validation   = {"passed": False, "notes": [str(e)], "validation_type": "error"}
            status = "ERROR"
        elapsed = round(time.time() - t0, 2)
        print(f"  → {status} in {elapsed}s ({agent_result['turns']} turns)")
        results.append({
            "id": task["id"],
            "description": task["description"],
            "status": status,
            "elapsed_s": elapsed,
            "turns": agent_result["turns"],
            "tool_calls": agent_result["tool_calls"],
            "validation": validation,
        })

    print_summary(results)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    output = {"timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"), "tasks": results}
    Path(args.out).write_text(json.dumps(output, indent=2))
    print(f"\nFull results written to {args.out}")


if __name__ == "__main__":
    main()
