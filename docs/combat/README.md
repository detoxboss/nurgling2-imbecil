---
doc_id: combat-readme
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Discovery index for all combat-reactor documentation"
---

# Combat reactor documentation — start here

Nurgling-native Haven & Hearth combat automation: reads `Fightview`/`Fightsess` state directly (no screen pixels), automatically fires the correct defensive restoration, and continuously recommends an attack (Quick Barrage / Full Circle / Sting) that only fires when the user presses a configured key. Ported from an AutoHotkey pixel-reading script; see [`../haven-combat-reactor-nurgling-port-brief.md`](../haven-combat-reactor-nurgling-port-brief.md) for the original requirements brief.

**This is a truth record, not a claim of correctness.** Several gaps were found during documentation and are recorded, not hidden — see [unresolved.md](unresolved.md) and the noncompliance table in [behavior-contract.md](behavior-contract.md#known-current-noncompliance-index).

**Implementation identity:** `HEAD 9d7404fa06eaf2dd99ac8f4eea44162298216b3b + uncommitted worktree` (no dedicated commit exists yet for this feature; all combat files are new/modified working-tree state as of 2026-07-27). Two unrelated pre-existing uncommitted files (`NCore.java`, `NGItem.java`, a tableware feature) are present in the same working tree and are explicitly **not** part of this feature — see [evidence.md](evidence.md#explicitly-excluded-from-this-evidence-set).

## Read this for…

| Task | Read |
|---|---|
| Game-mechanics facts (openings, IP, cooldowns, the attack trio) | [mechanics.md](mechanics.md) |
| What behavior is approved/intended, independent of code | [behavior-contract.md](behavior-contract.md) |
| What client state is actually readable, and by what name | [observable-state.md](observable-state.md) |
| Which class/method implements what, and known plan-vs-code variances | [code-map.md](code-map.md) |
| Designing a future configurable rule builder | [rule-system.md](rule-system.md) |
| Where a claim's source evidence comes from | [evidence.md](evidence.md) |
| What's actually been tested vs merely implemented | [verification.md](verification.md) |
| Open questions / known gaps | [unresolved.md](unresolved.md) |
| History of what changed and why | [changes/CHANGELOG.md](changes/CHANGELOG.md) |
| Why a lasting architecture choice was made | [decisions/README.md](decisions/README.md) |

## Authority / conflict rules

1. Current source ([code-map.md](code-map.md)) establishes only what the implementation *does*.
2. A passing build/manual check ([verification.md](verification.md)) establishes only what was actually exercised — matching code is not a passing test.
3. Explicit user-approved requirements ([behavior-contract.md](behavior-contract.md), `[Approved requirement]`) establish intended behavior.
4. Verified mechanics ([mechanics.md](mechanics.md)) constrain what behavior is even valid.
5. Accepted ADRs ([decisions/](decisions/README.md)) explain *why*; they are not current truth.
6. The implementation plan ([evidence.md](evidence.md)) establishes intent only — never cite it as proof code exists.
7. Historical AHK behavior (the R3 documents) is not automatically a Nurgling requirement — only what the brief/behavior-contract explicitly carried forward applies.
8. Every claim in this tree is either evidenced, or explicitly marked `[Unresolved question]`/`[Proposed behavior]`/`[Unresolved product decision]`. If you find an unlabeled unsupported claim, treat it as a documentation defect, not as truth.

**When code and an approved requirement disagree, [behavior-contract.md](behavior-contract.md#known-current-noncompliance-index) records the noncompliance. The requirement is never silently rewritten to match the code.**

## Minimum reading routes

- **Answering a mechanics question:** [mechanics.md](mechanics.md) → follow `EV-*` links into [evidence.md](evidence.md) if you need the primary source.
- **Changing behavior:** [behavior-contract.md](behavior-contract.md) (confirm it's actually approved, not just recommended) → [code-map.md](code-map.md) (find the class) → after implementing, update [verification.md](verification.md) and append to [changes/CHANGELOG.md](changes/CHANGELOG.md).
- **Changing implementation without changing behavior:** [code-map.md](code-map.md) only, plus a changelog entry; do not touch [behavior-contract.md](behavior-contract.md) unless the user approved a behavior change.
- **Debugging a reactor issue:** [code-map.md](code-map.md)'s decision-flow/manual-attack-flow diagrams → [observable-state.md](observable-state.md) to check whether the value involved is even `KNOWN` → [unresolved.md](unresolved.md) to check whether it's a known gap already.
- **Rule-builder/future-feature work:** [rule-system.md](rule-system.md) first — confirm whether what you're about to build is `[Approved]`, `[Proposed]`, or an `[Unapproved idea]` before writing code.

## Document revision/status table

| Document | Revision | Status | Last verified |
|---|---|---|---|
| README.md (this file) | 1 | current | 2026-07-27 |
| mechanics.md | 1 | current | 2026-07-27 |
| behavior-contract.md | 1 | current | 2026-07-27 |
| observable-state.md | 1 | current | 2026-07-27 |
| code-map.md | 1 | current | 2026-07-27 |
| rule-system.md | 1 | current | 2026-07-27 |
| evidence.md | 1 | current | 2026-07-27 |
| verification.md | 1 | current | 2026-07-27 |
| unresolved.md | 1 | current | 2026-07-27 |
| changes/CHANGELOG.md | 1 | current (append-only) | 2026-07-27 |
| decisions/README.md + ADR-0001..0006 | 1 | current (ADR-0006 Proposed, all others Accepted) | 2026-07-27 |

## Last cross-document audit

2026-07-27 — full link/path/ID/contradiction pass performed at initial authoring (see this session's final report for the checklist covered). Next audit should run after any combat-related change, per [`.claude/rules/combat-automation.md`](../../.claude/rules/combat-automation.md).
