# Development workflow

**Status: current — describes how to work in this repo today.**

This is a maintenance guide for a small passion project, not a corporate process manual. It exists so
work doesn't get lost or tangled across sessions, not to add ceremony.

## One feature at a time

- Work one feature branch at a time. Don't start a second unrelated feature on top of an uncommitted
  first one — that's how a session ends up with mixed, hard-to-untangle working-tree state (see
  `docs/mixed-wip-recovery.md` for what that costs to unwind after the fact).
- **Commit before beginning an unrelated feature.** A real commit, not "I'll remember what's mine" —
  once two unrelated changes are both uncommitted in the same tree, telling them apart later requires
  guesswork. This is the same discipline `docs/fork-sync-guide.md` calls out as what kept the Combat
  Reactor's 38-commit upstream sync easy.
- Keep incomplete features on their own branch/worktree rather than stacking them behind whatever
  you're currently doing. Half-finished work blocks a clean commit of the work that *is* finished.

## The safety-snapshot pattern — recovery only, not routine

A dedicated backup branch/commit (e.g. `backup/mixed-wip-before-upstream-2026-08-07`) is for one
situation: you've already ended up with mixed, uncommitted, multi-feature work in a tree and need to
do something risky to it (an upstream merge, a reset) without losing any of it. Snapshot first, then
proceed, then reconstruct the preserved work feature-by-feature afterward — see
`docs/mixed-wip-recovery.md` for the live example and its recovery ledger.

This is **not** a normal-development habit. If you're following "one feature, committed before the
next" above, you should rarely need it. Reach for a real commit on a real branch first; reach for a
throwaway safety snapshot only when recovering from already-mixed state.

## Verification appropriate to the change

Match the check to the risk, not to a fixed checklist:

- Compiles: `ant jar` (fast inner loop) or full `ant`.
- Has automated coverage: `ant test` (JUnit Platform console launcher over `test/**` — see root
  `CLAUDE.md` for the target chain).
- Touches in-client behavior: launch and exercise the actual feature touched, not just "does it
  build." A passing build proves the code compiles, nothing about whether the feature works.
- Distinguish, in whatever you write about the change, between "I read the code and it looks right,"
  "it compiled/passed `ant test`," and "I ran it live and watched it work." Combat-area docs
  (`docs/combat/verification.md`) enforce this distinction formally; do the same informally
  everywhere else — don't let a code-read upgrade itself into a tested claim.

## Patch-and-report review before an important commit

Before committing something you'd be annoyed to have to redo — a multi-file change, an upstream
merge, anything touching `src/haven/**` — generate the diff, review it yourself (or have it reviewed)
before committing, not after. Catching a mistake in a patch costs a re-edit; catching it after commit
costs a revert or a second corrective commit.

## Working with multiple worktrees

This repo is routinely checked out as more than one worktree at once (`git worktree list` shows
what's currently active). Each worktree has its own `build/`, `bin/`, and branch checkout. Two
consequences that repeatedly matter:

- **A build in one worktree does not update another worktree's `bin/hafen.jar`.** Before testing a
  change in-client, confirm which worktree's `bin/` you're about to launch actually contains the
  build you just made — `ant run` builds and launches from the current worktree only.
- Before running anything that could discard uncommitted work in a worktree you're not actively
  driving, check its `git status` first. A worktree that looks idle may be mid-recovery (see
  `docs/mixed-wip-recovery.md`) or holding another in-progress feature.

## Low-conflict upstream practice

Two independent upstream relationships exist, both merge-only (never rebase, never cherry-pick —
rebasing rewrites hashes, so the next sync re-conflicts on already-integrated commits):

- **Official Hafen source** (`dolda2000/hafen-client`, no local remote configured by default — add
  one ad hoc, see root `CLAUDE.md`) → `src/haven/**`. Full procedure and per-file conflict strategy:
  `docs/hafen-integration-guide.md`. This is the trickier of the two — the dangerous cases aren't git
  conflicts, they're upstream changes that silently break a parallel nurgling reimplementation with no
  conflict at all. After any hafen merge, grep for nurgling's parallel implementation of whatever
  subsystem changed, not just the diff.
- **Nurgling2 upstream** (`upstream` remote, `aleksandrsvoboda/nurgling2`) → the rest of the tree.
  Procedure: `docs/fork-sync-guide.md`. The same guide identifies the recurring conflict-prone shared
  files (`NConfig.java`, `NGItem.java`, `BotRegistry.java`, `messages*.properties`) — keep additions
  to those small and localized rather than reformatted, so they merge clean.
- A uniquely named new file usually avoids *textual* merge conflicts across either boundary, since
  there's no shared file for git to diff line-by-line. It is not immune to conflict outright: two
  branches can still add a same-named file (add/add), or a new file can integrate poorly with
  something upstream changed in parallel (a semantic/integration conflict with no textual collision at
  all). Preferring a new file over editing a shared one still reduces merge pain in the common case —
  just don't treat "new file" as a guarantee.

## `src/nurgling/**` vs `src/haven/**` — where to implement

- Prefer adding or overriding behavior in `src/nurgling/**` over changing `src/haven/**` — it's
  conflict-isolated from both upstreams (above) and is where this project's own logic belongs.
- **Don't assume an existing `src/nurgling/**` implementation is already correct** just because it's
  there. Verify it against current behavior/code before building on it — this repo has working
  history of bots shipping with real, previously-undetected bugs in code that had been present and
  unquestioned for a while (see `docs/lp-assistant-bot.md`'s round-by-round fixes for concrete
  examples).
- Before modifying `src/haven/**` or a widely-shared `src/nurgling/**` core file, look for an
  extension or replacement point first (an `N`-prefixed sibling widget, a hook, a factory) and check
  every caller of what you're about to change. `docs/nurgling-modified-files.md` lists which `haven`
  files are already touched and why — **being on that list is not proof a further change there is
  safe.** Read its documented conflict strategy for that specific file, check its current callers, and
  check how much upstream churn that file has seen recently, before treating it as low-risk.

## Trace behavior end-to-end, not just method names

When following a protocol/message-driven interaction (anything going through `wdgmsg`), trace the
complete argument list and the actual server/client state change it produces — not just which method
or message name is involved. Two calls with the same message name can carry different argument shapes
and mean different things (see `docs/inventory-grid-system.md`'s drop-message reference for a worked
example: a `"drop"` sent to a container vs. to one of its children are the same message name with
different effects). Matching a name is not the same as confirming the behavior.

## Prefer bounded, event-driven waits over polling

When code needs to wait for game state to change, prefer a bounded wait keyed to a real signal (a
field changing, a message arriving) over a fixed sleep or unbounded poll loop. Keep diagnostics
opt-in and scoped (a debug flag, a file log instead of permanent chat spam) rather than an always-on
cost paid by every run. This isn't a hard rule with one implementation — see
`docs/inventory-grid-system.md`'s notes on `WaitNoItems`'s bounded-timeout constructor and
`NInventory.dropOn()`'s tick-count-vs-wall-clock gotcha for two concrete cases where getting this
wrong caused a real bug.

## Documentation ownership

One canonical document per technical subject. `CLAUDE.md` and `.claude/rules/*.md` are routers only —
they point at the owning document and state safety rules needed before opening it; they never
duplicate its mechanics. If you find the same technical fact stated in two places, that's a defect:
fix it by removing the duplicate, not by trying to keep both in sync. `docs/README.md` is the index of
which document owns which subject.
