# Evaluation Plan & Benchmark Harness

**Imperial College London — MEng (AI & Machine Learning) Final Year Project**

---

## 1. Research Questions

| RQ | Question |
|---|---|
| RQ1 | Does the structured-refactoring-agent produce semantically correct refactorings (correct-by-construction), compared to a text-substitution baseline? |
| RQ2 | Can a Claude agent driven by the tool API complete real-world multi-step refactoring tasks without manual intervention? |
| RQ3 | How accurately does the agent resolve symbols by qualified name (without being told file paths or offsets)? |
| RQ4 | How does developer-perceived effort compare between manual IntelliJ, Copilot Chat, and the structured-refactoring-agent for maintenance tasks? |

---

## 2. Benchmark Projects

### 2a. Sample Java Project (`benchmarks/projects/sample-java-project/`)

A minimal 4-class Maven project (Java 17) committed alongside the plugin. Every task in `benchmarks/tasks.json` targets this project at a fixed state — there are no external dependencies and no setup required beyond opening it in the sandbox IDE.

Classes:
| Class | Qualified name | Role |
|---|---|---|
| `User.java` | `com.example.User` | Has poorly-named field `usrNm`; rename + add-method targets |
| `LegacyHelper.java` | `com.example.LegacyHelper` | Has unused method `parseOldFormat`; safe-delete target |
| `Notifier.java` | `com.example.Notifier` | Has method `send(String)`; change-signature target |
| `utils/DateHelper.java` | `com.example.utils.DateHelper` | move-class target |

### 2b. spring-petclinic (`benchmarks/tasks_petclinic.json`)

A real-world Spring Boot application cloned at tag `3.3.0` via `benchmarks/setup.py --petclinic`. Used to validate that the agent can navigate unfamiliar, medium-sized codebases (~30 classes, Spring annotations, layered architecture).

Tasks cover:
- Rename attribute (`Owner.telephone` → `phoneNumber`)
- Add method (`Owner.getFullName()`)
- find_usages inspection (`Pet`)
- read_file inspection (reading `Owner` source)
- Rename class (`CrashController` → `PanicController`)

### 2c. Setup Script

```bash
python benchmarks/setup.py          # verifies sample-java-project
python benchmarks/setup.py --petclinic  # also clones spring-petclinic 3.3.0
```

---

## 3. Benchmark Tasks (`benchmarks/tasks.json`)

Each task is a JSON object with:
- `id` — unique identifier
- `description` — natural-language instruction given to the agent
- `project` — target project (checked-out at a specific git commit)
- `operations` — expected sequence of tool calls
- `validation` — how to verify correctness (compile, test, RefactoringMiner, git-diff shape)

Example:

```json
[
  {
    "id": "rename-001",
    "description": "Rename the field 'usrNm' in class com.example.User to 'username'.",
    "project": "sample-java-project",
    "operations": [
      { "tool": "find_symbol_by_name", "params": { "qualifiedName": "com.example.User#usrNm" } },
      { "tool": "rename_symbol",       "params": { "qualifiedName": "com.example.User#usrNm", "newName": "username" } }
    ],
    "validation": { "type": "compile_and_refactoringminer", "expectedRefactoringType": "Rename Attribute" }
  },
  {
    "id": "extract-001",
    "description": "Extract the selected block in UserService.processRequest() into a new method called 'validateInput'.",
    "project": "sample-java-project",
    "operations": [
      { "tool": "find_symbol_by_name", "params": { "qualifiedName": "com.example.UserService#processRequest" } }
    ],
    "validation": { "type": "compile_and_refactoringminer", "expectedRefactoringType": "Extract Method" }
  },
  {
    "id": "move-001",
    "description": "Move class com.example.utils.DateHelper to package com.example.common.",
    "project": "sample-java-project",
    "operations": [
      { "tool": "find_symbol_by_name", "params": { "qualifiedName": "com.example.utils.DateHelper" } },
      { "tool": "move_class", "params": { "qualifiedClassName": "com.example.utils.DateHelper", "targetPackage": "com.example.common" } }
    ],
    "validation": { "type": "compile_and_refactoringminer", "expectedRefactoringType": "Move Class" }
  },
  {
    "id": "safe-delete-001",
    "description": "Safe-delete the unused method com.example.LegacyHelper#parseOldFormat.",
    "project": "sample-java-project",
    "operations": [
      { "tool": "find_symbol_by_name", "params": { "qualifiedName": "com.example.LegacyHelper#parseOldFormat" } },
      { "tool": "safe_delete", "params": { "qualifiedName": "com.example.LegacyHelper#parseOldFormat" } }
    ],
    "validation": { "type": "compile_and_no_reference" }
  },
  {
    "id": "create-001",
    "description": "Create a new interface PaymentGateway in package com.example.payments with a single method 'charge(double amount): boolean'.",
    "project": "sample-java-project",
    "operations": [
      { "tool": "create_java_file", "params": {
        "packageName": "com.example.payments",
        "fileName": "PaymentGateway.java",
        "content": "package com.example.payments;\npublic interface PaymentGateway { boolean charge(double amount); }"
      }}
    ],
    "validation": { "type": "compile_and_file_exists", "expectedFile": "com/example/payments/PaymentGateway.java" }
  },
  {
    "id": "change-sig-001",
    "description": "Change the signature of com.example.Notifier#send to add a second parameter 'priority: int' with default value 0.",
    "project": "sample-java-project",
    "operations": [
      { "tool": "change_signature", "params": {
        "qualifiedName": "com.example.Notifier#send",
        "parameterChanges": [
          { "name": "message", "type": "String", "oldIndex": "0" },
          { "name": "priority", "type": "int", "oldIndex": "-1", "defaultValue": "0" }
        ]
      }}
    ],
    "validation": { "type": "compile_and_refactoringminer", "expectedRefactoringType": "Add Parameter" }
  }
]
```

---

## 4. Benchmark Runner (`benchmarks/run_benchmarks.py`)

```python
#!/usr/bin/env python3
"""
Benchmark runner for the structured-refactoring-agent.

Usage:
  python benchmarks/run_benchmarks.py \
    --tasks benchmarks/tasks.json \
    --agent-port 6473 \
    --api-key $ANTHROPIC_API_KEY \
    --out results/run-$(date +%Y%m%d-%H%M%S).json

Requirements:
  pip install anthropic requests
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


def run_task_with_agent(task: dict, port: int, api_key: str) -> dict:
    """Drive the agent with a natural-language instruction and collect tool calls."""
    schema_r = requests.get(TOOL_SCHEMA_URL.format(port=port), timeout=5)
    tools = schema_r.json()

    client = anthropic.Anthropic(api_key=api_key)
    messages = [{"role": "user", "content": task["description"]}]
    tool_calls_made = []
    turns = 0
    max_turns = 10

    while turns < max_turns:
        response = client.messages.create(
            model="claude-sonnet-4-6",
            max_tokens=2048,
            tools=tools,
            messages=messages,
        )
        turns += 1

        tool_results = []
        for block in response.content:
            if block.type == "tool_use":
                result = call_tool(port, block.name, block.input)
                tool_calls_made.append({"tool": block.name, "params": block.input, "result": result})
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

    # Check expected tool sequence was followed (soft check)
    expected_ops = task.get("operations", [])
    actual_tools = [c["tool"] for c in agent_result["tool_calls"]]
    expected_tools = [op["tool"] for op in expected_ops]
    if set(expected_tools) <= set(actual_tools):
        notes.append(f"Expected tools {expected_tools} all called.")
    else:
        missing = set(expected_tools) - set(actual_tools)
        notes.append(f"Missing expected tools: {missing}")
        passed = False

    # Check all tool calls returned ok=true
    failed_calls = [c for c in agent_result["tool_calls"] if not c["result"].get("ok", False)]
    if failed_calls:
        passed = False
        for fc in failed_calls:
            notes.append(f"Tool {fc['tool']} returned error: {fc['result'].get('error', '?')}")

    return {"passed": passed, "notes": notes, "validation_type": vtype}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks",     required=True)
    parser.add_argument("--agent-port", type=int, default=6473)
    parser.add_argument("--api-key",   required=True)
    parser.add_argument("--out",       default="results/run.json")
    args = parser.parse_args()

    if not check_server(args.agent_port):
        print(f"ERROR: Agent server not running on port {args.agent_port}. Start IntelliJ with the plugin first.")
        sys.exit(1)

    tasks = json.loads(Path(args.tasks).read_text())
    results = []

    for task in tasks:
        print(f"Running task {task['id']}: {task['description'][:60]}...")
        t0 = time.time()
        try:
            agent_result = run_task_with_agent(task, args.agent_port, args.api_key)
            validation = validate(task, agent_result)
            status = "PASS" if validation["passed"] else "FAIL"
        except Exception as e:
            agent_result = {"tool_calls": [], "turns": 0}
            validation = {"passed": False, "notes": [str(e)], "validation_type": "error"}
            status = "ERROR"
        elapsed = time.time() - t0
        print(f"  {status} in {elapsed:.1f}s ({agent_result['turns']} turns)")
        results.append({
            "id": task["id"],
            "description": task["description"],
            "status": status,
            "elapsed_s": round(elapsed, 2),
            "turns": agent_result["turns"],
            "tool_calls": agent_result["tool_calls"],
            "validation": validation,
        })

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps({"tasks": results}, indent=2))

    passed = sum(1 for r in results if r["status"] == "PASS")
    print(f"\nResults: {passed}/{len(results)} passed. Written to {args.out}")


if __name__ == "__main__":
    main()
```

---

## 4. Metrics

| Metric | How measured |
|---|---|
| **Correctness** | `ok: true` from tool call + project compiles after operation |
| **RefactoringMiner match** | Run RefactoringMiner on git diff; check expected refactoring type detected |
| **Task completion rate** | % of benchmark tasks where all expected tools were called and all returned `ok: true` |
| **Symbol resolution accuracy** | % of `find_symbol_by_name` calls that resolved to the correct element (non-null) |
| **Turns to completion** | Number of API round-trips per task |
| **Baseline comparison** | Repeat same tasks with SWE-agent (text diffs) and measure compile rate |

---

## 5. Baseline

For RQ1, the text-substitution baseline uses a vanilla agent that applies refactorings by generating code text and overwriting files. The comparison metric is whether the project compiles after the operation and whether all original usages were updated.

Expected outcome: structured-refactoring-agent achieves ~100% compilation rate and full usage update; text-substitution baseline will have partial failures on cross-file renames and parameter reordering.

---

## 6. Developer Study (RQ4)

**Participants:** 10–15 final-year CS/EE students or junior developers.

**Task set:** 5 maintenance tasks on an unfamiliar Java project (rename, extract, move, safe-delete, add method).

**Conditions:**
- A: Manual IntelliJ (standard refactoring menu)
- B: GitHub Copilot Chat inside IntelliJ
- C: Structured-refactoring-agent chat panel

**Measures:**
- Time to complete each task
- Number of errors requiring undo
- Post-task NASA-TLX cognitive load questionnaire
- Semi-structured interview: "Did you trust the change was correct without checking?"

---

## 7. Expected Contributions from Evaluation

1. Empirical confirmation that AST-safe execution eliminates compilation failures for scope-preserving refactorings (rename, move, change-signature).
2. Evidence that `find_symbol_by_name` + qualified-name resolution reduces agent error rate on symbol lookup vs. offset-based approaches.
3. Comparison data positioning the structured-refactoring-agent vs. SWE-agent (text patches) on real-world Java refactorings.
