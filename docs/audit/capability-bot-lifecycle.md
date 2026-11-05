# Capability registry: bot lifecycle & state machines — session 1

**Kind:** current (framework contracts) / recovery-in-progress (coverage). First pass on the
capability area picked to start Phase 3 of the modernization audit (see
[`README.md`](README.md)'s status). This covers the **framework contracts** every bot is built on,
read in full and verified, plus a handful of **quantified, grep-level structural facts** about how
consistently bots actually use those contracts. It does **not** cover per-bot behavior for the
~208 registered bots — that's explicitly out of scope for this session; see "Not covered" below.

## Framework contracts (read in full, verified)

- **[`Action`](../../src/nurgling/actions/Action.java)** — the entire bot contract:
  `Results run(NGameUI gui) throws InterruptedException`, plus a default `getSupp()` returning an
  empty list (sub-action composition hook). **Nothing else.** No retry, backoff, timeout, or
  state-machine support at this level — every bot hand-rolls its own loop/retry/failure logic
  inside `run()`. This is a design fact, not a bug, but it's the reason behavior is inconsistent
  bot-to-bot (see below).
- **[`Results`](../../src/nurgling/actions/Results.java)** — three static factories:
  - `SUCCESS()` → `isSuccess = true`, no message.
  - `ERROR(msg)` → `isSuccess = false`, and **unconditionally calls
    `NUtils.getGameUI().error(msg)`** when `msg != null` (line 23) to surface the error in the UI.
  - `FAIL()` → `isSuccess = false`, no message. **Verified: behaviorally identical to
    `ERROR(null)`** (same constructor path, same resulting state) — a redundant pair, not a bug,
    but worth collapsing if this file is next touched.
  - **Plausible, not proven:** `ERROR(msg)`'s unconditional `NUtils.getGameUI().error(msg)` call is
    exactly the dereference pattern Qodana's `DataFlowIssue` check flagged **344 times** across the
    bot codebase (`docs/audit/qodana-findings-triage-3a35b8b8a.md` §2) as the single most common
    nullable dereference. If a bot calls `Results.ERROR(...)` at a moment `getGameUI()` can return
    null (e.g. mid-teardown), reporting the failure would itself NPE and swallow the original error
    behind a crash instead of showing it. **Not verified against a concrete repro** — flagging the
    hypothesis, not claiming it fires in practice.
- **[`NTask`](../../src/nurgling/tasks/NTask.java)** — the bounded-wait predicate base class every
  "wait for X" task extends. `baseCheck()` enforces a `maxCounter` (default 200) poll limit —
  **unless `infinite` is true, which it is by default** (`protected boolean infinite = true;`,
  line 22). A subclass must explicitly set `infinite = false` to get a bounded wait at all.
  **Quantified:** of 142 files under `src/nurgling/tasks/`, only **5** (`WaitFreeHand`,
  `ISRemoved`, `FepStateSettled`, `DropOn`, `AnimalIsDead`) set `infinite = false`. The other 137
  (~96%) wait unboundedly by default unless they override `maxCounter`/`infinite` some other way
  not checked here. **Not verified per-task** whether each of those 137 actually relies on some
  other exit path (e.g. `InterruptedException` from an outer timeout) — this is a structural fact
  about the default, not a claim that 137 tasks hang in practice.
- **[`Scenario`](../../src/nurgling/scenarios/Scenario.java) /
  [`BotStep`](../../src/nurgling/scenarios/BotStep.java)** — chained-bot sequences are pure data:
  an ordered list of `BotStep` (just a bot id string + a settings map), JSON-serializable. **No
  retry/failure-propagation policy exists at this data-model level.** What happens when one step's
  `Action.run()` returns `Results.ERROR(...)` or `FAIL()` — does the scenario stop, skip, or retry
  the step? — is decided by whatever executes a `Scenario` (presumably `ScenarioManager`).
  **Not read in this session — open question for session 2, not answered here.**
- **[`BotRegistry`](../../src/nurgling/actions/bots/registry/BotRegistry.java)** — a static list of
  **208** `BotDescriptor` entries (`bots.add(...)` call count, verified), each mapping a string id
  to a `BotDescriptor.BotType`, display name/description, flags, the `Action` class, and a command
  alias. New bots register here by convention (per `CLAUDE.md`) — not classpath-scanned.

## Quantified structural pattern: ad-hoc `while(true)` retry loops

Grep across `src/nurgling/actions/bots/**/*.java` (276 files) for `while\s*\(\s*true\s*\)`:
**46 files (~17%) contain at least one.** None of `src/nurgling/tasks/*.java`'s 142 files do (tasks
are single `check()` predicates, not loops — consistent with the `NTask` contract above).

This confirms the pattern behind the `BoughBee.java` finding in
`qodana-findings-triage-3a35b8b8a.md` (§1.2 — a `while(true)` wrapper whose every internal path
`return`s, so it never actually loops) **is not a one-off** — it's a common idiom across roughly a
sixth of bot implementations. **Only `BoughBee.java` was individually read and confirmed to have
the "wrapper never loops" defect.** The other 45 files were located by grep only, not opened — they
may loop correctly, may share the same defect, or may be legitimate infinite-poll loops exited via
`InterruptedException`/an internal `return`/`break` on a real condition. Characterizing all 46 is
session 2+ work, not done here. Listing them for whoever picks this up next:

```
# re-run to get the current list (grep, not re-embedded here to avoid staleness):
rg -l 'while\s*\(\s*true\s*\)' src/nurgling/actions/bots
```

## Not covered in this session

- Per-bot behavior review for any of the 208 registered bots beyond `BoughBee` (already covered in
  the Qodana triage) and the grep-level `while(true)` count above.
- `ScenarioManager` — how chained-bot failure actually propagates between steps.
- `BotDescriptor`'s field meanings (type/flags) beyond what's visible in the registry listing.
- Whether the 137 `NTask` subclasses with `infinite = true` actually rely on it safely (e.g. all
  bot-level timeouts/interrupts) or represent latent hang risk — needs tracing actual call sites,
  not just the default.
- Headless (`-bots` CLI) lifecycle differences, if any — not looked at.

## Suggested session 2 scope

Pick one: (a) read `ScenarioManager` to close the failure-propagation question above, or
(b) sample 5–10 of the 46 `while(true)` bot files to see how common the "never actually loops"
defect is beyond `BoughBee`. Either is a bounded, verifiable next step — don't attempt both plus
the remaining 200 bots in one pass.
