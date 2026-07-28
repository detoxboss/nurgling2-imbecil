# Haven & Hearth Combat-Interface Recognition Reference

Last verified: 2026-07-25  
Revision: 1.2.0 — remaining final-audit corrections  
Applies to: `Haven_Combat_Reactor_Optimized_Audited.ahk`  
Reference script SHA-256: `6b04efc3474b638a434c14aab4fa838fcb508a72b9692a0e29f35ed770178405`

## Purpose and evidence categories

This file documents what the automation attempts to observe and exactly how the current source interprets pixels. It does not treat a pixel inference as a game mechanic.

- **[Verified game mechanic]**
- **[Community-documented mechanic]**
- **[Current observable script behavior]**
- **[Existing user-approved requirement]**
- **[Mechanics constraint]**
- **[Historical implementation decision]**
- **[Auditor-recommended improvement]**
- **[Unresolved question]**

## Canonical recognition-state vocabulary

| State | Required meaning |
|---|---|
| `confirmed_zero` | A valid detector positively established the displayed value as zero. |
| `valid_not_found` | Search completed but the requested match was absent (`ErrorLevel = 1`). |
| `display_absent` | A dedicated rule positively established that the relevant display/UI is absent. |
| `search_error` | Search could not be performed (`ErrorLevel = 2`). |
| `unknown` | Evidence cannot establish a value or absence. |
| `stale` | A prior observation is no longer safe because of age, target/focus/UI change, or intervening state. |
| `unavailable` | An action, weapon, asset, template, or capability is established as unavailable. |

Current AHK variables do not encode these seven states. Bucket `0` and Boolean `cachedTwoIP` collapse several of them.

## Official combat-interface model

The pinned official client revisions show:

- an action bar of up to 10 actions;
- per-action cooldown overlays and a central/shared cooldown;
- a current relation plus other relations;
- separate player/current-opponent buff/opening groups;
- player-against-opponent and opponent-against-player IP;
- target indication, last-action icons, and selected/queued frames.

The AHK script recognizes only fixed ROIs for openings, one sampled cooldown color, and IP templates.

## Coordinate system

`CoordMode, Pixel, Screen` uses absolute screen coordinates. `Region(x,y,w,h)` deliberately returns `x2=x+w`, `y2=y+h`; `w/h` are inclusive deltas rather than pixel counts.

| Region | Variable | Inclusive bounds | Actual span | Intended content |
|---|---|---|---|---|
| Player openings | `combatRegion` | x 650–859, y 768–829 | 210 × 62 | Defensive opening tiles |
| Shared cooldown | `cooldownRegion` | x 947–972, y 793–812 | 26 × 20 | Central cooldown sample |
| Target openings | `attackRegion` | x 1043–1261, y 770–832 | 219 × 63 | Current-target opening tiles |
| Player IP | `ipRegion` | x 859–916, y 742–792 | 58 × 51 | IP against current target |

All intended content is a **[Current observable script behavior]** plus an asset/layout assumption. DPI, UI scale, window placement, monitor, theme, hover/animation and custom-client changes can invalidate it.

## Templates and startup checking

### Combat templates

`Tile()` creates `*Trans0xFF00FF *95 <path>`. Magenta is transparent; variation 95 is permissive and may improve tolerance while increasing false positives. `ImageSearch` returns the first match only.

### IP templates

The script requires:

- `ip0.png`, `ip1.png` at variation 95;
- `ip10/ip11` through `ip90/ip91` at variation 90;
- magenta transparency.

The guard family exists because a single 0/1 glyph may match the suffix of a multi-digit number.

### Exact current behavior and defect H7

- **Current behavior:** `RequireFile()` calls `FileExist()` for each path before timers begin.
- **Risk:** a corrupt, unsupported, wrong-dimension or geometrically wrong PNG passes startup and can later produce `search_error` or invalid matching.
- **Affected components:** `RequireFile()`, `ImageSpec()`, `Tile()`, startup template loops.
- **Status:** documented only; no script change.
- **Recommended future correction:** add decode/load preflight, expected dimensions/transparency/anchor checks, and a representative `ImageSearch` load test.

Call the present behavior “template existence checking,” not full template validation.

## Opening-bar geometry and bucket recognition

| Variable | Value | Meaning |
|---|---:|---|
| `barOffX` | 2 | Horizontal bar offset from template match |
| `barOffY` | 44 | Vertical bar offset |
| `barW` | 40 | Assumed width |
| `barH` | 4 | Assumed height |
| `probeY` | 46 | Sampled row |
| `whiteThresh` | 210 | Intended minimum RGB channel |
| `whiteVariation` | 45 | Variation passed to `PixelSearch` |

- Defensive probes: `[4, 8, 12, 18]` (nominal 10%, 20%, 30%, 45%).
- Offensive probes: `[4, 8, 12, 24]` (nominal 10%, 20%, 30%, 60%).
- Each probe searches a 3×1 band (`center-1..center+1`) for near-white.
- `ReadBarBucket()` counts matching bands and returns 0–4.

A bucket is a probe count, not an exact percentage. The two probe sets are not directly identical scales. Artifacts can create nonmonotonic counts.

### Search-error semantics

- `PixelSearch ErrorLevel = 0` → qualifying pixel found.
- `ErrorLevel = 1` → `valid_not_found`.
- `ErrorLevel = 2` → `search_error`.
- **Current behavior:** both 1 and 2 count as an unfilled probe, and the resulting count may be 0.
- **Risk:** detector failure is promoted to zero pressure.
- **Affected components:** `ReadBarBucket()`, `ScanSingleTile()`, `ScanTileRegion()`.
- **Status:** documented defect only.
- **Recommended future correction:** return value plus recognition state; never convert `search_error` to `confirmed_zero`.

## Defensive scan

`ScanAndActDefence()` checks cooldown, scans four player-side templates, reads buckets, finds the highest, optionally chooses `multiDefKey`, otherwise queues every unique key tied at the top.

- Highest bucket is a script heuristic.
- Red/yellow map to one `W` and are deduplicated.
- With distinct tied keys, the script queues every candidate.

### Tie execution risk H2

- **Current behavior:** queued tied defenses are serialized, but the second begins immediately after the first release when the queue timer runs. Cooldown/openings/execution are not rechecked.
- **Risk:** the second action may be stale or undesirable after the first defense, passive decay, target/UI transition, or failure.
- **Affected components:** `ScanAndActDefence()`, `QueueTap()`, `ReleaseExpiredKeys()`, `tapQueue`.
- **Status:** compliant with the preserved historical send-all tied-defense policy; documented staleness/safety risk, not noncompliance with that historical requirement.
- **Recommended future correction:** preserve ties as candidates and revalidate before starting a later automatic defense, or define a user-approved alternative policy. Revalidation is an auditor recommendation, not an approved requirement.

## Offensive scan and grouped terminology

`RefreshAttackDecision()` searches all four target-opening colors sequentially within one call, updates `attackBuckets`, and invokes `ChooseAttackDecision()`.

Use **grouped decision batch** for this behavior:

- the four colors belong to one logical decision update;
- built-in `ImageSearch` calls are sequential;
- each matched tile triggers four further sequential `PixelSearch` calls;
- searches may observe different rendered frames.

Do not call this a coherent snapshot or one-frame capture.

`ChooseAttackDecision()` uses the legacy heuristic:

- all bucket counts 0 → Quick Barrage;
- `FC pressure = red + yellow`;
- `Sting pressure = green + blue`;
- without its Boolean 2+ inference: Full Circle if FC is nonzero and `FC >= Sting`, otherwise Quick Barrage;
- with its Boolean 2+ inference: Sting if `Sting > FC`, otherwise Full Circle if FC is nonzero, otherwise Quick Barrage.

This is not the nonlinear game formula. All-zero Quick Barrage is an opener/deck-flow choice, and bucket geometry cannot confirm the strict >25% red threshold for IP gain.

Before the first completed attack scan, `nextAttack` already contains the Quick Barrage key. Because `E` is global when `targetWindow := ""`, pressing it during this startup interval can request Quick Barrage. This is **[Current observable script behavior]**.

## Initiative recognition state machine

### Phase 0

Search `ip0`.

- match: set `cachedTwoIP := false`, begin guards;
- any nonmatch/error: proceed to phase 1 because the code does not distinguish 1 from 2.

### Phase 1

Search `ip1`.

- match: set `cachedTwoIP := false`, begin guards;
- any nonmatch/error: set `cachedTwoIP := true` and reset.

### Phase 2

Search one `10/11/.../90/91` guard per scanner frame.

- match: set Boolean 2+ true;
- all fail/error: retain false and treat earlier suffix as genuine 0/1.

Worst ambiguous 0/1 path is about 20 scanner steps (~600 ms at 30 ms plus command/scheduler delay).

### Exact defect C2/M4

- **Current behavior:** neither single-digit match becomes `cachedTwoIP := true`; guard errors are also treated as nonmatches.
- **Risk:** `valid_not_found`, `display_absent`, `search_error`, target transition, obstruction, wrong ROI, and values not represented by the assumptions can authorize Sting.
- **Affected components:** `ScanNextIPStep()`, `FindIPTemplate()`, `cachedTwoIP`, `ipScanPhase`, `ipGuardIndex`, all IP templates.
- **Status:** documented only.
- **Recommended future correction:** explicit state model; display-validity detector; target-bound timestamps; fail closed; test values beyond 91 if possible.
- **Unresolved:** maximum possible/displayable IP and behavior for values above guard coverage.

No document may describe the current fallback as confirmed `2+ IP`.

## Cooldown recognition

The script searches `cooldownRegion` for `0xE0E0E0` with variation 10.

- found: script assumes shared cooldown is active and skips defense scan;
- `valid_not_found`: script assumes cooldown clear;
- `search_error`: current code also assumes cooldown clear;
- display validity is not checked.

The official client draws a translucent cooldown wedge/frame, but the custom-client sampled gray is unverified.

- **Risk:** `search_error` can authorize defense; a static frame pixel can create false blocking.
- **Affected components:** `ScanAndActDefence()`, `cooldownRegion`, `cooldownColor`, `cooldownVariation`.
- **Status:** documented only.
- **Recommended correction:** validity-aware result and calibration against target screenshots/video.

## Action availability and execution

The official interface exposes selected/queued frames, per-action cooldown, shared cooldown and last action. The AHK script reads only the sampled central cooldown and does not verify:

- active deck presence/binding;
- per-action cooldown;
- weapon/IP/range eligibility;
- selected/queued visual state;
- server execution after send.

Per-action execution confirmation is an **[Auditor-recommended improvement]**, not an existing approved requirement.

## Target and combat presence

- The script does not read portrait, target arrow, relation count/state or identity.
- `attackRegion` and `ipRegion` are assumed to refer to the same current relation.
- No dedicated combat-presence/start/end detector exists.
- Missing tiles become bucket 0.

Confirmed combat-presence gating and target identity/change detection are **[Auditor-recommended improvements]**. Current noncompliance is documented, but these are not user-approved requirements.

## Foreground protection and global hotkeys

### Current behavior C1/C3

When `targetWindow` is nonblank, `ScanFrame` and manual `E` requests call `IsTargetActive()`. `ReleaseExpiredKeys()` does not.

After focus loss, the queue timer can:

- release the active key into the newly focused application;
- dequeue a pending key;
- send its key-down into the newly focused application.

With default `targetWindow := ""`:

- fixed-coordinate scanning runs regardless of active application;
- false visual matches can generate `A`, `D`, or `W`;
- `E` is globally intercepted;
- `Esc` globally exits the script;
- `F8` is global.

| Item | Current behavior | Risk | Affected components | Status | Recommended correction |
|---|---|---|---|---|---|
| Focus loss | Stops new scan/manual requests only when configured; does not stop scheduler | Key-up/new key-down in another app | `ScanFrame`, `e::`, `ReleaseExpiredKeys()`, queue state | Documented only | On loss, clear pending queue and safely release active key without starting another |
| Blank target | Global scanning/input | False positives and intercepted normal typing | `targetWindow`, hotkey declarations | Documented only | Configure exact client window for deployment; consider context-sensitive hotkeys |
| Combat absence | No gate | Inputs outside combat | all detectors/sends | Documented only | Proposed combat-presence gate |

## Input queue behavior

- One key is physically active at a time.
- Ordinary pending taps append FIFO.
- Priority manual requests use `InsertAt(1)`.
- Successive priority inserts are LIFO relative to one another; newer manual requests can overtake older pending manual attacks.
- Queue entries carry key/hold duration but no enqueue timestamp, target identity, observation identity, or validity state.
- A queued request can become stale without rejection.
- Duplicate active/pending keys are suppressed.
- For Sting specifically, successful `QueueTap()` admission immediately sets `lastStingFire := A_TickCount`, begins the 600 ms local throttle, sets `cachedTwoIP := false`, and recomputes `nextAttack`. This occurs before actual key-down and without confirmed game execution.

Queue timestamps/stale rejection are an auditor recommendation. Future policy must also decide whether a newer manual request replaces or stacks ahead of older pending attacks.

## Timer and F8 limitations

- AutoHotkey timers are pseudo-threads, not simultaneous worker threads.
- A release timer cannot interrupt the middle of a long `ImageSearch`/`PixelSearch` command.
- `tapHoldMS := 30` is a minimum target, not an exact hold. Physical hold can be longer.
- “No `Sleep`” means the code does not deliberately block for the hold; it does not guarantee deadline release or logical independence during a long command.
- With `Thread, Interrupt, 0`, timers may interrupt the F8 hotkey while its `MsgBox` is displayed.
- The dialog may obscure combat ROIs while scanning continues, causing false readings including unsafe IP inference.

Recommended correction: pause scanning safely while stats are displayed or render stats outside all combat ROIs.

## Safe fallback behavior

Desired semantics for a future safety revision:

1. `search_error`, `unknown`, `display_absent`, or `stale` combat/target state → automatic send nothing.
2. Unknown IP → never authorize Sting.
3. Unknown cooldown → do not automatically defend until revalidated.
4. Partial/unknown openings → do not promote missing colors to `confirmed_zero`.
5. Focus loss → clear pending queue; safely release active key; do not start another.
6. Target change → invalidate relation-dependent state.
7. Persistent errors → rate-limited diagnostic **[Auditor-recommended improvement]**.

Items 1 and 3–7 are auditor recommendations unless separately approved. Item 2 is the established mechanics/safety constraint that unknown IP must not authorize Sting. The current implementation does not satisfy these semantics completely.

## False-positive/negative risks

- `*95`/`*90` are permissive.
- Transparent crops may contain too little distinctive content.
- UI scale/theme/animation/hover can change pixels.
- First match may be wrong or hide multiple same-color tiles.
- Near-white bar probes can hit borders/glare/text or miss a shifted row.
- IP suffixes can collide with larger values; display can disappear during target change.
- Cooldown color may sample a frame rather than fill.
- F8 can occlude ROIs while timers continue.

## Relevant current-script components

- Regions/thresholds: `combatRegion`, `cooldownRegion`, `attackRegion`, `ipRegion`, `cooldownColor`, `barOffX`, `probeY`, probe arrays, white thresholds.
- Templates: `tileList`, `Tile()`, `ImageSpec()`, `RequireFile()`, `ip0Spec`, `ip1Spec`, `ipGuardSpecs`.
- Detection: `ScanFrame`, `ScanAndActDefence()`, `RefreshAttackDecision()`, `ChooseAttackDecision()`, `ScanTileRegion()`, `ScanSingleTile()`, `ReadBarBucket()`, `ScanNextIPStep()`, `FindIPTemplate()`.
- Scheduling/safety: `QueueTap()`, `ReleaseExpiredKeys()`, `ReleaseAllKeys()`, `tapQueue`, `activeTapKey`, `targetWindow`, `IsTargetActive()`, `Cleanup`.

## Source register

- Current script and SHA-256 above.
- AHK v1 ImageSearch: https://www.autohotkey.com/docs/v1/lib/ImageSearch.htm
- AHK v1 PixelSearch: https://www.autohotkey.com/docs/v1/lib/PixelSearch.htm
- AHK v1 SetTimer: https://www.autohotkey.com/docs/v1/lib/SetTimer.htm
- AHK v1 Threads: https://www.autohotkey.com/docs/v1/misc/Threads.htm
- Pinned `Fightsess.java`: https://github.com/dolda2000/hafen-client/blob/bf45129905d4b1f03ebefe43b3237f8469453a42/src/haven/Fightsess.java
- Pinned `Fightview.java`: https://github.com/dolda2000/hafen-client/blob/223f516c88e98bcbf54f5f18be7d05f3c29f0c70/src/haven/Fightview.java
- Combat moves, pinned audited revision: https://ringofbrodgar.com/index.php?title=Combat_moves&oldid=122547
- Combat guide: https://www.havenandhearth.com/forum/viewtopic.php?f=42&t=72160
- PNG assets/calibration screenshots were not present and recognition claims remain asset-dependent.

## Revision history

- **1.2.0 — 2026-07-25:** Removed the nonexistent red-comment conflict, classified persistent-error diagnostics as an auditor recommendation, documented startup Quick Barrage and Sting queue-admission mutations, aligned tied-defense compliance/risk wording, and pinned the Combat Moves revision.
- **1.1.0 — 2026-07-25:** Resolved C1–C3, H1–H3, H6–H9, M1, M4, and M6. Unified recognition vocabulary; documented exact global/focus/queue/timer/F8/search-error behavior; corrected grouped terminology and startup checking; separated defects from recommendations.
- **1.0.0 — 2026-07-25:** Initial canonical extraction.

## Future update instructions

1. Re-read the script and record its hash before changing any function/coordinate description.
2. Never call a nonmatch zero, an error a nonmatch, or an unsafe IP fallback confirmed.
3. Preserve current behavior, risk, affected components, status, and proposed remediation for every defect until implemented and verified.
4. Do not claim one-frame consistency unless all detectors consume one captured bitmap.
