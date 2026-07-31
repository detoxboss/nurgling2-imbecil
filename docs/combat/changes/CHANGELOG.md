---
doc_id: combat-changelog
revision: 2
status: current
last_verified: 2026-07-28
verified_against: "HEAD 9d7404fa0 + uncommitted worktree"
canonical_for:
  - "Append-only history of combat-reactor changes"
---

# Changelog

Append-only. Never edit a past entry's substance — add a new entry instead, and tombstone-link from the old one if something it recorded was later superseded.

## 2026-07-28 — Bots-menu Start/Stop popup (new control surface)

- **Commit:** same uncommitted baseline as prior entries.
- **Request/source:** User provided reference screenshots (bots-menu sidebar, its Combat category, the "Combat Distance Tool" and "Prepare Blocks" popups) and asked for a second control surface: a bots-menu entry under the existing Combat category, opening a tiny draggable popup with a rebindable attack-key field and a Start/Stop button (not a checkbox), where closing the popup also stops the reactor. Also confirmed the manual-attack key was never actually listed in nurgling's standard Keybinds screen (investigated: that screen only lists keys wired via explicit `addbtn(...)` calls in `haven.OptWnd`, not everything registered through `KeyBinding.get(...)`) — the user does not want an entry added there; per-run configuration in the new popup is the intended replacement.
- **Why:** Match the client's existing bot-automation UX convention (sidebar → category → icon → popup) instead of only the Settings-panel checkbox, per explicit user direction.
- **Changed behavior:** [BEH-UI-003](../behavior-contract.md#diagnosticsui-approved-minimum) (new).
- **Code files:** `src/nurgling/actions/bots/CombatReactorTool.java` (new — `Action`, exact toggle pattern copied from `CombatDistanceTool.java`), `src/nurgling/widgets/NCombatReactorTool.java` (new — `haven.Window` popup, exact close/destroy pattern copied from `NCombatDistanceTool.java`), `src/nurgling/actions/bots/registry/BotRegistry.java` (one new `BotDescriptor` line in the `BATTLE` section), `resources/src/nurgling/bots/icons/combatreactor/` (new — duplicated from `.../combatdist/` as a placeholder, see [UNR-015](../unresolved.md#unr-015)).
- **Documentation files:** `docs/combat/behavior-contract.md`, `code-map.md`, `evidence.md`, `verification.md`, `unresolved.md`, this changelog.
- **Plan versus implementation variances:** none — implemented exactly as planned and approved.
- **Verification performed:** `ant jar` — `BUILD SUCCESSFUL` (including the new `combatreactor` resource folder compiling without error, confirmed by checking the build log specifically for that folder name).
- **Manual verification still required:** user has not yet clicked through the new bots-menu entry in-client (`VER-BOTSMENU-001`, `MANUAL PENDING`).
- **Known limits/follow-ups:** [UNR-015](../unresolved.md#unr-015) (placeholder icon/tooltip art, cosmetic only).
- **Implemented by:** Claude Code (this session), following an approved plan.
- **Documentation reconstructed by:** Claude Code (this session).

## 2026-07-27 — Shared-cooldown gating + configurable defense threshold (post-testing fixes)

- **Commit:** same uncommitted baseline as prior entries.
- **Request/source:** User live-tested the reactor and reported two issues: (1) the highlighted action visibly "wavered" between attacks/defenses up to ~20 times across a single ~3-second cooldown window, usually settling on a defense move by the time the window closed regardless of what was originally intended; (2) automatic defense reacted to trivial opening pressure (10-20) when real play doesn't start clearing until roughly 40-60.
- **Why:** Root cause of (1): `CombatReactorController.tick`'s defense loop sent a fresh request every ~100ms tick with no awareness of `Fightview.atkct` — the shared/global attack-cooldown window, which has a per-move duration supplied by the server (confirmed both from source and the user's direct observation, [MEC-CD-004](../mechanics.md#cooldowns-and-action-lifecycle)). Per-action cooldown alone (`Fightsess.Action.cs/ct`) only blocks re-selecting the *same* move, not a *different* one, so the reactor kept re-selecting/highlighting a new action every tick for the entire window. (2) was a user-directed revision of the original `>0` threshold from the port brief, based on real play experience.
- **Changed behavior:** [BEH-DEF-001](../behavior-contract.md#defense-automatic) (revised — configurable threshold, default 40, replacing `>0`), [BEH-DEF-005](../behavior-contract.md#defense-automatic) (new — shared-cooldown gate).
- **Code files:** `src/nurgling/combat/CombatSnapshot.java` (new `sharedCooldownEnd` field + `sharedCooldownActive()`), `src/nurgling/combat/CombatStateAdapter.java` (reads `Fightview.atkct`), `src/nurgling/combat/CombatReactorController.java` (gates the defense loop on the shared cooldown; `defenseThreshold()` reads config), `src/nurgling/combat/CombatDecisionEngine.java` (`chooseDefence` takes a `threshold` parameter), `src/nurgling/NConfig.java` (new `Key.combatReactorDefenseThreshold`, default 40), `src/nurgling/widgets/nsettings/CombatReactorSettings.java` (new threshold `TextEntry`; also removed a false claim that the manual-attack key can be rebound in the standard Keybinds menu — investigated this session and confirmed nurgling's Keybinds screen only lists keys explicitly wired via `addbtn(...)` in `OptWnd`, not everything registered through `KeyBinding.get(...)`; `kb_attack` was never actually listed there).
- **Documentation files:** `docs/combat/behavior-contract.md`, `mechanics.md`, `code-map.md`, `evidence.md`, `verification.md`, this changelog.
- **Plan versus implementation variances:** none new — this is a bug fix and a user-directed threshold revision, not a plan deviation.
- **Verification performed:** `ant jar` — `BUILD SUCCESSFUL` after each of the two changes.
- **Manual verification still required:** user has not yet re-tested either fix in-client (`VER-COOLDOWN-GATE`, `VER-THRESHOLD-001`, both `MANUAL PENDING`).
- **Known limits/follow-ups:** the manual-attack trigger is intentionally left ungated by the shared cooldown (only automatic defense is gated) — matches the R3-era historical precedent of gating automatic defense scans on cooldown, not manual input; not separately re-confirmed with the user as a explicit design choice beyond that precedent.
- **Implemented by:** Claude Code (this session), in direct response to user live-testing feedback.
- **Documentation reconstructed by:** Claude Code (this session).

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
