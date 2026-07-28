# Haven & Hearth Combat-Automation Requirements

Last verified: 2026-07-25  
Revision: 1.2.0 — remaining final-audit corrections  
Reference implementation: `Haven_Combat_Reactor_Optimized_Audited.ahk`  
Reference script SHA-256: `6b04efc3474b638a434c14aab4fa838fcb508a72b9692a0e29f35ed770178405`

## Purpose and authority

This document separates approved requirements from current behavior and auditor proposals. Current noncompliance does not automatically create a requirement.

Provenance:

- **[Verified game mechanic]** Supported by official game/client material or current move data with no identified conflict.
- **[Community-documented mechanic]** Reported by cited community material but not independently established from current official server behavior.
- **[Current observable script behavior]** What the current AHK actually does.
- **[Existing user-approved requirement]** Explicit user request or a deliberately approved compatibility policy.
- **[Mechanics constraint]** A safety condition required to avoid contradicting a verified or historically verified mechanic; it is not automatically a user-approved requirement.
- **[Historical implementation decision]** Prior architecture choice.
- **[Auditor-recommended improvement]** Proposed, not approved.
- **[Unresolved question]** Needs evidence or a user decision.

Normative `MUST/SHOULD/MAY` applies only inside an explicitly identified approved/mechanics requirement. Auditor recommendations use “recommended/proposed,” not normative language.

## Existing user-approved requirements

1. React quickly enough for practical combat automation; approximately 30 checks/s is the initial target, with 60 considered only if sustainable.
2. Avoid unnecessary blocking between logical checks.
3. Do not implement combat key holds with a blocking `Sleep`.
4. Reduce repeated image searches/redundant work without changing approved decisions.
5. Preserve automatic defensive selection.
6. Preserve continuously updated attack recommendation with manual `E` firing.
7. Preserve the current three-move truth table unless the user deliberately changes strategy.
8. Preserve red/yellow defensive-key deduplication, serialized key down/up, and current compatibility behavior unless deliberately revised.

The following are not proven user-approved requirements and remain auditor recommendations:

- confirmed combat-presence gating;
- revalidation between tied automatic defenses;
- queue timestamps and stale-action rejection;
- target identity/change detection;
- per-action execution confirmation;
- one-frame bitmap capture.

## Canonical recognition-state vocabulary

| State | Meaning |
|---|---|
| `confirmed_zero` | Valid detector positively established zero. |
| `valid_not_found` | Search completed with no requested match (`ErrorLevel = 1`). |
| `display_absent` | Dedicated rule positively established UI/display absence. |
| `search_error` | Search could not be performed (`ErrorLevel = 2`). |
| `unknown` | Evidence cannot establish a value or absence. |
| `stale` | Prior state is no longer safe due to age/target/focus/UI/intervening change. |
| `unavailable` | Action, weapon, asset, template, or capability is established unavailable. |

No current Boolean or bucket value represents all of these safely.

## Non-goals

- Not a general combat AI, target selector, movement/escape controller, stamina/equipment/peace manager, exact damage simulator, or universal UI recognizer.
- Does not automatically initiate/end combat or fire attacks without `E`.
- Does not promise hard real-time deadlines; AHK/Windows timers are approximate.
- A Python/DXcam/OpenCV port is optional future architecture, not current scope.

## Required inputs and current configuration

### Visual inputs

Fixed player-opening, target-opening, cooldown and IP ROIs; bar pixels; combat/IP PNGs; matching resolution/UI scale/window geometry.

### Current key map

| Action | Key | Configuration status |
|---|---|---|
| Green defense | `A` | embedded in `tileList`; source edit |
| Blue defense | `D` | embedded in `tileList`; source edit |
| Red/yellow defense | `W` | embedded in `tileList`; source edit |
| Optional multi-defense | `S` | variable |
| Quick Barrage | `;` | variable |
| Full Circle | `,` | variable |
| Sting | `Q` | variable |
| Manual trigger | `E` | static hotkey declaration |
| Stats | `F8` | static hotkey declaration |
| Exit | `Esc` | static hotkey declaration |

**Compliance status H8:** only attack keys/some timing settings are centralized variables. Defensive keys and global hotkeys require source editing.  
**Proposed remediation:** if approved, centralize bindings/profiles and make hotkeys context-sensitive or generated from configuration.

### Global hotkey/current deployment behavior

With `targetWindow := ""`, scanning and `E`/`F8`/`Esc` hotkeys are global. `E` is intercepted, `Esc` exits, and false ROI matches can produce keys in any active application. This is current behavior, not desired behavior.

## Observable outputs

- Serialized `SendInput` key-down/key-up for defenses and requested attacks.
- Continuously updated `nextAttack`.
- At startup, `nextAttack` initializes to Quick Barrage before the first completed attack scan; global `E` can therefore request Quick Barrage during that interval. This is **[Current observable script behavior]**, not a statement of desired startup behavior.
- `F8` `MsgBox` with effective scans/s, average/maximum duration and `skippedBusyScans`.
- Startup exit when a required path does not exist.
- Exit cleanup releases active key and clears queue.

Corrections:

- F8 blocks the handler/user, but timers may continue and the dialog may obscure ROIs.
- Startup checks file existence only, not PNG decode/geometry.
- The busy counter does not measure scanner periods that never launch.

## Approved attack-selection policy

The compatibility truth table remains an **[Existing user-approved requirement]**:

1. All opponent buckets 0 → Quick Barrage.
2. `FC pressure = red + yellow`; `Sting pressure = green + blue`.
3. Without confirmed 2+ IP: Full Circle when FC is nonzero and `FC >= Sting`, otherwise Quick Barrage.
4. With confirmed 2+ IP: Sting when `Sting > FC`; otherwise Full Circle when FC nonzero; otherwise Quick Barrage.

Important:

- This is a legacy deck heuristic, not the documented nonlinear damage formula.
- All-zero Quick Barrage is an opener/deck-flow decision. At zero red it has no opening-derived damage; it creates red, and IP gain requires red strictly >25%, which buckets cannot confirm exactly.
- “Confirmed 2+” is required by the policy, but the current script does not actually establish it when both 0/1 templates fail.

## Defensive priorities and ties

Approved/current compatibility behavior:

1. Check sampled shared cooldown before expensive defense searches.
2. Choose the highest observed player-opening bucket.
3. Preserve equal maxima as candidates.
4. Deduplicate identical keys; red+yellow top tie sends one `W`.
5. Keep `enableMultiDef := false` until its move is confirmed.

**Current behavior:** distinct tied keys are all queued and later sent without revalidation.  
**Compliance status:** compliant with historical “send all tied actions,” but safety is unresolved.  
**Auditor-recommended improvement:** preserve ties as candidates and revalidate before a later automatic defense, or obtain user approval for a replacement policy.  
**Affected components:** `ScanAndActDefence()`, `QueueTap()`, `ReleaseExpiredKeys()`, `tapQueue`.

## Foreground and fail-safe behavior

### Existing/derived safety behavior

- Exit cleanup must release the physically held automation key.
- Confirmed 2+ relation-specific IP is a mechanics constraint for Sting.
- Serialized held keys must preserve client release-before-next-use sequencing.

### Current compliance C1/C3

| Area | Current behavior | Compliance | Risk | Proposed remediation |
|---|---|---|---|---|
| Exit | Releases active key and clears pending queue | Compliant | none identified for normal exit | retain |
| Configured focus guard | Guards `ScanFrame` and `E` only | Noncompliant with earlier “prevents sending” wording | release/new queued key in another app | on focus loss clear queue and release active without starting another |
| Blank `targetWindow` | global scanning/hotkeys | Unsafe default behavior | false sends/intercepted keys | exact window configuration for deployment; context-sensitive hotkeys if approved |
| Combat presence | no detector | Not implemented | inputs outside combat | proposed confirmed-combat gate |
| Target identity | no detector | Not implemented | cross-relation stale state | proposed identity/change detection |

Confirmed combat gating and target identity are recommendations, not approved requirements.

## Timing and responsiveness

### Targets

- User target: approximately 30 checks/s initially; 60 only if sustainable.
- `scanInterval := 30` requests about 33.3 timer launches/s before overhead.
- 30 Hz budget ≈33.3 ms; 60 Hz ≈16.7 ms.
- Average, peaks and input latency require target-system combat measurement.

### Timer limitations

- AHK timers are pseudo-threads and do not provide CPU parallelism.
- A timer cannot preempt the middle of a long `ImageSearch`/`PixelSearch`.
- A timer whose own subroutine is already running may not launch another instance.
- `tapHoldMS := 30` is a minimum requested hold, not an exact physical duration.
- The separate 5 ms release timer removes deliberate `Sleep` blocking but cannot guarantee release deadline independence.
- A scanner period missed because the scanner is still running normally never enters the function; `skippedBusyScans` therefore does not count it.

### Grouped scan terminology

Approved compatibility requires all four target colors in one grouped decision update. The current implementation performs sequential commands, not one captured frame. One-frame capture is only an auditor recommendation.

### Staleness

Passive opening decay is community-documented, so queued/cached decisions can age without an intervening move. Queue timestamps/stale rejection and target-bound observations are recommended improvements, not approved requirements.

## Input scheduling

### Current behavior

- one physically held key;
- ordinary queue appends FIFO;
- priority manual requests insert at index 1;
- successive priority inserts are LIFO among themselves;
- duplicate active/pending key rejected;
- when `QueueTap()` accepts Sting, the 600 ms local throttle starts, `cachedTwoIP` becomes false, and `nextAttack` is immediately recomputed before actual key-down or confirmed game execution;
- queue item has key and hold duration only;
- on expiry, active key is released and next pending can be pressed in the same timer call;
- no enqueue timestamp, target identity, observation revision, focus validity, action availability, or execution confirmation.

### Risks

- older/newer manual attacks can reverse relative order;
- pending actions can become stale;
- focus loss can release/start keys in another application;
- a later tied defense is not revalidated;
- sending a key is treated operationally as a tap, not confirmed game execution.

### Required versus proposed

- **[Existing user-approved requirement]** Preserve nonblocking serialized down/up and manual `E`.
- **[Mechanics constraint]** Never overlap physically held combat action keys.
- **[Auditor-recommended improvement]** timestamp/reject stale items; revalidate later automatic ties; define whether newer manual requests replace older pending attacks; confirm execution.

## Image recognition requirements and compliance

### Search semantics

**[Auditor-recommended improvement]** A future redesign should carry `value + canonical state`; this is not yet implemented:

- found → valid positive observation;
- `valid_not_found` must remain distinct from `search_error`;
- UI absence requires `display_absent`, not a collection of failed feature searches;
- no error path should authorize Sting under the redesign.

Separately, **[Mechanics constraint]** unknown IP must not authorize Sting. This established safety constraint does not depend on approval of the proposed `value + canonical state` redesign.

### Templates

**Current behavior:** all expected paths must pass `FileExist()`.  
**Compliance:** existence checking implemented; validation not implemented.  
**Proposed:** decode/dimensions/transparency/anchor/load preflight.

### Bars

Bucket = number of matching 3×1 near-white probe bands. It is not a percentage and `0` is not `confirmed_zero`. `PixelSearch` error currently becomes unfilled.

### IP

- suffix guards distinguish detected 0/1 from `10/11...90/91`;
- during guard sequence Boolean state is conservative;
- both single-digit nonmatches/errors produce unsafe `cachedTwoIP := true`;
- maximum IP/values above 91 are unresolved;
- current implementation is noncompliant with the mechanics constraint “only confirmed 2+ authorizes Sting.”

### Cooldown

Found gray blocks defense; nonmatch/error clears the gate. Color/layout semantics are uncalibrated.

## F8/statistics behavior

**Current behavior:** global F8 opens `MsgBox`; timers may continue because AHK pseudo-threads can interrupt the F8 thread. The dialog can obscure ROIs.  
**Risk:** false tile/bar/cooldown/IP results, including false 2+ inference.  
**Affected components:** `F8`, `ShowRuntimeStats()`, scan/release timers.  
**Status:** documented only.  
**Proposed remediation:** pause scanners safely or display stats outside combat ROIs.

## Performance measurement

**[Auditor-recommended improvement]** Target-system acceptance evidence for a future implementation should include:

1. representative combat ≥60 seconds;
2. completed scans/s and scan duration;
3. defense observation-to-key-down latency;
4. multiple opponents, IP suffix-guard activity and cooldown transitions;
5. count of requested timer periods versus completed scans using an external/independent time basis;
6. F8 measured only under a safe pause/out-of-ROI procedure.

`skippedBusyScans` must not be reported as missed/overdue periods. Effective scans/s currently includes wall time when inactive focus returns are not counted as completed scans, so interpretation must state that limitation.

## Current implementation compliance matrix

| Topic | Required/current desired behavior | Current compliance |
|---|---|---|
| Manual `E` attack trigger | approved | Implemented, globally intercepted |
| Automatic defense | approved | Implemented |
| Serialized key holds | approved/mechanics | Implemented |
| No blocking hold sleep | approved | Implemented, but exact release not guaranteed |
| Grouped four-color decision | approved | Implemented sequentially; not one-frame coherent |
| Attack truth table | approved | Implemented |
| Confirmed 2+ IP | mechanics constraint | Noncompliant on dual nonmatch/error fallback |
| Recognition states | recommended redesign | Not implemented |
| Focus-loss scheduler cleanup | recommended safety | Not implemented |
| Combat-presence gate | auditor recommendation | Not implemented |
| Target identity/change | auditor recommendation | Not implemented |
| Tie revalidation | auditor recommendation | Not implemented |
| Queue timestamp/stale rejection | auditor recommendation | Not implemented |
| Per-action confirmation | auditor recommendation | Not implemented |
| One-frame capture | auditor recommendation | Not implemented |
| Full key configurability | earlier documentation overclaim | Not implemented |
| Template validation | earlier documentation overclaim | existence only |
| Real 30 Hz proof | approved performance target | Not verified |

## Acceptance tests

### Static/source tests

1. AHK v1 parses; no executable `Sleep` in scanner/hotkey/scheduler.
2. Every key-down has release/exit path.
3. Exact `ErrorLevel = 1` versus 2 branches exist before claiming state-compliant recognition.
4. All required template paths exist; separately test decode/dimensions when validation is implemented.
5. Four attack colors execute within one grouped decision batch.
6. All 18 suffix guards are enumerated; test maximum-IP assumptions separately.
7. `cachedTwoIP` starts false; dual nonmatch/error must not be accepted as confirmed in a corrected version.
8. `enableMultiDef` starts false; inclusive ROI endpoints preserved.

### Decision tests

- Reproduce all 1,250 legacy bucket/IP cases when the historical harness is available.
- Until the harness/results are included, record the zero-difference claim as historical and not independently reproduced.
- Add cases where nonlinear combined-opening ordering differs from sum ordering; expected result remains legacy truth table unless the user changes strategy.
- Test all-zero Quick Barrage as opener behavior and do not claim guaranteed IP gain.

### Recognition/error tests

- Force `ErrorLevel = 1` and 2 independently for tiles, probes, cooldown and IP.
- Obscure/remove combat/IP display; distinguish `display_absent`/`unknown`.
- Test 0, 1, 2–9, every `x0/x1` guard value, 90, 91, and >91 if valid.
- Switch targets during every IP phase.
- Show F8 over each ROI and verify safe behavior.

### Queue/focus tests

- Focus loss with active key and with multiple pending keys.
- Multiple differing `E` requests to expose priority LIFO.
- Distinct tied defenses; verify current blind second send and later proposed revalidation separately.
- Queue age/passive decay/target change.
- Wrong weapon/missing action/cooldown/range; do not treat tap as execution.

## Source register

- Current script and SHA-256 above.
- `combat-system-spec-r3.md`, `combat-interface-reference-r3.md`, `implementation-decisions-r3.md`.
- Pinned `Fightsess.java`: https://github.com/dolda2000/hafen-client/blob/bf45129905d4b1f03ebefe43b3237f8469453a42/src/haven/Fightsess.java
- Pinned `Fightview.java`: https://github.com/dolda2000/hafen-client/blob/223f516c88e98bcbf54f5f18be7d05f3c29f0c70/src/haven/Fightview.java
- AHK ImageSearch: https://www.autohotkey.com/docs/v1/lib/ImageSearch.htm
- AHK PixelSearch: https://www.autohotkey.com/docs/v1/lib/PixelSearch.htm
- AHK SetTimer: https://www.autohotkey.com/docs/v1/lib/SetTimer.htm
- AHK Threads: https://www.autohotkey.com/docs/v1/misc/Threads.htm
- Microsoft `timeBeginPeriod`: https://learn.microsoft.com/en-us/windows/win32/api/timeapi/nf-timeapi-timebeginperiod
- Official damage announcement: https://www.havenandhearth.com/forum/viewtopic.php?p=673607
- Combat guide: https://www.havenandhearth.com/forum/viewtopic.php?f=42&t=72160
- Historical original script/harness/results/assets/screenshots: not available in package; claims not reproduced during this audit.

## Revision history

- **1.2.0 — 2026-07-25:** Classified state-carrying redesign and target-system measurement criteria as auditor recommendations; kept unknown-IP Sting authorization as a separate mechanics constraint; documented startup Quick Barrage and Sting queue-admission behavior; updated R3 cross-references.
- **1.1.0 — 2026-07-25:** Resolved C1–C3, H1–H9, M1–M6. Separated approved requirements from recommendations; added exact current/compliance/remediation records, canonical states, global/focus/queue/timer/F8 limitations, formula/decay implications, and reproducibility-aware tests.
- **1.0.0 — 2026-07-25:** Initial canonical requirements.

## Future update instructions

1. Never turn an auditor proposal into `MUST` without explicit approval or a verified mechanics constraint.
2. Keep current behavior, required behavior, compliance and proposed remediation separate.
3. Add acceptance evidence with any implementation change and update all four files together.
