# Report Writing Guide

Working scaffold for the MEng final report. **Local LaTeX, in the repo.** You are
the author; this scaffold provides structure, per-section guidance (in `.tex`
comments and red `[WRITE: …]` placeholders), and a pre-seeded bibliography.

## ⚠️ Confirm before you write much
- **Page limit.** Unknown — it was in the (blank) "Full List of Deliverables"
  table on the Confluence page. **A report over the limit is capped at 40–49%
  for Communication (15%).** Find the exact number and put it here: `LIMIT = ___`.
- **Do appendices / references count toward the limit?** Check — it changes how
  much detail you push to the appendix.
- **Supervisor repo access** granted? Required, and the repo link goes in the
  Declarations page (`main.tex`).
- **Interim report** accepted? Required to pass.

## How to build
You have MiKTeX. `latexmk` needs Perl (not installed here) and will fail with
"could not find the script engine 'perl'" — use the `pdflatex` sequence instead:
```powershell
cd report
pdflatex -interaction=nonstopmode main.tex
bibtex main
pdflatex -interaction=nonstopmode main.tex
pdflatex -interaction=nonstopmode main.tex
```
First run is slow while MiKTeX auto-installs packages. Verified building cleanly:
19 pages, 0 errors. (Overleaf would also work — it imports the scaffold as-is and
runs `latexmk` there, since Overleaf has Perl.)

**Before final submission:** remove the `\nocite{*}` line above the bibliography
in `main.tex` (it currently force-renders all 16 refs so you can preview them;
once you cite real sources, drop it so only cited works appear).

## Marking weights → where the marks are
| Criterion | Weight | Chapters that earn it |
|---|---|---|
| Framing of research problem | 15% | 1 Introduction, 2 Background |
| **Execution & technical quality** | **50%** | 3 Design, 4 Implementation, 5 Evaluation |
| Evaluation & reflection | 20% | 5 Evaluation, 6 Conclusion |
| Communication | 15% | whole report: structure, clarity, refs, GenAI declaration |

## Page budget (proportional — rescale to your real limit)
Example for a **~50-page body**; rescale once `LIMIT` is known.

| Chapter | Share | ~Pages |
|---|---:|---:|
| 1 Introduction | 10% | 5 |
| 2 Background & Related Work | 20% | 10 |
| 3 Design | 15% | 7–8 |
| 4 Implementation | 20% | 10 |
| 5 Evaluation | 25% | 12–13 |
| 6 Conclusion & Reflection | 10% | 5 |

Evaluation gets the most pages because it carries 20% directly and a large slice
of the 50%.

## Doc → chapter mapping (you already wrote most of this)
| Chapter | Pull from |
|---|---|
| 1 Introduction | `docs/PROJECT_CONTEXT.md` (Problem, Contribution, Novelty) |
| 2 Background | `docs/RELATED_WORK.md` (≈90% done — just elevate to prose) |
| 3 Design | `docs/PROJECT_CONTEXT.md` (architecture, tool taxonomy) |
| 4 Implementation | `README.md` layout + tool tables + the two fixes in `docs/EVALUATION.md` |
| 5 Evaluation | `docs/EVALUATION.md` + `docs/CROSS_AGENT_EVALUATION.md` + `results/*.json` |
| 6 Conclusion | `docs/EVALUATION.md` §9 (refined hypothesis), §10 (study) |

## The five highest-leverage things (do in this order)
1. **Measure, don't predict.** Run structured-LLM *and* text-edit-LLM on the
   9-task petclinic suite; replace every `PASS (expected)` with a real number.
   (Commands: `docs/EVALUATION.md` §6–7b.) This is the backbone of 70% of the mark.
2. **GenAI declaration** (in `main.tex`) — honest, specific, and you must be able
   to defend every artifact in the presentation. Protects 15% + the degree.
3. **Threats to validity** + **Broader impact** subsections (Ch. 5 and 6) — both
   currently missing and both explicitly rewarded above 60%.
4. **Hedge the "first/only" novelty claims** + a contemporaneous-work paragraph
   (JetBrains MCP etc.) so Framing stays credible.
5. **Reframe as agent–computer-interface research**; if time, add the GPT-4o
   cross-model run to make the "AI" contribution concrete.

## Suggested schedule (finish Wed 11 Jun; submit a full day early)
| Day | Focus |
|---|---|
| Wed 3 Jun | Template + skeleton (done); lock page limit; draft Introduction. Kick off petclinic runs in parallel. |
| Thu 4 Jun | Background/Related Work (elevate `RELATED_WORK.md`). |
| Fri 5 Jun | Design/Architecture. |
| Sat 6 Jun | Implementation (incl. the two war-stories). |
| Sun 7 Jun | Evaluation I: methodology + sample-project measured results. |
| Mon 8 Jun | Evaluation II: petclinic measured results + root-cause + threats. |
| Tue 9 Jun | Conclusion, reflection, future work, broader impact, GenAI declaration, Declarations page. |
| Wed 10 Jun | Edit pass: figures, BibTeX, abstract, trim to limit, spell-check. |
| Thu 11 Jun | Final proof + supervisor sanity check + **submit**. |
| Fri 12 Jun 13:00 | Contingency only — never submit at 12:59. |

## Working agreement (GenAI rules)
I scaffold, coach, and edit *your* drafts. The intellectual content, the
verification of every claim and number, and the authorship are yours — and you
declare the assistance. Write each section, then hand it to me for a rubric-mapped
critique and line edits.
