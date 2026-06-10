# Bibliography verification checklist

`references.bib` is seeded from `docs/RELATED_WORK.md`. BibTeX has no inline `%`
comments, so the "things to confirm" live here. Verify each against the actual
paper before final submission — **referencing accuracy is explicitly assessed**
(Communication band), and your motivation depends on a couple of these figures.

## Must-fix before submission
- **`swerefactor2025` — citation integrity.** `RELATED_WORK.md` lists
  `arXiv:2602.03712` (= Feb 2026) but is dated "compiled 2025-05". The ID/year are
  inconsistent (possibly meant `2502`). Confirm the real arXiv ID **and** that the
  **"41.6% ceiling (GPT-4o-mini)"** figure is stated correctly — your Introduction's
  motivation rests on this single citation.

## Author lists to fill in (currently `{{Authors TODO --- verify}}`)
- `potentialllm2024` (arXiv:2411.04444)
- `swerefactor2025` (arXiv:2602.03712)
- `llmrefactorlimits2025` (IDE Workshop @ ICSE 2025)
- `openhands2025` (arXiv:2407.16741) — OpenHands/OpenDevin
- `emassist2024` (arXiv:2405.20551) — FSE 2024
- `aicodequality2023` (arXiv:2304.10778)
- `copilotproductivity2023` (arXiv:2302.06590)
- `demystifying2025` (PACMSE, doi:10.1145/3715754)
- `motivations2025` (arXiv:2509.07763)

## Author lists seeded but marked "and others" — complete them
- `sweagent2024` (Yang, Jimenez, …) — full Princeton author list
- `movemethod2025` (Bryksin, …)
- `mantra2025` (Xu, Lin, Yang, Chen, Tsantalis) — full first names
- `refactoringminer2025` (Tsantalis, …)

## General
- Replace `arXiv preprint` with the real venue where a paper has since been
  published (several may have proper proceedings now).
- Decide on one citation style (`plainnat` numeric is set in `main.tex`); IEEE or
  ACM style is also fine for CS — just be consistent.
