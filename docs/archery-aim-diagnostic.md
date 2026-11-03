# Archery aim diagnostic (mid-draw re-aim experiment)

**Status: current — describes a temporary, opt-in diagnostic. Its in-game behavior has not been run
live yet. Nothing in this document proves that mid-draw re-aiming is possible or impossible.**

This document owns two things: the code trace of how a bow shot is started and targeted in this
client, and how to run the diagnostic that tests whether a shot can be redirected while the bow is
drawing. It does not describe an aim-assist feature; none exists.

## 1. Question and evidence standard

Observed in play: activate Shoot, click a spot, the bow draws for about 1–2 s with a progress ring,
and further clicks on empty ground during the draw do not redirect the shot. Other players reportedly
change direction once before release. The question is whether any client-sendable message changes the
**server-authoritative** trajectory of a shot that is already drawing.

Three different things must not be confused:

| Observation | What it proves |
|---|---|
| The client sent a message | Only that it was sent. |
| The player's facing (`Gob.a`) or pose changed | The server (or local prediction) turned the model. Not where the arrow goes. |
| The current combat target changed (`Fightview "cur"`) | The server accepted a target selection. Not where the arrow goes. |
| **The arrow flew toward the correction and the draw did not restart** | Re-aim works. Only the player's own eyes report this; the diagnostic records it as a manual observation. |

The server's implementation is not available. Every statement below about what the server *does* with
a message is either marked as observed in client source (what the client sends/receives) or as
something to confirm in play.

## 2. Code trace (verified against current source)

### 2.1 Activating Shoot

- Shoot is a server-defined menu action (pagina). **Its resource name and action arguments do not
  appear anywhere in client source** — there is no "shoot" string under `src/`. The diagnostic logs
  the actual `"act"`/`"belt"` message the player sends instead of assuming it.
- Menu grid / menu hotkey: `MenuGrid.PagButton.use` (`src/haven/MenuGrid.java`) sends, from the
  `MenuGrid` widget, `"act", ad..., modflags` — and appends `mc` and gob click args only when the
  `Interaction` carries a map position. Menu-grid clicks and menu hotkeys use `new Interaction()`,
  so they carry no position.
- Action belt keys: `GameUI.Belt.keyact` runs a map hit-test under the mouse and sends, from
  `GameUI`, `"belt", slot, button, modflags, mc, [gob click args]` (`GameUI.BeltSlot.use`). This is
  the only activation path that carries a target position.
- After activation the server sets the cursor with a `"curs"` message, handled generically in
  `Widget.uimsg` (`root.cursorRes`). **That cursor is the only client-side "targeting active" state.**
  There is no Shoot-specific flag in the client.

### 2.2 Starting the draw

- The click that starts the draw is an ordinary `MapView` `"click"` (`MapView.Click.hit`). The client
  builds it the same way whether it means "walk", "use" or "shoot here"; the server interprets it
  against the current cursor/action.
- **Nothing in the client blocks clicks during a draw.** `MapView.mousedown` always runs a `Click`
  unless the map is in DRAG mode, placing, or grabbed. `Click.hit` has fork intercepts (Alt+Shift
  chat/point ping, Alt waypoint, milestone splice, nurgling overlay widgets, route recording); none is
  Shoot-related. So the "clicks are ignored mid-draw" behavior is decided by the server (or by the
  cursor having already reverted, which would turn later clicks into walk orders — the log shows the
  cursor at every click).

### 2.3 Progress, release, poses

- Draw progress arrives as `"prog"` to `GameUI` (`GameUI.uimsg`): one number 0–100, or **no
  arguments** to remove the indicator. `GameUI.Progress` and `nurgling.tools.ProgressRate` only draw
  it and estimate time left.
- `"prog"` is generic: crafting, digging and every other timed action use it. The client cannot tell
  a bow draw from another action by the progress message alone. The diagnostic links it to Shoot by
  timing (first progress after the shot click while an action cursor was active) and an optional
  cursor-name filter.
- **The client has no distinct "arrow released" event.** Release, interruption and completion all look
  like the progress message without arguments. Supporting signals: the player's pose
  (`Composite.current_pose`, the normal poses; temporary poses are private) and facing (`Gob.a`). A
  projectile gob appearing would be another; the diagnostic does not watch for it.

### 2.4 Targeting paths compared

| # | Path | Sender widget | Message and arguments | Gob identity |
|---|---|---|---|---|
| 1 | World click, empty ground | `MapView` | `"click", pc, mc, button, mods` | none |
| 2 | World click on a gob | `MapView` | `"click", pc, mc, button, mods, olFlag, gobId, gobRc, olId, meshId` | yes; mc is the terrain point under the cursor |
| 3 | Corner minimap gob icon | `MapView` (via `MiniMap.mvclick`) | `"click", pc=mouse, mc=tile centre, button, mods, 0, gobId, gobRc, 0, -1` | yes |
| 4 | Map window gob icon | `MapView` (via `MiniMap.mvclick`) | same as 3; sent on release instead of press | yes |
| 5 | Combat portrait (`Fightview.Relbox`/`Mainrel`) | `Fightview` | `"click", gobId, button` | yes; no position at all |
| 6 | Action belt key with mouse over map | `GameUI` | `"belt", slot, button, mods, mc, [olFlag, gobId, gobRc, olId, meshId]` | optional |
| 7 | Party portrait (`Partyview`) | `Partyview` | `"click", gobId, button` | yes (party members only; not tested) |

- Paths 3 and 4 are the same message as path 2 with a different `pc` and `mc`. Unless the server reads
  `pc`, which nothing suggests, they are not a separate mechanism. **There is no separate
  "minimap method" in the diagnostic**; method B sends this shape.
- Path 5 is a different message to a different widget. Clicking a portrait only *asks* to make that
  relation current; the server answers with `Fightview "cur"`. Whether the server aims a shot at the
  current combat target is unknown.
- Path 6 re-activates the action itself with a target. It is the most plausible explanation for "other
  players re-aim" that the code shows, but it also may start a new shot. The diagnostic **never sends
  it**; it only logs it if the player presses the belt key during a test.

### 2.5 Combat state

`Fightview.current` (set by the server's `"cur"`) and `lsrel` are the client's only combat-target
state. The existing Combat Reactor (`CombatStateAdapter`, `CombatReactorController`) reads them and is
untouched by this work. The diagnostic never starts combat, never uses the Attack action, and refuses
method D unless a relation already exists.

## 3. Which hypotheses are still open

Each needs a live test; the code cannot settle them:

1. A second ground click during the draw is ignored by the server (baseline, method A).
2. A gob-targeted click during the draw is treated differently from a ground click (method B).
3. Changing the current combat target during the draw redirects the shot (method D).
4. Re-activating Shoot from a belt key with the mouse over a new point re-aims instead of restarting
   (manual only: press the belt key during an armed test; the log records the exact `"belt"` message).
5. Timing: a correction early in the draw is treated differently from one near release (vary the
   delay or progress threshold).

## 4. The diagnostic

**Files:** `src/nurgling/archery/AimProtocol.java` (message shapes and classification),
`AimDiagnostic.java` (per-session state machine), `AimDiagnosticHub.java` (`UiEvents` listener and
session binding), `AimDiagnosticWindow.java` (controls),
`src/nurgling/actions/bots/ArcheryAimDiagnosticAction.java` (Bots menu entry, Utils section).

**Design rules it follows:**

- Off by default. The `UiEvents` listener is registered only while a diagnostic window is open in some
  session. Closing the window cancels any test and detaches.
- Bound to the session that opened it. Events from other sessions are dropped; the correction is sent
  through that session's own `GameUI.map` / `GameUI.fv`. It never calls `NUtils.getGameUI()`.
- One armed test = at most one correction. No retries, no repeats, never activates Shoot, never starts
  the draw. A finished, cancelled or timed-out test disarms itself; the player must arm again.
- No permanent polling. A single daemon timer thread runs the delay, the timeouts, and a bounded
  100 ms facing/pose sample that exists only between the shot click and the end of the test (at most
  the draw time plus 3 s). Changes are logged, not every sample.
- Safety aborts (nothing is sent): no shot click within 60 s of arming; no progress indicator after
  the shot click (when "Require draw progress" is on); progress ended before the correction time;
  progress never reached the threshold within 6 s; target gob gone; combat relation gone; sender
  widget missing; user cancel; diagnostic disabled; window closed; session ended (GameUI destroyed).

**Methods:**

| Method | Sends | Needs | Risk |
|---|---|---|---|
| A — ground | `MapView "click", (0,0), B, 1, 0` | point B | If Shoot is no longer active, the server may read it as "walk to B". Must tick the risk box. |
| B — gob | `MapView "click", (0,0), gobRc, 1, 0, 0, gobId, gobRc, 0, -1` with the gob's **current** position at send time | a gob within 20 tiles (players and own character are not offered) | If Shoot is no longer active, it may walk to or interact with the gob. Must tick the risk box. |
| D — portrait | `Fightview "click", gobId, 1` | an **existing** combat relation | Changes the selected combat target. Never creates a relation. |

## 5. How to run a test safely

Use a quiet spot, outside combat, with no players nearby. Use your own target dummy, a tree, a rock or
a stump as the gob for method B.

1. Open the Bots menu → Utils → **Archery Aim Diagnostic**.
2. Tick **Diagnostic mode**.
3. Choose the method (click the method button to cycle).
   - Method A: stand where you want point B, press **Set B = my position**, then walk somewhere else.
   - Method B: press **Next nearby gob** until the label names the object you want.
   - Method D: only if you already have a combat relation; press **Next combat relation**.
4. Choose the trigger: fixed delay (start with 500 ms) or progress % (start with 50 %).
5. Keep **Require draw progress** ticked for the first runs.
6. Optional: after a first run, copy the Shoot cursor name from the log (`cursor -> ...`) into the
   cursor filter, so only a click with that cursor counts as the shot.
7. For methods A and B, read the risk line and tick **I understand this method's risk**.
8. Press **Arm one test**.
9. Activate Shoot the way you normally do, and click the original spot A. Do nothing else.
10. Watch where the arrow goes and whether the draw ring started again.
11. Within the next minute, record what you saw: one arrow button (To original / To correction /
    Elsewhere / Not seen) and one restart button (Draw did not restart / Draw restarted / Unsure).
12. To try again, change a setting if you want, and press **Arm one test** again.

**Cancel test** stops a pending test at once. Closing the window does the same and disables the
diagnostic.

## 6. What to send back

For each test, send:

- The log file named at the bottom of the window (`archery-aim-diag-<date>-<time>.log` in the client
  data directory). The whole file is fine; it holds only diagnostic lines.
- The bow used, method, trigger value, and roughly how far apart A and B were.
- Your observation: arrow direction and whether the draw restarted (also recorded in the log as
  `MANUAL OBSERVATION`).
- Anything the log cannot show: what happened on screen, whether the character turned, any message in
  the system chat.

The key log lines are `action:` (the real Shoot activation message), `SHOT CLICK` (the click that
started the draw, with the cursor), `prog START/RESET/END`, `CORRECTION SENT (once)`, `wire:` (the
correction as it went out), `facing`, `pose`, `incoming Fightview "cur"`, `ABORT`/`TIMEOUT`/`CANCELLED`
and `=== END` (automatic results and the verdict, kept separate from the manual observation).

## 7. Verification status

- `ant jar` and `ant test` pass. Unit tests (`test/nurgling/archery/**`) cover message
  classification, one correction per armed test, refusal without a valid target or relation, risk
  acknowledgement, aborts, cancellation, session end, per-instance isolation, and that no verdict
  reports re-aim success without the player's observation.
- **Not run live.** No in-game test has been done; the diagnostic window, the Shoot detection and the
  server's response are all unverified.
