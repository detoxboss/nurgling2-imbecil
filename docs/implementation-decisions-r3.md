# Haven & Hearth Combat-Automation Implementation Decisions

Last verified: 2026-07-25  
Revision: 1.2.0 — remaining final-audit corrections  
Reference implementation: `Haven_Combat_Reactor_Optimized_Audited.ahk`  
Reference script SHA-256: `6b04efc3474b638a434c14aab4fa838fcb508a72b9692a0e29f35ed770178405`

## How to use this log

This log preserves reasoning, exact current behavior and defects. Status meanings:

- **Accepted historical decision:** intentionally present/preserved.
- **Superseded:** replaced by a later choice.
- **Rejected:** considered and not chosen.
- **Experimental:** benefit is unproven.
- **Current defect:** verified noncompliance in the current script.
- **Auditor recommendation:** proposed work, not a user-approved requirement.
- **Unresolved:** needs evidence/decision.

Evidence categories match the other canonical files: verified game mechanic, community-documented mechanic, current observable script behavior, existing user-approved requirement, mechanics constraint, historical implementation decision, auditor-recommended improvement, unresolved question.

## Canonical recognition-state vocabulary

`confirmed_zero`, `valid_not_found`, `display_absent`, `search_error`, `unknown`, `stale`, and `unavailable` have the definitions in `combat-interface-reference-r3.md`. The current Boolean/bucket implementation is not compliant with this complete model.

## Original architecture summary

The historical 562-line version reportedly used multiple timers, repeated searches, synchronous IP bursts and blocking key holds. The current 708-line script uses:

- one 30 ms scanner timer;
- one 5 ms release timer;
- defense-first grouped control flow;
- a 120 ms grouped target-opening decision batch;
- phased IP suffix disambiguation;
- one serialized key queue;
- precomputed ROIs/templates;
- no executable `Sleep`;
- optional focus checking;
- runtime counters.

The original source, exhaustive harness/results, PNGs and calibration screenshots are not included in the canonical package; comparisons based on them are historical claims not reproduced in this audit.

## Accepted historical decisions

### Keep AutoHotkey v1

Retain AHK v1 while fixed ROIs and built-in searches meet practical needs. A future captured-frame architecture remains possible after profiling.

### Use one unified scanner

One scanner controls defense, IP progress and periodic attack refresh to reduce timer contention. It does not make searches simultaneous.

### Target approximately 30–33 scanner launches/s

`scanInterval := 30` requests ~33.3 launches/s. It is a target, not proof of achieved rate.

### Remove blocking key-hold sleeps

Use queued key-down plus release timer rather than `Sleep, 30`. This removes deliberate sleep blocking but does not guarantee exact 30 ms release because a long search command cannot be interrupted mid-command.

### Serialize combat key taps

One key is physically held; new taps wait. This matches the client’s release-before-next-use lifecycle and avoids overlapping action-key state.

### Give manual requests pending priority

Manual `E` requests are inserted at queue index 1 and never interrupt the active key. Exact current order is FIFO for ordinary appends but LIFO among successive priority inserts. Newer pending manual attacks can overtake older ones.

### Suppress duplicate active/pending keys

Reject a key already active or anywhere pending. This limits flooding but can suppress a deliberate repeated request.

### Defense-first scanning

Defense and cooldown checks run before IP progress/attack refresh in `ScanFrame()`, preserving latency priority.

### Gate defense on sampled shared cooldown

If the gray sample is found, defense scan is skipped. The detector is uncalibrated and errors are currently treated as clear; this decision does not establish correctness of the sampled color.

### Share tile/bar functions and precompute specifications

Combat colors share `ScanTileRegion()`, `ScanSingleTile()` and `ReadBarBucket()`; probe ranges and image specifications are prepared outside the hot path.

### Preserve inclusive ROI endpoints

`Region()` uses `x+w`, `y+h` to match historical endpoints. `w/h` are deltas, despite misleading names.

### Group all four opponent colors

All four colors are read in one grouped decision batch every ~120 ms. Built-in commands are sequential and do not consume one coherent captured bitmap.

### Preserve the attack truth table

Legacy logic uses red+yellow versus green+blue and all-zero Quick Barrage. This is an approved compatibility policy, not the documented nonlinear damage formula.

All-zero Quick Barrage is an opener/deck-flow decision: no red opening means no opening-derived damage; Quick Barrage creates red, and IP gain requires red strictly >25%, which coarse buckets cannot confirm.

The prior claim of exhaustive equivalence across 1,250 bucket/IP inputs remains historical; harness/results were unavailable and not reproduced in this audit.

### Conservative IP startup and phased guards

Start `cachedTwoIP := false`; keep `10/11...90/91` guards; perform one IP search step per scanner frame. This bounds synchronous work but leaves generic nonmatch/error unsafe.

### Deduplicate red/yellow `W`

Keep separate values for attack selection; suppress duplicate `W` when both tied player openings map to Zig-Zag.

### Keep multi-defense disabled by default

`enableMultiDef := false` because the bound move/side effects are not verified.

### Keep local Sting throttle

`stingCooldown := 600` suppresses rapid duplicate local requests. It is not an availability or execution model; Sting’s listed base cooldown is longer.

The throttle and presumed IP-consumption state change begin when `QueueTap()` accepts the Sting request: `lastStingFire` is set, `cachedTwoIP` becomes false, and `nextAttack` is recomputed immediately. Queue admission occurs before actual key-down and without confirmed game execution.

## Rejected or deferred architecture choices

- OCR for the fixed small IP display: rejected without measured reliability advantage.
- GPU compute for tiny ROIs: rejected without profiling; capture may benefit more than compute.
- Staggering opponent colors across timer frames: rejected because it worsens mixed-age decisions.
- Only two IP templates: rejected because of suffix collisions.
- Simultaneously held action keys: rejected because client state is single-held/sequenced.
- Claiming current searches share a frame: rejected as false.

## Current defects, limitations, risks, and exact remediation records

### C1/C3 — foreground protection and global activity

- **Current behavior:** `IsTargetActive()` guards `ScanFrame` and `e::` only. `ReleaseExpiredKeys()` can release/dequeue/start the next key after focus loss. Blank `targetWindow` leaves scanning and `E`/`F8`/`Esc` global.
- **Risk:** keys sent/released into another app; ordinary `E`/`Esc` intercepted; false sends outside combat.
- **Affected:** `targetWindow`, `IsTargetActive()`, `ScanFrame`, `e::`, `ReleaseExpiredKeys()`, hotkey declarations.
- **Status:** current defect, documented only; script unchanged.
- **Recommended correction:** clear pending queue and safely release active key on focus loss without starting another; configure exact target; consider context-sensitive hotkeys. Combat-presence gating is a recommendation, not approved.

### C2 — collapsed recognition semantics/IP authorization

- **Current behavior:** ImageSearch/PixelSearch results 1 and 2 are collapsed. Tile/probe error becomes bucket 0; cooldown error becomes clear; both IP single-digit failures become true.
- **Risk:** search failure authorizes defense or Sting.
- **Affected:** all visual search functions, especially `ReadBarBucket()`, `ScanAndActDefence()`, `FindIPTemplate()`, `ScanNextIPStep()`, `cachedTwoIP`.
- **Status:** current defect, documented only.
- **Recommended correction:** return canonical state with value; fail closed on `search_error`/`unknown`; add display validity. Never call current fallback confirmed 2+.

### H1 — grouped batch is not coherent capture

- **Current behavior:** sequential ImageSearch plus per-match PixelSearch commands.
- **Risk:** one decision can mix rendered frames.
- **Affected:** `RefreshAttackDecision()`, `ScanTileRegion()`, `ScanSingleTile()`, `ReadBarBucket()`.
- **Status:** documented limitation.
- **Recommended correction:** one captured bitmap only if approved/needed; terminology corrected now.

### H2 — tied defenses are sent without revalidation

- **Current behavior:** every distinct top key is queued; later tap starts after release without cooldown/opening/execution check.
- **Risk:** stale/redundant/unsafe second defense.
- **Affected:** `ScanAndActDefence()`, `QueueTap()`, `ReleaseExpiredKeys()`.
- **Status:** compliant with the preserved historical send-all tied-defense policy; documented staleness/safety risk, not noncompliance with that historical requirement.
- **Recommended correction:** preserve candidates and revalidate before later automatic action; not yet approved.

### H3 — timer does not guarantee hold duration

- **Current behavior:** release timer requests 5 ms cadence and `tapHoldMS=30`.
- **Risk:** long visual command delays release; hold exceeds 30 ms.
- **Affected:** scanner commands, `ReleaseExpiredKeys()`, timer configuration.
- **Status:** documented limitation.
- **Recommended correction:** measure physical durations; consider separate process/input mechanism if strict deadlines are required.

### H6 — F8 dialog does not necessarily pause timers

- **Current behavior:** global F8 shows `MsgBox`; timers may interrupt and continue scanning.
- **Risk:** dialog obscures ROIs and creates false readings/unsafe IP.
- **Affected:** `F8`, `ShowRuntimeStats()`, scan timers.
- **Status:** current defect, documented only.
- **Recommended correction:** explicit safe pause or out-of-ROI display.

### H7 — existence checking mislabeled validation

- **Current behavior:** `RequireFile()` only calls `FileExist()`.
- **Risk:** corrupt/wrong PNG reaches runtime and may cause error/mismatch.
- **Affected:** startup/template functions.
- **Status:** documentation corrected; implementation unchanged.
- **Recommended correction:** decode/dimension/transparency/anchor/search preflight.

### H8 — incomplete key configurability

- **Current behavior:** attack/timing variables exist; defense keys are embedded; `E/F8/Esc` static.
- **Risk:** source edits required; documentation can mislead deployment.
- **Affected:** `tileList`, attack key variables, hotkey declarations.
- **Status:** documentation corrected.
- **Recommended correction:** centralized profiles if user approves.

### H9 — priority requests are LIFO and stale-capable

- **Current behavior:** every priority request uses `InsertAt(1)`.
- **Risk:** newer manual requests overtake older pending manual requests; both can age.
- **Affected:** `QueueTap()`, `tapQueue`, `e::`.
- **Status:** current behavior documented.
- **Recommended correction:** user decision: replace older pending manual attack, strict FIFO, or intentional newest-wins.

### M1 — telemetry overclaim

- **Current behavior:** `scanBusy` is checked inside `ScanFrame`; a timer suppressed because its prior instance is running usually never reaches that check. Effective rate uses wall time including guarded inactive periods while early returns are not completed scans.
- **Risk:** `skippedBusyScans` and effective rate are misinterpreted.
- **Affected:** `ScanFrame`, `RecordCompletedScan()`, `ShowRuntimeStats()`, counters.
- **Status:** documentation corrected; metric implementation unchanged.
- **Recommended correction:** count requested periods independently or rename/remove metric; report active measurement windows.

### M4 — unsupported IP range

- **Current behavior:** guards end at `90/91`.
- **Risk:** values beyond assumed coverage may interact unpredictably with suffix detection.
- **Affected:** IP assets/startup/scan state.
- **Status:** unresolved/documented.
- **Recommended correction:** establish maximum; add >91 tests if possible.

### M5 — timer-resolution semantics

- **Current behavior:** script calls `timeBeginPeriod(1)`, ignores return, and later calls `timeEndPeriod(1)`.
- **Platform facts:** the calling AutoHotkey process requests the timer resolution. Since Windows 10 version 2004 the effect is generally per-process rather than system-wide. Starting with Windows 11, Windows does not guarantee the higher requested resolution if the window-owning AHK process becomes fully occluded, minimized, or otherwise invisible or inaudible to the user.
- **Risk:** assumed benefit may not exist; unmatched cleanup is possible when begin failed.
- **Affected:** startup DllCall, cleanup.
- **Status:** experimental, documented only.
- **Recommended correction:** check return; call `timeEndPeriod` only on success; benchmark the AHK automation’s scheduling and scan behavior on the actual target system, including relevant visible/non-occluded and occluded/minimized conditions. Do not describe the separate Haven client as the process receiving the AHK process’s request.

### M6 — historical verification not reproducible

- **Current behavior/history:** documents previously asserted comparisons to original source and a 1,250-case harness.
- **Risk:** future session cannot reproduce claims from package.
- **Affected:** verification claims/source registers.
- **Status:** documentation corrected.
- **Recommended correction:** preserve original source, harness/results, assets and calibration captures as versioned artifacts.

## Auditor-recommended improvements (not approved requirements)

1. Confirmed combat-presence gating.
2. Revalidation between tied automatic defenses.
3. Queue timestamps and stale-action rejection.
4. Target identity/change detection and cache invalidation.
5. Per-action availability/execution confirmation.
6. One-frame bitmap capture when temporal coherence is needed.
7. Complete focus-loss queue cleanup.
8. Recognition state/error propagation.
9. Calibrated cooldown detector.
10. Reproducible regression/performance artifacts.

## Verification performed in this audit

- Read the current 708-line script and computed the pinned SHA-256 above.
- Statically traced focus, hotkeys, queue, IP phases, template checks, scans, timers and telemetry.
- Checked recognition error semantics against AHK v1 docs.
- Checked timer/pseudo-thread constraints against AHK docs.
- Updated official client links to pinned revisions supplied by the audit.
- Corrected damage-formula evidence and passive-decay confidence.
- Did not modify or execute the AHK script.
- Did not reproduce target Windows/in-game performance, template calibration, original-script equivalence, or the historical 1,250-case harness.

## Audit-resolution table

| Finding | Corrected file/section | Resolution | Type | Remaining uncertainty |
|---|---|---|---|---|
| C1 | Interface/Foreground; Requirements/Foreground; Decisions/C1 | Exact scheduler-on-focus-loss behavior, risk, components and proposed cleanup | Script defect | Safest key-up method in a newly focused app needs implementation testing |
| C2 | All files/Recognition states; Interface/IP/search; Requirements/Image recognition; Decisions/C2 | Seven-state vocabulary; ErrorLevel 1/2 split; cached true called unsafe inference | Documentation + script defect | Valid display detector/design |
| C3 | Interface/Foreground/global; Requirements/Global/compliance; Decisions/C1/C3 | Default global scanning and E/F8/Esc explicitly documented | Script defect + proposed change | Exact deployment window and approved gating policy |
| H1 | Spec/Invariants; Interface/Grouped; Requirements/Timing; Decisions/H1 | “Grouped decision batch,” not coherent snapshot | Documentation limitation + proposal | Whether one-frame capture is needed |
| H2 | Spec/Timing; Interface/Defense; Requirements/Ties; Decisions/H2 | Blind queued ties comply with preserved send-all policy; staleness risk documented; revalidation remains recommendation | Historical compliance + risk/proposal | User-approved replacement tie policy |
| H3 | Spec/Invariants; Interface/Timers; Requirements/Timer limitations; Decisions/H3 | 30 ms is minimum target; pseudo-thread delay documented | Documentation + limitation | Measured physical hold distribution |
| H4 | Spec/Damage formula; Requirements/Policy; Decisions/Truth table | Official-at-introduction nonlinear formula; guide corroboration; current server unverified; heuristic separated | Documentation mechanics correction | Current server formula |
| H5 | Spec/Passive decay; Requirements/Staleness; Decisions/H2 context | Community-documented/currently corroborated; staleness consequence | Documentation mechanics correction | Official current rate/behavior |
| H6 | Interface/F8; Requirements/F8; Decisions/H6 | Timers may continue under MsgBox; ROI obstruction risk | Script defect + proposal | Exact interruption behavior under target workload |
| H7 | Interface/Templates; Requirements/Templates; Decisions/H7 | “Existence checking” replaces “validation” | Documentation + script limitation | Asset decode/geometry without PNGs |
| H8 | Interface/Key map; Requirements/Configuration; Decisions/H8 | Variable versus embedded/static bindings listed | Documentation + proposed change | Desired configuration interface |
| H9 | Interface/Queue; Requirements/Input; Decisions/H9 | Priority LIFO behavior and staleness recorded | Script behavior + proposal | Replace/FIFO/newest-wins policy |
| M1 | Requirements/Performance; Decisions/M1 | Counter no longer claimed to measure missed launches; inactive-wall-time caveat | Documentation + telemetry defect | Best replacement metric |
| M2 | Spec/Attack trio; Requirements/Policy; Decisions/Truth table | All-zero QB documented as opener/deck flow; >25% uncertainty | Documentation reasoning | Exact bucket-to-25% mapping |
| M3 | Spec/Combat termination | Server causes relabeled community; relation deletion pinned/verified | Documentation correction | Current server-side causes |
| M4 | Spec/IP; Interface/IP; Requirements/Tests; Decisions/M4 | Maximum IP and >91 coverage unresolved | Documentation + test gap | Actual maximum/display |
| M5 | Requirements/Sources; Decisions/M5 | Modern Windows semantics, unchecked return, experimental benefit | Documentation + implementation limitation | Target-system benefit/occlusion |
| M6 | All source registers; Decisions/Verification/table | Pinned client revisions; unavailable historical artifacts marked unreproduced | Documentation/reproducibility | Original/harness/assets not packaged |

## Source register

Game/client:

- Official damage announcement: https://www.havenandhearth.com/forum/viewtopic.php?p=673607
- Combat guide: https://www.havenandhearth.com/forum/viewtopic.php?f=42&t=72160
- Passive-decay corroboration: https://www.havenandhearth.com/forum/viewtopic.php?t=77003
- Combat moves, pinned audited revision: https://ringofbrodgar.com/index.php?title=Combat_moves&oldid=122547
- Pinned `Fightsess.java`: https://github.com/dolda2000/hafen-client/blob/bf45129905d4b1f03ebefe43b3237f8469453a42/src/haven/Fightsess.java
- Pinned `Fightview.java`: https://github.com/dolda2000/hafen-client/blob/223f516c88e98bcbf54f5f18be7d05f3c29f0c70/src/haven/Fightview.java

Implementation:

- AHK ImageSearch: https://www.autohotkey.com/docs/v1/lib/ImageSearch.htm
- AHK PixelSearch: https://www.autohotkey.com/docs/v1/lib/PixelSearch.htm
- AHK SetTimer: https://www.autohotkey.com/docs/v1/lib/SetTimer.htm
- AHK Threads: https://www.autohotkey.com/docs/v1/misc/Threads.htm
- AHK Thread: https://www.autohotkey.com/docs/v1/lib/Thread.htm
- Microsoft `timeBeginPeriod`: https://learn.microsoft.com/en-us/windows/win32/api/timeapi/nf-timeapi-timebeginperiod

Artifacts:

- Current script SHA-256 above.
- Historical original script, harness/results, PNGs and calibration screenshots were not available and were not reproduced.

## Revision history

- **1.2.0 — 2026-07-25:** Resolved tied-defense compliance wording, expanded Sting queue-admission timing, corrected `timeBeginPeriod()` process semantics and benchmarking language, updated R3 cross-references, and pinned the Combat Moves revision.
- **1.1.0 — 2026-07-25:** Applied every C1–M6 correction; added the audit-resolution table, exact defect records, proposal authority boundaries, pinned sources, modern timer semantics and reproducibility limits.
- **1.0.0 — 2026-07-25:** Initial canonical decision log.

## Future update instructions

1. Keep historical decisions even when superseded; add status/reason.
2. Do not mark auditor recommendations accepted without user approval.
3. When a defect is fixed, record code revision, tests and compliance change in all affected files.
4. Preserve exact source revisions and reproducible artifacts.
