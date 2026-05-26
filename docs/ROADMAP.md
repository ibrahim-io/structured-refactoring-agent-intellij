# Project Roadmap / TODO Tracker

**Project:** Structured Refactoring Agent for IntelliJ  
**Degree:** Imperial College London MEng (AI & Machine Learning) Final Year Project

The project goal is to show that AI coding assistants become more robust when
their edit primitive is a structured AST/refactoring operation rather than a raw
text patch.

## 1. Core Plugin

- [x] Expose IntelliJ refactoring operations through localhost HTTP tools.
- [x] Add symbol lookup by qualified name so agents do not need byte offsets.
- [x] Add `read_file` and `find_usages` for project understanding.
- [x] Add PSI creation tools for fields, methods, inner classes, and Java files.
- [x] Add Kotlin creation tools behind optional Kotlin plugin support.
- [x] Add move class and change signature tools.
- [x] Add extract method and extract variable tools.
- [x] Add inline method tool.
- [x] Fix disk persistence and IntelliJ VFS staleness after benchmark resets.
- [x] Improve Java class rename so public class renames also guarantee file rename.
- [ ] Add stronger precondition checks for unsafe delete/inline operations.
- [ ] Add structured support for non-Java assets affected by Java refactors where relevant.

## 2. Benchmark Infrastructure

- [x] Build structured agent benchmark runner.
- [x] Build Claude text-edit benchmark runner.
- [x] Add multi-layer validation: tool result, compile, disk content, optional RefactoringMiner.
- [x] Add no-API direct structured runner for petclinic.
- [x] Add no-API scripted text-edit baseline runner.
- [x] Add result comparison utility.
- [ ] Add a unified benchmark summary command for any two result files.
- [x] Add CSV export for thesis tables.
- [ ] Add automatic environment checks: server project name, Maven availability, git cleanliness.
- [ ] Add failure categorisation: tool failure, compile failure, validation failure, agent planning failure.

## 3. Evaluation Projects

- [x] Create focused sample Java project with cross-file refactoring tasks.
- [x] Expand sample project to 8 tasks including inline and change signature.
- [x] Prepare spring-petclinic benchmark tasks.
- [x] Run no-API structured direct petclinic suite.
- [x] Run no-API scripted text-edit petclinic suite.
- [ ] Run full API structured petclinic suite when credits are available.
- [ ] Run full API text-edit petclinic suite when credits are available.
- [x] Add harder petclinic tasks that involve templates, tests, repositories, and controllers.
- [ ] Add one larger 100+ file Java project if petclinic is still too easy.

## 4. Cross-Agent Comparison

- [x] Define agent-agnostic thesis framing: the structured backend is model independent.
- [x] Document how to run Codex against the same task set without Anthropic API usage.
- [x] Document how to run Gemini CLI / Gemini Code Assist against the same task set.
- [x] Record whether each agent used subscription quota, free tier, or paid API.
- [ ] Compare structured direct vs Codex text-edit vs Gemini text-edit vs Claude text-edit.
- [ ] If feasible, expose the IntelliJ tool API as an MCP server so non-Claude agents can call the same structured tools.

## 5. Thesis Evidence

- [x] Document related work and how this repository extends it.
- [x] Document the sample-project result: both agents pass, structured uses fewer tool calls.
- [x] Document why small projects understate the advantage of structured tools.
- [x] Document petclinic no-API direct results.
- [ ] Document petclinic API results when available.
- [ ] Create final result tables: correctness, compile pass rate, tool calls, turns, wall time.
- [ ] Write analysis of failure modes for text-edit agents.
- [ ] Write analysis of failure modes for structured tools.
- [ ] Connect results back to the central research claim.

## 6. Product Polish

- [x] Add settings UI for port, model, and max turns.
- [x] Add in-IDE chat panel.
- [ ] Add a visible tool-call transcript export button.
- [ ] Add benchmark result import/export UI or command.
- [ ] Improve README setup path for a fresh evaluator.
- [ ] Add screenshots or diagrams of the architecture and benchmark flow.

## Current Next Moves

- [x] Implement no-API petclinic direct structured runner.
- [x] Implement no-API scripted text-edit baseline.
- [x] Run full no-API petclinic direct structured suite.
- [x] Run full no-API petclinic scripted text-edit suite.
- [x] Compare and document the result.
- [x] Fix RenameProcessor: class rename now guarantees file rename.
- [x] Add OpenAI provider support to both benchmark runners (`--provider openai`).
- [x] Expand petclinic task suite from 5 → 9 tasks with harder cross-file cases.
- [x] Add CSV export to compare_results.py (`--csv out.csv`).
- [ ] Run direct structured suite on the 4 new petclinic tasks (requires IntelliJ on spring-petclinic).
- [ ] Run full API petclinic suite (structured + text-edit) with OpenAI or Anthropic credits.
- [ ] Document API petclinic results in EVALUATION.md.
