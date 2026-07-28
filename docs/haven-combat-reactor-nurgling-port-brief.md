# Haven & Hearth Combat Reactor: Functional Specification for a Nurgling2 Port

Prepared: 2026-07-27  
Purpose: Explain to Claude Code exactly what the existing AutoHotkey combat reactor is trying to automate, while defining how the same behavior should be adapted to Nurgling2 using internal client state instead of screen pixels.

## 1. The short version

This is a **defense automator plus manually triggered attack recommender/executor** for one specific combat deck.

While combat exists, the reactor should continuously:

1. Read the player's four current openings.
2. Read the current target's four openings.
3. Read the player's initiative points against that exact target.
4. Read combat relation identity, action availability, shared/per-action cooldowns, and queued/selected action state.
5. Automatically use the correct defensive restoration against the player's most dangerous opening when it is safe to do so.
6. Continuously choose which of three attacks would be requested next:
   - Quick Barrage
   - Full Circle
   - Sting
7. Execute that chosen attack **only when the user presses the configured manual attack trigger**. The current trigger is `E`.
8. Never overlap combat action presses, never use Sting without confirmed sufficient IP, and invalidate relation-dependent decisions when the target changes or combat disappears.

The AHK implementation finds this information by scanning fixed screen rectangles and then sends keyboard taps. The Nurgling2 port should preserve the approved decisions, **not the pixel-reading method or its known defects**. It should read the client objects and server-driven widget state directly and invoke combat actions through the same client message path used by normal combat input.

### Authority labels used in this brief

- **Approved behavior:** preserve unless the user deliberately changes it.
- **Mechanics/safety constraint:** required to avoid contradicting established mechanics.
- **Recommended Java-port improvement:** safer or cleaner target design, but not previously approved merely because an auditor proposed it.
- **Current AHK quirk/defect:** document for comparison; do not automatically preserve.
- **Unresolved:** Claude must investigate the checked-out source or leave the matter undecided.

Combat-presence gating, tied-defense revalidation, queue timestamps/stale rejection, target-change detection, per-action execution confirmation, and coherent internal snapshots are **recommended Java-port improvements**. They are not being retroactively represented as requirements that the user had already approved. They are included because this brief describes the optimized port target; Claude should keep them separable so the user can accept or alter their exact policy.

## 2. Scope and non-scope

### The reactor does

- Automatically defend against the player's current openings.
- Maintain a live recommendation among Quick Barrage, Full Circle, and Sting.
- Fire the recommendation when the user presses `E`.
- Serialize action requests.
- Respect relation-specific IP and current-target state.
- Prefer fast reaction and event-driven state updates.

### The reactor does not

- Pick targets.
- Start combat.
- end combat.
- Chase, flee, pathfind, drink, manage stamina, manage equipment, or change decks.
- Automatically fire offensive attacks without the user's `E` request.
- Solve every possible combat deck.
- Calculate exact damage.
- Change maneuvers.
- Assume that a sent action was accepted or executed unless the client/server state confirms it.

This is intentionally a narrow reactor for the user's present combat deck and workflow, not a general combat AI.

## 3. Required deck/action mapping

Claude must resolve actions by their resource identity or canonical action identity, not by hard-coded keyboard characters where possible.

| Purpose | Move | Combat meaning | Current AHK key |
|---|---|---|---|
| Green defense | Quick Dodge | Reduces Striking / green / Off Balance | `A` |
| Blue defense | Sidestep | Reduces Backhanded / blue / Dizzy | `D` |
| Red and yellow defense | Zig-Zag Ruse | Reduces Oppressive/red/Cornered and Sweeping/yellow/Reeling | `W` |
| Optional multi-defense | Unconfirmed current binding/move | Disabled until positively identified | `S` |
| Basic/opening attack | Quick Barrage | Oppressive/red attack; 0 IP | `;` |
| Main red/yellow attack | Full Circle | Oppressive + Sweeping attack; 0 IP | `,` |
| Green/blue attack | Sting | Striking + Backhanded attack; costs 2 IP; requires pointed weapon | `Q` |
| Manual offensive trigger | User command | Execute current recommendation | `E` |

The action bar can contain up to ten actions. The official client exposes the action array and receives action resources through the `act` UI message. Per-action cooldown timing arrives through `acool`; the current official client converts the supplied cooldown unit using `× 0.06` seconds. See the current [`Fightsess.java`](https://github.com/dolda2000/hafen-client/blob/master/src/haven/Fightsess.java).

The Nurgling port should:

- locate configured moves by resource name/ID;
- verify that each required action is actually in the active combat deck;
- expose a clear unavailable/configuration state when a required move is missing;
- avoid silently assuming that action-slot numbers or keyboard bindings never change.

## 4. State the reactor must observe

The port needs one current immutable or consistently locked combat snapshot containing the following.

### 4.1 Combat presence

- Whether `Fightview`/`Fightsess` exists and is usable.
- Whether at least one combat relation exists.
- Whether there is a current relation.
- Whether the automation is enabled.
- Whether the game UI is still active and not being destroyed, logged out, or replaced.

If combat is absent, the reactor sends nothing and clears pending automatic work.

### 4.2 Current relation identity

- Stable identity of the current `Fightview.Relation`.
- Opponent gob ID or the best stable relation key exposed by this client revision.
- A monotonically increasing local relation/snapshot revision.

Opponent openings and player IP are relation-specific. When current relation identity changes:

- invalidate cached opponent openings;
- invalidate cached IP;
- invalidate the current attack recommendation until a fresh snapshot exists;
- discard automatic actions tied to the old relation;
- do not allow an old `E` request to fire on the new relation accidentally.

The official input code also exposes relation cycling and a current relation. Its normal combat key path releases a previously held action before sending the next `use` message, and sends a corresponding `rel` message on release. Preserve that ordering rather than simulating unrelated OS keystrokes. See [`Fightsess.java`](https://github.com/dolda2000/hafen-client/blob/master/src/haven/Fightsess.java).

### 4.3 Player openings

Read all four live player-side opening values:

- Striking / green / Off Balance
- Backhanded / blue / Dizzy
- Sweeping / yellow / Reeling
- Oppressive / red / Cornered

Use the actual numeric values represented by client buff/opening state. Do not reduce them to four screen-probe buckets unless the client genuinely exposes nothing better.

The reactor needs all four values from the same logical snapshot. Missing, loading, or unrecognized buff resources must remain unknown; they must not silently become zero.

### 4.4 Current target openings

Read the same four opening values for the current relation's opponent.

These values feed the attack truth table. Keep all four colors separate until the attack decision is calculated. Do not merge red and yellow merely because their defensive moves share one action.

### 4.5 Initiative points

Read the player's exact IP against the current relation.

Important rules:

- Sting costs 2 IP.
- Sting is eligible only when IP is positively known to be at least 2.
- Unknown, absent, stale, or relation-mismatched IP must never authorize Sting.
- Do not carry IP from one target to another.
- Prefer the direct relation field updated by server messages; do not parse rendered digits.

The old AHK script uses `0`, `1`, and `10/11...90/91` image templates because a digit template can match a multi-digit suffix. That entire image-disambiguation state machine should disappear in the Java port.

### 4.6 Shared and per-action cooldowns

Read:

- shared/global combat cooldown, if represented separately;
- each configured action's `cs`/`ct` or equivalent cooldown interval;
- current selected/queued action (`use`, `useb`, or the equivalent in the checked-out source);
- whether the action is actually available to request now.

Do not copy the AHK detector that searches for a gray pixel. The official client already receives per-action cooldown updates and selected/queued state. Claude must inspect the exact Nurgling2 and bundled Hafen revision to determine the safest accessor/event hook.

### 4.7 Action execution/acknowledgement

Differentiate:

1. recommended;
2. requested by user or defense policy;
3. accepted into the local scheduler;
4. sent through the client;
5. selected/queued by the game;
6. actually executed/observed through server-driven state.

A keyboard tap or `wdgmsg("use", ...)` is not proof of execution. Range, cooldown, IP, weapon requirements, movement cancellation, relation changes, or server rejection can prevent the intended result.

## 5. Recognition/state semantics

The original documentation defines seven states because “not found” and “zero” are not the same. A direct-client port can often avoid screen-recognition errors, but it still needs equivalent validity semantics.

| State | Meaning in the Java port |
|---|---|
| `confirmed_zero` | The current relation/widget state positively reports numeric zero. |
| `valid_not_found` | A requested resource/action/buff was searched for correctly but was not present. This does not automatically prove a numeric zero unless absence is the protocol's defined zero representation. |
| `display_absent` | The combat widget/relation/display is positively absent. |
| `search_error` | Resource loading or lookup failed unexpectedly. |
| `unknown` | The value cannot currently be established. |
| `stale` | The observation was valid but is no longer safe because of age, target change, intervening state, or widget replacement. |
| `unavailable` | The required action, weapon capability, resource, or configuration is positively unavailable. |

For action authorization, fail closed:

- unknown openings: do not auto-defend from them;
- unknown cooldown: do not send an automatic defense;
- unknown IP: never Sting;
- absent combat/current relation: send nothing;
- unavailable action: report it and do not substitute another action unless the explicit decision table says to.

## 6. Defensive behavior

Defense is automatic and latency-sensitive. It has priority over recomputing the offensive recommendation.

### 6.1 When defense evaluation runs

Evaluate whenever any relevant input changes:

- player opening update;
- cooldown transition to ready;
- action/deck availability change;
- combat relation creation/removal;
- automation enable/disable;
- execution/queue state change.

A light periodic reconciliation tick may exist as a safety net, but the primary implementation should be event-driven. The previous target was about 30 checks per second because AHK had to poll pixels. Internal events do not need wasteful 30–60 Hz image scanning.

### 6.2 Defense decision

1. Confirm combat/current relation and a valid player-opening snapshot.
2. Confirm no conflicting action is held, being released, or already queued by this reactor.
3. Confirm the shared cooldown and the candidate move's cooldown permit a request, or deliberately allow the client to queue it according to an explicitly chosen policy.
4. Find the highest player opening value greater than zero.
5. Preserve every color tied at that highest value as a candidate.
6. Map candidates to moves:
   - green → Quick Dodge;
   - blue → Sidestep;
   - red → Zig-Zag Ruse;
   - yellow → Zig-Zag Ruse.
7. Deduplicate by action identity. A red/yellow tie produces one Zig-Zag request, not two.
8. Request the selected defense through the normal client action mechanism.

The old compatibility policy queues every **distinct** defensive action tied for highest pressure. That means a green/blue tie can request both Quick Dodge and Sidestep in serialized order. This behavior is historically approved, but the second action can be stale after the first one executes.

Therefore, implement tied defenses as **candidates requiring revalidation before each send**, unless the user deliberately requests exact blind-queue compatibility. Revalidation should confirm:

- combat/current relation still valid;
- openings still make that defense a top candidate;
- action still available;
- no newer defense decision superseded it;
- cooldown/queue state permits it.

This preserves “handle ties” without copying the AHK's known stale second-send defect.

### 6.3 Optional multi-defense

The AHK contains an optional `S` action when multiple colors are detected and the highest coarse bucket is low. It is disabled by default because the exact move and side effects were never verified.

Keep this feature disabled and do not infer what `S` means. Only add it after Claude identifies the configured move/resource and the user approves its policy.

### 6.4 Why these defenses

- Quick Dodge restores green/Striking.
- Sidestep restores blue/Backhanded.
- Zig-Zag Ruse restores both yellow/Sweeping and red/Oppressive.

Zig-Zag has a major side effect: it grants 2 IP to every opponent in combat with the user. That is part of the move's mechanics and is one reason action choice must be explicit rather than a generic “clear colors” solver.

## 7. Offensive recommendation behavior

Offense is **recommended continuously but fired manually**.

The current user-approved strategy is a legacy three-move truth table. It is not an exact damage optimizer and must not be replaced with a new formula without a deliberate strategy change.

Let:

- `R` = current target's red/Oppressive opening;
- `Y` = current target's yellow/Sweeping opening;
- `G` = current target's green/Striking opening;
- `B` = current target's blue/Backhanded opening;
- `hasTwoIP` = current relation IP is confirmed `>= 2`;
- `FC = R + Y`;
- `STING = G + B`.

Use actual comparable opening values in the Java port. The old AHK uses 0–4 probe counts, but the decision structure is the part to preserve.

### 7.1 Exact attack truth table

```text
if R == 0 and Y == 0 and G == 0 and B == 0:
    recommend Quick Barrage

else if hasTwoIP is false:
    if FC > 0 and FC >= STING:
        recommend Full Circle
    else:
        recommend Quick Barrage

else:  # exact current-relation IP is confirmed >= 2
    if STING > FC:
        recommend Sting
    else if FC > 0:
        recommend Full Circle
    else:
        recommend Quick Barrage
```

Tie behavior is intentional:

- `FC == STING` favors Full Circle when `FC > 0`.
- Sting requires a strict `STING > FC`.
- Zero red/yellow pressure prevents Full Circle.
- Unknown IP is treated as not sufficient for Sting.

### 7.2 Why Quick Barrage is selected at zero

Quick Barrage at all-zero openings is an opener/deck-flow choice, not a damage-maximizing hit.

- It costs 0 IP.
- It creates red/Cornered opening.
- It can gain 1 IP only when the target's red opening is **strictly greater than 25%**.
- At zero matching red opening it has no opening-derived damage under the historically documented formula.

The old AHK cannot tell whether red is exactly above 25% because it uses coarse probes. The Java port should use the exact client-represented opening value if available, but the approved truth table still does not need a separate 25% branch unless the user changes the strategy.

### 7.3 Why the sums are a strategy heuristic

Historically documented combat damage combines matching openings nonlinearly:

\[
O_\text{combined}=1-\prod_i(1-O_i)
\]

\[
D_\text{final}=D_\text{base}\times O_\text{combined}^2
\]

The current-server implementation of that exact formula and the matching-types-only rule were not independently reverified in the canonical audit. The reactor therefore must not claim that `R + Y` versus `G + B` is exact damage math. It is simply the user-approved compatibility policy.

The community combat guide describes Quick Barrage, Full Circle, and Sting as a combo/deck sequence and lists Quick Barrage as a red opener and Full Circle as the red/yellow follow-up. See the [Haven & Hearth combat guide](https://www.havenandhearth.com/forum/viewtopic.php?f=42&t=72160).

## 8. Manual `E` attack behavior

`E` does not mean “always Quick Barrage” or “automate a full combo.” It means:

> Request the attack currently recommended from the newest valid snapshot.

On `E`:

1. Confirm reactor enabled, combat present, and current relation valid.
2. Obtain or recompute the latest attack recommendation atomically.
3. Confirm that the recommendation belongs to the same relation revision.
4. Confirm that the configured action is present.
5. For Sting, reconfirm exact current IP `>= 2` and pointed-weapon/action eligibility when the client exposes it.
6. Submit one request to the serialized action executor.
7. Do not interrupt an action already held/sent in a way that violates normal `use` then `rel` ordering.

The current AHK initializes `nextAttack` to Quick Barrage before its first scan, so early `E` can request Quick Barrage without a valid combat snapshot. That is current behavior, not a desirable Java-port behavior. The port should initially expose `UNKNOWN/NO_RECOMMENDATION` until current relation, openings, and action availability are valid.

### Sting request handling

The AHK starts a 600 ms local Sting throttle, marks cached IP insufficient, and recomputes the recommendation when Sting is merely admitted to its local key queue. This occurs before key-down and without execution confirmation.

For the Java port:

- retain duplicate-request suppression;
- do not use the 600 ms throttle as proof of game cooldown or execution;
- bind the request to relation identity;
- use actual per-action/shared cooldown state;
- treat IP as server/client state, not permanently mutate the authoritative observed value locally;
- an optional short local debounce may suppress repeated `E` events, but it must be labeled as input debouncing;
- refresh the recommendation when IP/cooldown/selected-action state updates.

## 9. Action scheduler and client-message behavior

### 9.1 Core invariant

Only one combat action lifecycle may be active through this reactor at a time.

The official client input implementation tracks a held slot, sends `use`, and schedules `rel` in sequence. Claude should reuse or wrap the same mechanism rather than generating OS-level key-down/key-up events. Directly calling messages without respecting UI-thread and render-fence sequencing may behave differently from ordinary input, so inspect and follow the checked-out client implementation.

### 9.2 Queue entry

Each request should contain at least:

- action identity/resource and resolved current slot;
- origin: automatic defense or manual `E`;
- relation ID/revision;
- decision/snapshot revision;
- enqueue time;
- expiry/staleness policy;
- required preconditions;
- status: proposed, accepted, sent, queued, executed, failed, cancelled;
- optional diagnostic reason.

### 9.3 Priority

- Automatic defense is latency-sensitive.
- Manual `E` is intentional and should not be lost.
- Neither should blindly interrupt the protocol lifecycle of an already active action.
- Exact conflict policy should be explicit and testable.

A practical policy is:

1. finish/release the active action safely;
2. invalidate any stale automatic candidate;
3. re-evaluate urgent defense;
4. retain at most one pending manual attack for the current relation, using newest valid recommendation;
5. never allow repeated timer updates to flood duplicates.

This improves on the AHK queue, where normal requests append FIFO, manual priority insertions become LIFO among themselves, and entries have no timestamp or relation identity.

### 9.4 Cancellation

Cancel pending actions when:

- reactor disabled;
- combat/current relation disappears;
- current relation changes;
- required action becomes unavailable;
- request expires;
- a newer decision supersedes an automatic request;
- game/client widget is removed;
- logout/session transition occurs.

Release/finish any active client action through the proper client lifecycle during shutdown or disable.

## 10. Recommended architecture inside Nurgling2

Exact class names must follow the checked-out source, but keep responsibilities separated.

### `CombatStateAdapter`

Reads or subscribes to:

- `Fightview` relation list/current relation;
- player and opponent buff/opening collections;
- relation IP fields;
- `Fightsess.actions`;
- `Action.cs`/`Action.ct`;
- shared cooldown data;
- selected/queued action fields;
- relation add/change/delete messages;
- action/resource loading state.

It emits one immutable `CombatSnapshot`.

### `CombatDecisionEngine`

Pure logic only:

- `chooseDefence(snapshot) -> candidate actions`
- `chooseAttack(snapshot) -> Quick Barrage | Full Circle | Sting | none`
- no widget messages;
- no sleeps;
- no pixel logic;
- deterministic and unit-testable.

### `CombatActionExecutor`

- resolves action to the current slot;
- runs required calls on the proper UI/client thread;
- preserves normal `use`/`rel` sequencing;
- serializes requests;
- revalidates preconditions;
- tracks acknowledgement/selected/queued/executed state;
- cancels stale work.

### `CombatReactorController`

- enable/disable lifecycle;
- subscribes to state events;
- prioritizes defense;
- maintains current recommendation;
- handles the configured manual `E` command;
- owns diagnostics and settings.

### UI/settings

Expose at minimum:

- enabled state;
- current target/relation;
- four player openings;
- four opponent openings;
- current IP;
- recommended attack and reason;
- required action availability;
- active/pending action;
- last rejection/failure reason;
- configurable trigger/bindings or resource mapping;
- safe stop.

Do not put the implementation primarily in `haven/*` if a clean Nurgling extension/hook can provide the needed access. The user's priority is to minimize upstream-merge damage. If base-client access changes are unavoidable, keep them as small generic accessors/events and place reactor behavior in the `nurgling` package.

## 11. Event loop in plain language

### On combat appearing

1. Discover the fight session and current relation.
2. Resolve configured deck actions by resource.
3. Build a fresh snapshot.
4. Set no recommendation until required relation/opening state is valid.
5. Evaluate defense.
6. Compute the first attack recommendation.

### On any opening update

1. Produce a new snapshot revision.
2. If it is a player opening update, re-evaluate automatic defense first.
3. If it is the current target's opening update, recompute the attack recommendation.
4. Invalidate queued automatic work derived from an older snapshot if its preconditions no longer hold.

### On IP update

1. Verify it belongs to the current relation.
2. Recompute the attack recommendation.
3. Sting becomes eligible only at confirmed `>= 2`.
4. If IP falls below 2, cancel an unsent Sting request whose precondition is no longer true.

### On cooldown/action-state update

1. Update the snapshot.
2. If cooldown becomes ready, re-evaluate defense.
3. Advance the action executor only after revalidation.
4. Update request status and diagnostics.

### On `E`

1. Read the latest valid snapshot.
2. Recompute or verify recommendation.
3. Enqueue one relation-bound manual attack request.
4. Execute when protocol-safe and still valid.

### On target/relation change

1. Increment relation revision.
2. Cancel old relation-bound pending work.
3. Clear opponent openings, IP, and recommendation.
4. Wait for current-relation state.
5. Recompute from the new relation only.

### On combat ending, disable, or shutdown

1. Stop new decisions.
2. Cancel pending work.
3. safely complete/release any active action lifecycle;
4. clear state and recommendation.

## 12. Mechanics and behavior that must not be misrepresented

- Player IP and opponent openings are relation-specific.
- Quick Barrage costs 0 IP; its IP gain requires red strictly above 25%.
- Sting costs 2 IP.
- Full Circle evaluates red/yellow and can affect secondary opponents in range, but the decision engine evaluates the current target only.
- Zig-Zag reduces red/yellow but grants every opponent 2 IP.
- Opening values may decay while standing still according to community documentation; cached decisions can age even without an action.
- A client action request can be queued for range/cooldown and movement can cancel it.
- Sent is not the same as executed.
- The legacy red+yellow versus green+blue sum is a policy, not verified current damage math.
- The optional multi-defense is not verified and stays off.

## 13. Validation and acceptance tests

### Pure decision tests

Test all meaningful combinations, including:

- all zero → Quick Barrage;
- no 2 IP, `FC > STING` → Full Circle;
- no 2 IP, `FC == STING > 0` → Full Circle;
- no 2 IP, `STING > FC` → Quick Barrage;
- 2+ IP, `STING > FC` → Sting;
- 2+ IP, tie → Full Circle;
- 2+ IP, `FC == 0`, `STING > 0` → Sting;
- unknown IP never → Sting;
- unknown required openings → no unsafe action.

### Defense tests

- each single highest color chooses its mapped move;
- red/yellow tie deduplicates to one Zig-Zag;
- green/blue tie preserves both candidates;
- second tied candidate is revalidated after the first;
- zero openings sends no defense;
- unknown/cooldown-invalid state sends no defense;
- missing configured move yields unavailable, not a substitute key;
- repeated identical updates do not flood requests.

### Relation tests

- switch targets during every executor state;
- IP from target A never authorizes Sting on target B;
- old manual/automatic requests cannot cross relations;
- relation deletion cancels work;
- cycling current relation rebuilds the recommendation.

### Action protocol tests

- verify `use` and `rel` ordering matches normal client input;
- verify all client calls occur on required UI/render threads;
- action on cooldown;
- action queued for range;
- movement cancellation;
- missing pointed weapon for Sting;
- deck/action removed or changed;
- shared cooldown changes between decision and send;
- sent request with no execution acknowledgement.

### Lifecycle tests

- enable during combat;
- disable with pending work;
- disable while an action is active;
- combat widget removal;
- logout/session reset;
- no actions after stop.

### Performance tests

- measure state-update-to-defense-request latency;
- run representative multi-opponent combat for at least 60 seconds;
- verify no blocking sleeps on the UI thread;
- verify no busy polling where an event exists;
- log dropped/superseded/stale requests by reason;
- confirm UI remains responsive.

## 14. Implementation order for Claude Code

1. Inspect the exact Nurgling2/Hafen classes and identify authoritative fields/events for relations, openings, IP, action slots, shared cooldown, per-action cooldown, and selected/queued/executed action.
2. Inspect existing Nurgling automation examples for UI-thread dispatch, stop/cancel lifecycle, settings, hotkeys, and widget-message helpers.
3. Produce a source map before editing: field/event → adapter output → evidence that it is authoritative.
4. Implement immutable snapshot and pure decision tests first.
5. Implement read-only diagnostics UI and verify values against the visible combat UI.
6. Implement the action executor using the normal client protocol path.
7. Enable manual `E` attacks.
8. Enable one automatic defense at a time.
9. Add tied-defense candidate revalidation.
10. Run lifecycle, relation-switch, cooldown, missing-action, and failure tests.

Do not begin by translating `ImageSearch`, screen coordinates, 30 ms key holds, the IP template state machine, or AHK timers into Java. Those are workarounds for being outside the client.

## 15. Evidence and source notes

Primary/current client evidence:

- Official current [`Fightsess.java`](https://github.com/dolda2000/hafen-client/blob/master/src/haven/Fightsess.java): action array, per-action `cs`/`ct`, `act`, `acool`, `use`, held action, and ordered `use`/`rel` input lifecycle.
- Canonically audited pinned [`Fightsess.java`](https://github.com/dolda2000/hafen-client/blob/bf45129905d4b1f03ebefe43b3237f8469453a42/src/haven/Fightsess.java).
- Canonically audited pinned [`Fightview.java`](https://github.com/dolda2000/hafen-client/blob/223f516c88e98bcbf54f5f18be7d05f3c29f0c70/src/haven/Fightview.java).
- Public [Nurgling2 repository](https://github.com/Katodiy/nurgling2). Claude should prefer the user's checked-out source because it may be newer or forked.

Mechanics/community evidence:

- [Haven & Hearth combat guide](https://www.havenandhearth.com/forum/viewtopic.php?f=42&t=72160): deck composition, defenses, Quick Barrage/Full Circle/Sting sequence, and combat behavior descriptions.
- [Official “Bumfights” opening-system announcement](https://www.havenandhearth.com/forum/viewtopic.php?p=673607): historical opening/damage documentation.
- [Ring of Brodgar Combat Moves, pinned audited revision](https://ringofbrodgar.com/index.php?title=Combat_moves&oldid=122547): move costs, types, openings, cooldowns, and side effects as audited on 2026-07-25.
- [2024 combat discussion](https://www.havenandhearth.com/forum/viewtopic.php?t=77003): community corroboration for waiting for openings to decay.

Local canonical inputs used for this brief:

- `combat-system-spec-r3.md`
- `combat-interface-reference-r3.md`
- `automation-requirements-r3.md`
- `implementation-decisions-r3.md`
- `Haven_Combat_Reactor_Optimized_Audited.ahk`

## 16. Authority rule

When the checked-out Nurgling2/Hafen source disagrees with an assumed field name or architecture in this brief:

1. the checked-out source controls implementation details;
2. the canonical documents control evidence/confidence and approved behavior;
3. the attack truth table and manual `E` firing remain preserved unless the user deliberately changes strategy;
4. verified server/client mechanics override AHK assumptions;
5. unresolved mechanics remain unresolved rather than being guessed.
