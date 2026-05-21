# Related Work: Structured Refactoring with LLM Agents

Literature survey compiled 2025-05 for the MSc FYP at Imperial College London.

---

## 1. LLM-Driven Code Refactoring

### [1.1] An Empirical Study on the Code Refactoring Capability of Large Language Models
**ACM TOSEM, 2024** · Cordeiro, Noei, Zou · arXiv:[2411.02320](https://arxiv.org/abs/2411.02320)

Evaluates StarCoder2, GPT-4o, GPT-4o-mini, LLaMA 3, and DeepSeek-v3 on 30 open-source Java projects. LLMs outperform developers on systematic/repetitive refactorings but fall short on context-heavy architectural ones.

**Relation:** Demonstrates the core problem this project solves — LLM-generated refactorings are text substitutions with no correctness guarantees. The structured-refactoring-agent sidesteps this by delegating execution to IntelliJ's PSI APIs; correctness is guaranteed by the IDE.

---

### [1.2] An Empirical Study on the Potential of LLMs in Automated Software Refactoring
**Automated Software Engineering (Springer), 2024** · arXiv:[2411.04444](https://arxiv.org/abs/2411.04444)

Studies 180 real-world refactorings across 20 projects. ChatGPT identifies only 28 of 180 opportunities unaided, but reaches 86.7% success when the refactoring sub-category is pre-specified.

**Relation:** When the LLM selects from a fixed menu of operation types (as in our tool schema) rather than generating free-form transformations, accuracy improves substantially — directly validating the tool-API design.

---

### [1.3] SWE-Refactor: A Repository-Level Benchmark for Real-World LLM-Based Code Refactoring
**arXiv, 2025** · arXiv:[2602.03712](https://arxiv.org/abs/2602.03712)

1,099 developer-written, behaviour-preserving Java refactorings from 18 projects (validated via compilation + tests + RefactoringMiner). Best LLM pass rate: **41.6%** (GPT-4o-mini).

**Relation:** The 41.6% ceiling for text-generation approaches motivates the structured, IDE-executed alternative — our rename and safe-delete are correct-by-construction and don't appear in this benchmark at all.

---

### [1.4] LLM-Driven Code Refactoring: Opportunities and Limitations
**IDE Workshop @ ICSE 2025** · [PDF](https://seal-queensu.github.io/publications/pdf/IDE-Jonathan-2025.pdf)

Catalogues failure modes (hallucinated edits, broken semantics, scope blindness) and identifies IDE integration as a mitigation pathway.

**Relation:** Directly motivates this project; the "limitations" it identifies are precisely what AST-safe tool APIs address.

---

## 2. Program Transformation Agents

### [2.1] SWE-agent: Agent-Computer Interfaces Enable Automated Software Engineering
**NeurIPS 2024** · Yang, Jimenez et al. (Princeton) · arXiv:[2405.15793](https://arxiv.org/abs/2405.15793)

Introduces the Agent-Computer Interface (ACI) abstraction — a shell-like toolset (view, search, edit, run) that lets an LLM agent resolve real GitHub issues. Achieved 12.5% on SWE-bench at release.

**Relation:** Closest architectural analogue to this project's HTTP tool surface, but SWE-agent's "edit" tool does raw text replacement with no AST awareness. The structured-refactoring-agent replaces text-edit with semantically typed IntelliJ operations.

---

### [2.2] OpenHands (OpenDevin): An Open Platform for AI Software Developers as Generalist Agents
**ICLR 2025** · arXiv:[2407.16741](https://arxiv.org/abs/2407.16741)

A sandboxed multi-agent platform where agents interact with a docker-contained OS (bash, browser, Python REPL) for software engineering tasks.

**Relation:** Generalist OS-level agent. The structured-refactoring-agent is narrower but safer: it exposes only semantically valid IDE operations, making it unsuitable for arbitrary tasks but ideal for controlled, correct transformations.

---

### [2.3] AutoCodeRover: Autonomous Program Improvement
**ISSTA 2024** · Zhang, Ruan, Fan, Roychoudhury · arXiv:[2404.05427](https://arxiv.org/abs/2404.05427)

Uses AST-backed code search APIs (class signatures, method bodies, call graphs) to locate bug context, then generates text patches. Resolved 19%+ of SWE-bench-lite tasks at ~$0.43/task.

**Relation:** AutoCodeRover uses AST for *read-only context retrieval* only; patch application is raw text diff. The structured-refactoring-agent uses the IDE's AST for *write operations* too — execution is AST-safe, not just discovery.

---

## 3. AST-Safe / Structured Refactoring with AI

### [3.1] EM-Assist: Safe Automated Extract-Method Refactoring with LLMs
**FSE 2024** · arXiv:[2405.20551](https://arxiv.org/abs/2405.20551) · [ACM DL](https://dl.acm.org/doi/10.1145/3663529.3663803)

An IntelliJ IDEA plugin that uses an LLM to *suggest* code ranges to extract, then validates and executes those suggestions through IntelliJ's PSI/refactoring engine. 53.4% Recall@5 on 1,752 real refactorings; 94.4% positive developer rating.

**Relation:** The most architecturally similar prior system. Uses IntelliJ PSI for safe execution. **Key difference:** EM-Assist is single-operation (extract-method only), hardwired inside the IDE with no external agent API. The structured-refactoring-agent exposes a multi-operation HTTP API callable by any external LLM, enabling agentic multi-step workflows.

---

### [3.2] Together We Are Better: LLM, IDE and Semantic Embedding to Assist Move Method Refactoring
**ICSME 2025** · Bryksin et al. · arXiv:[2503.20934](https://arxiv.org/abs/2503.20934)

Combines LLM suggestions with IDE-based static analysis (to filter hallucinations) and RAG over prior refactorings. 73% Recall@1, 82% Recall@3 — ~4x over prior tools.

**Relation:** Validates the LLM + IDE correctness-oracle pattern. Single-op, not an externally callable API.

---

### [3.3] MANTRA: Enhancing Automated Method-Level Refactoring with Contextual RAG and Multi-Agent LLM Collaboration
**arXiv, 2025** · Xu, Lin, Yang, Chen, Tsantalis · arXiv:[2503.14340](https://arxiv.org/abs/2503.14340)

Multi-agent LLM pipeline (RAG + planner + self-repair) for Extract Method; 82.8% of outputs compile and pass tests; outperforms EM-Assist by 50%.

**Relation:** Orchestrates multiple LLM agents for *generation*, still produces text-level replacements. The structured-refactoring-agent never writes code text — the LLM decides *what* and the IDE decides *how*, eliminating compilation concerns for semantics-preserving operations.

---

## 4. AI-Assisted IDE Plugins

### [4.1] Evaluating Code Quality of AI-Assisted Code Generation Tools
**arXiv, 2023** · arXiv:[2304.10778](https://arxiv.org/abs/2304.10778)

Benchmarks Copilot, CodeWhisperer, and ChatGPT on validity, correctness, security, and maintainability. ChatGPT 65.2% correct, Copilot 46.3%.

**Relation:** These tools generate code via token prediction with no IDE refactoring API access. Renames are text substitutions that miss call sites. The structured-refactoring-agent targets exactly the tasks these tools handle incorrectly.

---

### [4.2] The Impact of AI on Developer Productivity: Evidence from GitHub Copilot
**arXiv, 2023** · arXiv:[2302.06590](https://arxiv.org/abs/2302.06590)

Developers using Copilot completed tasks 55.8% faster in controlled experiments, but struggle with understanding, editing, and debugging generated code.

**Relation:** Establishes value of in-IDE AI. The structured-refactoring-agent targets a complementary niche — transformation *safety* rather than generation speed, for maintenance tasks where correctness is non-negotiable.

---

## 5. Tool Use / Function Calling for Code Editing

### [5.1] Demystifying LLM-Based Software Engineering Agents
**PACMSE, 2025** · [ACM DL](https://dl.acm.org/doi/abs/10.1145/3715754)

Systematic taxonomy of LLM SE agents by tool repertoire, memory, action space, and coordination. Identifies the tension between generality (shell access) and safety (constrained action spaces).

**Relation:** Provides theoretical framing that positions the structured-refactoring-agent as a *constrained-action-space* agent — its tool API exposes only type-safe, semantically defined operations rather than a Turing-complete shell.

---

### [5.2] Aider *(system)*
**2023–present** · [aider.chat](https://aider.chat)

Terminal-based LLM coding assistant using whole-file or diff-format edits committed to git. No AST awareness.

**Relation:** State-of-practice baseline. Aider operates at the syntactic (text) layer; the structured-refactoring-agent operates at the semantic (symbol) layer.

---

## 6. Refactoring Detection and Recommendation

### [6.1] RefactoringMiner 3.0
**IEEE TSE 48(3), 2022 (v2) / FSE 2025 (v3, C++)** · Tsantalis et al. · arXiv:[2502.17716](https://arxiv.org/abs/2502.17716)

State-of-the-art tool for detecting refactoring operations in Java/C++ commit histories using refactoring-aware AST differencing. Used as a ground-truth oracle in most LLM refactoring benchmarks above.

**Relation:** RefactoringMiner is the *detection* layer; the structured-refactoring-agent is the *execution* layer. RefactoringMiner could be used to evaluate that the agent's HTTP operations produced the expected refactoring type in git history.

---

### [6.2] What Were You Thinking? An LLM-Driven Large-Scale Study of Refactoring Motivations
**arXiv, 2025** · arXiv:[2509.07763](https://arxiv.org/abs/2509.07763)

Uses LLMs to mine and classify motivations behind 10,000+ real-world refactorings, building a taxonomy of when developers refactor (readability, feature preparation, technical debt, etc.).

**Relation:** Provides human-motivation grounding for when an agent should *suggest* refactorings. The structured-refactoring-agent could use this taxonomy as a trigger library during code review or feature development.

---

## Summary Table

| # | Paper / System | Venue | Year | How it differs from this project |
|---|---|---|---|---|
| 1.1 | Empirical Study — LLM Refactoring Capability | ACM TOSEM | 2024 | LLM generates text; no IDE execution |
| 1.3 | SWE-Refactor benchmark | arXiv | 2025 | Benchmark only; text-gen ceiling 41.6% |
| 2.1 | SWE-agent | NeurIPS | 2024 | ACI is shell/text; no AST writes |
| 2.2 | OpenHands | ICLR | 2025 | Generalist OS-level; no semantic ops |
| 2.3 | AutoCodeRover | ISSTA | 2024 | AST read-only; text patches for writes |
| 3.1 | EM-Assist | FSE | 2024 | Single-op IntelliJ plugin; no external API |
| 3.2 | Move Method (LLM+IDE+Embedding) | ICSME | 2025 | Single-op; no agent-callable HTTP API |
| 3.3 | MANTRA | arXiv | 2025 | Multi-agent text generation; not PSI ops |
| 4.1 | Copilot/CodeWhisperer study | arXiv | 2023 | Autocomplete; no refactoring semantics |
| 5.1 | Demystifying LLM SE Agents | PACMSE | 2025 | Taxonomy paper; frames constrained ACI |
| 6.1 | RefactoringMiner 2.0 / 3.0 | TSE / FSE | 2022–25 | Detection oracle, not execution engine |
| 6.2 | Refactoring Motivations study | arXiv | 2025 | Motivation taxonomy for agent triggers |
