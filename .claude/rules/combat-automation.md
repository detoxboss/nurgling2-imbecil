---
description: Combat reactor (Nurgling-native H&H combat automation) — authority, safety, and documentation-maintenance rules
paths:
  - "src/nurgling/combat/**/*.java"
  - "src/nurgling/widgets/NCombatReactor.java"
  - "src/nurgling/widgets/nsettings/CombatReactorSettings.java"
  - "src/haven/Fightsess.java"
  - "src/haven/NFightsess.java"
  - "src/nurgling/NGameUI.java"
  - "src/nurgling/NConfig.java"
  - "src/nurgling/widgets/NSettingsWindow.java"
  - "**/*Combat*Test*.java"
  - "docs/combat/**/*.md"
---

Before combat-related work, read `docs/combat/README.md` first — it indexes mechanics, approved
behavior, code map, evidence, verification status, and unresolved items. Do not load the full
detailed docs into context speculatively; follow only the links `README.md` points you to for the
task at hand.

Rules while working in this area:

- Follow the authority model in `docs/combat/README.md` — code shows what *is*,
  `docs/combat/behavior-contract.md`'s `[Approved requirement]` tag shows what's *intended*. Never
  treat a recommendation, a plan, or a passing build as proof of approved behavior or of runtime
  correctness.
- Never convert `UNKNOWN`/`UNAVAILABLE`/`STALE` client state into `0`/`false` — this reactor's core
  safety property is fail-closed handling of unknown state
  (`docs/combat/behavior-contract.md#fail-closed-handling`). H&H is permadeath; a wrong automatic
  action from misread state is worse than doing nothing.
- Preserve relation-scoping (IP/openings never leak across a target change) and session-scoping (a
  reactor instance must resolve its own `GameUI` via `getparent(GameUI.class)`, never a
  global/thread-local lookup — see
  `docs/combat/decisions/ADR-0003-relation-scoped-fail-closed-state.md` for why this exact mistake
  once caused a crash).
- Every reactor entry point (`tick`, hotkey handling, `draw`) must stay wrapped so no exception can
  propagate to the UI thread. Do not remove or weaken the trip-switch safety latch without discussing
  it first.
- `src/haven/Fightsess.java`/`NFightsess.java` are shared with real keyboard combat input — verify
  byte-for-byte that any change there preserves normal (non-reactor) play before considering it done.
- Run `ant jar` as the verification gate (no dedicated combat test harness exists yet). State plainly
  when something is a manual-code-trace claim vs an actually-run check, matching
  `docs/combat/verification.md`'s status vocabulary (`PASS`/`FAIL`/`NOT RUN`/`NOT IMPLEMENTED`/
  `MANUAL PENDING`/`NOT TESTABLE WITH CURRENT HARNESS`).

This document-maintenance sequence is specific to combat's own audited-truth-record structure — it
does not apply to other features (see `docs/development-workflow.md` for the ordinary review workflow
elsewhere in the repo).

## After any combat-related change

1. Re-read the current canonical docs and source relevant to the change.
2. Identify which `MEC-*`/`BEH-*`/`ADR-*`/`UNR-*` IDs are affected.
3. Implement and verify (`ant jar`, plus any manual check you can actually perform).
4. Update only the affected canonical owners — one fact category, one owning file.
5. Update `docs/combat/code-map.md` for any topology/lifecycle change.
6. Update `docs/combat/behavior-contract.md` only after an explicit behavior approval — not for a
   recommendation.
7. Update `docs/combat/mechanics.md` only with adequate evidence — never upgrade a
   community/historical claim to verified.
8. Add or supersede an ADR under `docs/combat/decisions/` for any lasting architectural decision.
9. Append one entry to `docs/combat/changes/CHANGELOG.md` (never edit a past entry's substance).
10. Update `docs/combat/verification.md`'s matrix.
11. Review `docs/combat/unresolved.md` — close, tombstone, or add items as needed.
12. Check that links/paths/IDs still resolve and no contradiction was introduced across documents.
13. Update `revision`/`last_verified` only on documents you actually rechecked.
14. If nothing above actually changed, say so explicitly: **"No canonical documentation update
    required."**
