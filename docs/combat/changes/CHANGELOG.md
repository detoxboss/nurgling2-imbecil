---
doc_id: combat-changelog
revision: 1
status: current
last_verified: 2026-07-27
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Append-only history of combat-reactor changes"
---

# Changelog

Append-only. Never edit a past entry's substance — add a new entry instead, and tombstone-link from the old one if something it recorded was later superseded.

## 2026-07-27 — Initial Nurgling-native combat reactor port

- **Commit:** `uncommitted (baseline HEAD 9d7404fa06eaf2dd99ac8f4eea44162298216b3b)`. All combat files are untracked/modified working-tree changes; no dedicated commit exists yet for this feature.
- **Request/source:** User asked to port an existing AutoHotkey combat-deck automator (pixel/keystroke-based) into Nurgling2 as native client-state-reading automation, per [`docs/haven-combat-reactor-nurgling-port-brief.md`](../../haven-combat-reactor-nurgling-port-brief.md) (`EV-BRIEF`).
- **Why:** Replace screen-pixel recognition (fragile, error-prone per the R3 audit trail) with direct reads of `Fightview`/`Fightsess` state and dispatch through the same client message path normal combat input uses.
- **Changed behavior (contract IDs):** BEH-ENABLE-001, BEH-SCOPE-001, BEH-SCOPE-002, BEH-DEF-001, BEH-DEF-002, BEH-DEF-003, BEH-ATK-001, BEH-ATK-002, BEH-ATK-003, BEH-MANUAL-001, BEH-STING-001, BEH-STING-002, BEH-FAIL-001, BEH-UI-001. All newly introduced by this entry — no prior combat-reactor behavior existed in this codebase.
- **Code files:**
  - New: `src/nurgling/combat/CombatOpeningState.java`, `CombatMove.java`, `CombatSnapshot.java`, `CombatStateAdapter.java`, `CombatDecisionEngine.java`, `CombatActionExecutor.java`, `CombatReactorController.java`
  - New: `src/nurgling/widgets/NCombatReactor.java`
  - New: `src/nurgling/widgets/nsettings/CombatReactorSettings.java`
  - Modified: `src/haven/Fightsess.java` (extract-method refactor: `requestUse`/`releaseHeld`, behavior-preserving for normal keyboard input)
  - Modified: `src/haven/NFightsess.java` (added `requestAction`/`releaseAction` public wrappers)
  - Modified: `src/nurgling/NConfig.java` (added `Key.combatReactorEnabled`)
  - Modified: `src/nurgling/NGameUI.java` (widget attachment + `globtype` hotkey routing)
  - Modified: `src/nurgling/widgets/NSettingsWindow.java` (settings registration)
- **Documentation files:** this entire `docs/combat/` tree plus `.claude/rules/combat-automation.md` (created in the same work session as this entry, immediately after the code above).
- **Plan versus implementation variances:** see [code-map.md](../code-map.md#current-limitations-and-sourceplan-variances-index) for the full table — summarized: `FightWnd`-based deck lookup replaced with `Fightsess.actions[]` (equivalent/better); `CombatEvents` subscription planned but not wired ([UNR-006](../unresolved.md#unr-006)); per-request lifecycle states collapsed to `SENT`/`REJECTED` ([UNR-006](../unresolved.md#unr-006)); snapshot-diffing (`sameDecisionState`) written but never called ([UNR-007](../unresolved.md#unr-007)); settings-panel `KeyMatch.Capture` rebind widget replaced with a label pointing at the standard Keybinds screen.
- **Verification performed:** `ant jar` — `BUILD SUCCESSFUL`, exit 0, 2026-07-27 12:23:23 -06:00 (`EV-BUILD-20260727`). All functional claims verified by manual code-trace only (no test harness exists in this repository) — see [verification.md](../verification.md) for the full per-claim matrix.
- **Manual verification still required:** every `MANUAL PENDING` row in [verification.md](../verification.md), most importantly [UNR-014](../unresolved.md#unr-014) (re-running the exact multi-session tab-switch-mid-fight scenario against the fixed code).
- **Known limits/follow-ups:** UNR-001 through UNR-009 (see [unresolved.md](../unresolved.md)).
- **Implemented by:** Claude Code (this session), following an approved implementation plan.
- **Documentation reconstructed by:** Claude Code (this session), from git evidence, source re-inspection, the four R3 documents, the port brief, and the implementation plan.

## 2026-07-27 — Mid-session safety hardening (same day, after user-reported crash)

- **Commit:** same uncommitted baseline as above — no new commit; this entry documents a same-day follow-up edit to files already listed above, not a separate release.
- **Request/source:** User reported an in-client crash (`NullPointerException` in `haven.overlays.NTargetFight.tick` via `NUtils.getGameUI()` returning null) that occurred while running two sessions simultaneously and switching the active character tab mid-fight, plus `"Uimsg to non-existent widget"` errors on the Loader thread (`EV-CRASH-REPORT`).
- **Why:** User's explicit stated priority: a crash mid-combat in a permadeath game is the worst possible outcome. Root-caused to `NCombatReactor.tick()` calling the global/thread-local `NUtils.getGameUI()` instead of resolving its own owning `GameUI` structurally; a second latent bug (the cached `CombatActionExecutor` never rebuilt when the underlying `Fightsess` widget identity changed) was found and fixed in the same pass.
- **Changed behavior:** BEH-UI-002 (new — safety trip switch after 3 consecutive failures).
- **Code files:** `src/nurgling/widgets/NCombatReactor.java` (session-safe `GameUI` resolution via `getparent(GameUI.class)`, `Throwable`-wide catch on every entry point, trip-switch latch + warning banner), `src/nurgling/combat/CombatActionExecutor.java` (per-call `Throwable` catch split so a `releaseAction` failure doesn't mask that `requestAction` already sent), `src/nurgling/combat/CombatReactorController.java` (executor rebuilt on `Fightsess` identity change, not just on first construction).
- **Documentation files:** none at the time (documentation reconstruction, including this entry, happened afterward in this same overall work session).
- **Plan versus implementation variances:** none beyond what's already listed above — this was defensive hardening, not a scope change.
- **Verification performed:** `ant jar` — `BUILD SUCCESSFUL` after each of the two edit passes in this entry (first fixing the `GameUI` resolution bug, then adding the trip switch + per-call exception splitting).
- **Manual verification still required:** the user has not yet reproduced the original crash scenario against this fixed code (= [UNR-014](../unresolved.md#unr-014)).
- **Known limits/follow-ups:** [UNR-005](../unresolved.md#unr-005) (no explicit release-on-disable/relation-change path) remains open and is a related-but-distinct gap from what this entry fixed.
- **Implemented by:** Claude Code (this session), in direct response to user-reported failure.
- **Documentation reconstructed by:** Claude Code (this session).

## See also

- [../unresolved.md](../unresolved.md) — open items referenced above.
- [../verification.md](../verification.md) — current pass/fail status.
- [../decisions/README.md](../decisions/README.md) — ADRs for the lasting architectural choices made across both entries above.
