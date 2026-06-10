# Bibliography verification record

This is the audit trail for `references.bib`. Every cited reference was checked
against the primary source (arXiv abstract page, publisher DOI page, or official
proceedings) in **June 2026**. The checks confirmed each paper exists, that the
cited identifier resolves to *that* paper, and that the specific claim the report
attributes to it is actually supported. Where a mismatch was found it is recorded
below together with the correction applied. Referencing accuracy is explicitly
assessed (Communication band), so this record exists to make every citation
defensible in the viva.

> **Method note (GenAI disclosure).** The verification sweep was AI-assisted
> (web search + source fetch per reference), then reviewed by the author. The
> corrections listed under "Mismatches found and fixed" were applied to
> `references.bib` and the chapter prose. The author is responsible for the final
> citations and should spot-check the flagged entries.

## Status of every cited reference

| Key | Identifier | Resolves? | Claim verdict | Action taken |
|---|---|---|---|---|
| `swerefactor2025` | arXiv:2602.03712 | ✓ | supported — 1,099 refactorings; DeepSeek-V3 best at **41.58%** | authors filled (Xu, Yang, Chen) |
| `difffuzzing2026` | arXiv:2602.15761 | ✓ | supported — differential fuzzing for functional equivalence | authors filled (Dristi, Dwyer) |
| `agenticrefactoring2025` | arXiv:2511.04824 | ✓ | supported — 15,451 refactorings; rename-parameter 10.4%; DesigniteJava | none needed |
| `cordeiro2024refactoring` | arXiv:2411.02320 | ✓ | **partial** — model list wrong | prose: studies **StarCoder2 only** (not a 5-model study); author fixed Shaowei→**Shayan** Noei |
| `potentialllm2024` | arXiv:2411.04444 | ✓ | supported — 180 refactorings / 20 projects; ChatGPT 28→86.7% | authors filled (Liu et al.) |
| `movemethod2025` | arXiv:2503.20934 | ✓ | supported — 73% Recall@1 (MM-Assist) | author order corrected (Bellur et al., not Bryksin-first) |
| `mantra2025` | arXiv:2503.14340 | ✓ | **supported but mis-scoped** | prose: 82.8% is the **overall** rate across six refactoring types, not extract-method-specific |
| `refactoringminer2025` | ~~arXiv:2502.17716~~ → **arXiv:2403.05939** | ✗→✓ | supported (general SOTA claim) | **ID replaced** — 2502.17716 was a *different* paper (RefactoringMiner++ for C++); now Alikhanifard & Tsantalis, TOSEM 2024 |
| `motivations2025` | arXiv:2509.07763 | ✓ | supported (LLM study of refactoring motivations) | authors filled (Robredo et al.) |
| `demystifying2025` | doi:10.1145/3715754 | ✓ | **contradicted description** | prose rewritten — paper is *Agentless* (Xia et al.), not a taxonomy; the constrained-action-space point it supports is retained; authors filled |
| `copilotproductivity2023` | arXiv:2302.06590 | ✓ | supported — Copilot RCT, 55.8% faster | authors filled (Peng, Kalliamvakou, Cihon, Demirer) |
| `sweagent2024` | arXiv:2405.15793 | ✓ | supported — ACI; edit = raw text replacement | authors completed |
| `openhands2025` | arXiv:2407.16741 | ✓ | supported — sandboxed shell/browser/code-exec | authors filled (Wang et al.; senior Neubig) |
| `autocoderover2024` | arXiv:2404.05427 | ✓ | supported — AST search for read, text patch for write | none needed |
| `emassist2024` | arXiv:2405.20551 | ✓ | supported — IntelliJ extract-method, single op | authors filled (Pomian, Bellur, …, Dig) |
| `llmrefactorlimits2025` | doi:10.1109/IDE66625.2025.00011 | ✓ | partial — position paper; "scope blindness" is the author's gloss | authors filled (Cordeiro, Noei, Zou); DOI added |
| `aider` | website (aider.chat) | ✓ | supported — file/diff edits, git, no AST | author added (Gauthier) |
| `hou2024llm4se` *(replaced `aicodeopt2025`)* | arXiv:2308.10620; doi:10.1145/3695988 | ✓ | supported — LLM4SE survey; §8.1.4 interpretability/trustworthiness | **swapped in** (TOSEM 2024, 395-paper SLR) |
| `refactoringconversations2024` *(replaced `interactiveagent2025`)* | arXiv:2402.06013; doi:10.1145/3643991.3645081 | ✓ | supported — 17,913 developer–ChatGPT refactoring conversations | **swapped in** (MSR 2024, CORE-A) |

`aicodequality2023` (arXiv:2304.10778) is present in `references.bib` but uncited;
removing the temporary `\nocite{*}` means it no longer renders, so it was left
unverified and does not appear in the bibliography.

## Mismatches found and fixed (the four substantive ones)

1. **`refactoringminer2025` — broken citation pointer.** The cited
   `arXiv:2502.17716` resolves to *"Refactoring Detection in C++ Programs with
   RefactoringMiner++"* (Ritz et al., FSE 2025) — a different paper by different
   authors — and *"RefactoringMiner 3.0"* is not a real paper title. Replaced with
   the genuine Tsantalis-group paper behind RefactoringMiner's AST differencing:
   Alikhanifard & Tsantalis, *"A Novel Refactoring and Semantic Aware Abstract
   Syntax Tree Differencing Tool…"*, TOSEM 2024, **arXiv:2403.05939**.

2. **`demystifying2025` — wrong description.** The DOI is correct, but the paper is
   *Agentless* (Xia, Deng, Dunn, Zhang), which argues a simple constrained pipeline
   beats elaborate agents — **not** "a systematic taxonomy by tool repertoire,
   memory, action space, and coordination." §2.5 was rewritten to describe what the
   paper actually argues; the action-space-vs-generality point it is cited for is
   genuinely supported by the paper's thesis.

3. **`cordeiro2024refactoring` — wrong model list.** The paper evaluates only
   **StarCoder2** (StarCoder2-15B-instruct) against human developers, not
   "StarCoder2, GPT-4o, GPT-4o-mini, LLaMA 3 and DeepSeek-v3." §2.2 corrected. The
   "thirty projects" figure and the architectural-refactoring finding are accurate.

4. **`mantra2025` — mis-scoped figure.** The 82.8% (582/703) compile-and-pass rate
   is across **six** method-level refactoring types, not Extract Method alone. §2.4
   reworded.

The single most load-bearing figure — the **41.6%** repository-level ceiling
(`swerefactor2025`) used in the abstract and introduction — was **confirmed**
against the primary source (DeepSeek-V3, 41.58%, rounded to 41.6%).

## Predatory / low-credibility venues — replaced

Two original entries were at venues with predatory signals (no real indexing,
citation rings, gmail corresponding authors, document artefacts in the PDF). The
claims were technically accurate but the venues were a Communication-mark risk, so
both were **replaced with reputable, verified, peer-reviewed sources**:

- §2.2 landscape-survey + trust/explainability framing: ~~`aicodeopt2025`
  (*Journal of Data & Digital Innovation*)~~ → **`hou2024llm4se`** — Hou et al.,
  *"Large Language Models for Software Engineering: A Systematic Literature
  Review"*, TOSEM 2024 (arXiv:2308.10620). The canonical 395-paper LLM4SE review;
  §8.1.4 covers interpretability/trustworthiness challenges. The §2.2 sentence was
  reworded to match the review's actual findings.
- §2.4 human-centred / interactive-refactoring contrast:
  ~~`interactiveagent2025` (*Academia Nexus Journal*)~~ →
  **`refactoringconversations2024`** — AlOmar et al., *"How to Refactor this Code?
  An Exploratory Study on Developer-ChatGPT Refactoring Conversations"*, MSR 2024
  (arXiv:2402.06013). A text-mining study of 17,913 developer–ChatGPT refactoring
  conversations. §2.4 was reworded accordingly.

## Pre-submission housekeeping (done)

- `\nocite{*}` removed from `main.tex` — the bibliography now lists only the
  cited works (19 entries), with no unfilled author placeholders.
- Citation style is `plainnat` (numeric), set in `main.tex`; applied consistently.
