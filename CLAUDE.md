# CLAUDE.md

Guidance for Claude Code working in this repository. Keep this file a **router**, not a manual —
technical detail belongs in the canonical docs it points to.

## Start here

1. Verify where you are before doing anything stateful: repo root (`git rev-parse --show-toplevel`),
   current branch, `HEAD`, `git status --short`, and — if you're about to launch/test the client —
   which worktree's `bin/hafen.jar` you're actually running. This repo runs multiple worktrees
   side-by-side (see `docs/development-workflow.md`); assuming you're in the one you think you're in
   is a recurring source of wasted work.
2. Read **[`docs/README.md`](docs/README.md)** — the routing index for every tracked doc. It tells
   you which canonical document owns which subject and whether that document describes current
   implementation, approved-but-unimplemented behavior, a proposal, or recovery/backlog material.
   Load only the specific docs relevant to your task from there; don't preload the whole tree.

## Authority model

- **Current source, tests, logs, and runtime evidence** are authoritative for what the software
  *currently does*. When you need to know actual behavior, read the code (or run it) — don't take a
  doc's word for it.
- **Canonical tracked docs** are authoritative for *intended* architecture, approved requirements,
  recorded decisions, known limitations, and verification status — each according to its own declared
  kind/status (see `docs/README.md`'s kind tags: current/approved/historical/proposal/recovery).
  A doc marked `proposal` or `recovery` is not describing current behavior even if it reads like it.
- **Never let stale documentation override contradictory current source or runtime evidence.** If a
  doc and the code disagree, the code wins for "what does it do"; treat the mismatch as a documentation
  defect to investigate and fix in the owning document, not as license to trust the doc anyway.
- Keep `CLAUDE.md` and `.claude/rules/*.md` **routers**, not duplicated technical manuals — don't
  restate what a canonical doc already says here or in a rule file; point at it instead.

## What this is

Nurgling2 (a.k.a. Nurgling II) is a **Git fork of Nurgling2** for the MMO *Haven & Hearth*. Its source
architecture layers upstream-derived **Hafen** client code (`src/haven/**`, from
`dolda2000/hafen-client`, periodically merged in) under `src/nurgling/**` and a few sibling packages
(`mapv4`, `monitoring`, `lang`) that add village-automation, botting, and logistics features on top.
`src/com/**` and `src/dolda/**` are bundled third-party libs (jogg/jcraft audio, dolda
jglob/coe/xiphutil) — treat as vendored, not project code.

### Two-layer structure: `haven` (client core) vs `nurgling` (automation layer)

- `src/haven/**` — the base game client: rendering (`render/`), networking (`Session.java`), resource
  loading (`Resource.java`), core UI widgets (`Widget.java`, `GameUI.java`, `MapView.java`), and
  server-distributed resource code (`res/`). Upstream-derived; a substantial, changing subset of these
  files carry nurgling-specific modifications — see `docs/nurgling-modified-files.md` for the current
  count, full list, and per-file conflict strategy before touching any of them. Don't hard-code a
  count here; it changes after every hafen integration.
- `src/nurgling/**` — everything automation/UI-extension related: `actions/bots/` (bot
  implementations, registered in `actions/bots/registry/BotRegistry.java` — new bots must be
  registered there, not classpath-scanned), `actions/` (composable primitives, pathfinding), `tasks/`
  (blocking-wait predicates), `db/` (pluggable SQLite/Postgres persistence via `NCore.databaseManager`),
  `headless/` (no-GPU run mode for `-bots` CLI), `scenarios/` (chained bot sequences), `sessions/`
  (multi-session support), `conf/` (typed config/registry objects), `widgets/` (`N`-prefixed sibling
  widgets — see the note below), `plugins/`, `combat/` (see `docs/combat/README.md`).
- `src/mapv4/**` — client-side mapping/minimap-upload subsystem.
- `src/monitoring/**` — cross-cutting item/container watchers hooked into `haven.Inventory`.
- `src/lang/**` — `messages.properties` / `messages_ru.properties` i18n, read via `L10n.get(...)`.

**If you touch a `haven` widget that has an `N`-prefixed sibling, check whether the sibling needs a
matching change** — nothing enforces this and the compiler won't catch it (e.g. `NZergwnd` silently
going stale against `GameUI.Zergwnd`). See `docs/development-workflow.md` for the general practice
this implies for any `src/haven/**` change.

`nurgling.NCore` (extends `haven.Widget`) is the top-level nurgling controller instance living inside
the game UI: owns the static `databaseManager`, `ScenarioManager`, `EquipmentPresetManager`,
`PlanningLayerManager`, and current bot/task/action state. Most bots and actions reach shared state
through this instance rather than through scattered globals.

## Remotes and upstreams (verified)

Two remotes are configured in this working copy:

- `origin` → `https://github.com/detoxboss/nurgling2-imbecil.git` — this fork.
- `upstream` → `https://github.com/aleksandrsvoboda/nurgling2.git` — the Nurgling2 project this fork
  tracks. Sync practice: `docs/fork-sync-guide.md`.

A third source, the **official Hafen client** (`https://github.com/dolda2000/hafen-client`), is
where `src/haven/**` originates. **No local remote for it is currently configured** — add one ad hoc
(`git remote add hafen https://github.com/dolda2000/hafen-client`) when doing a hafen integration.
Full procedure, conflict strategy, and a dated log of past integrations: `docs/hafen-integration-guide.md`.
Do not cherry-pick or rebase hafen commits — always `git merge`; see that guide for why.

## Build / test / run

Requires JDK (CI uses 17; main client compiles at `source`/`target` 1.8) and Apache Ant. Verified
against the current `build.xml`:

```
ant jar      # compile + build build/hafen.jar (fastest inner loop; needs lib/ext already populated)
ant test     # compile test/** and run it via the JUnit Platform console launcher
ant clean    # wipe build/, lib/ext/, bin/ — required occasionally after upstream integrations
ant          # (default "deftgt") full build: jars + opt/panama (if JDK>=22) + bin/ (runnable client dir)
ant run      # build bin/ then launch the client
ant bin      # produce a runnable bin/hafen.jar + data files, connects to the official server
```

- `ant test` exists and works — targets `test-deps` (downloads/verifies a pinned JUnit Platform
  console-standalone jar by SHA-256), `test-compile` (compiles `test/**` against `build/classes`), then
  runs everything under `build/test-classes` via `org.junit.platform.console.ConsoleLauncher`.
- First build downloads JOGL/LWJGL/steamworks jars from the H&H website into `lib/ext` — needs network
  once, then cached (`ant clean` deletes `lib/ext` entirely, forcing re-download).
- `opt/panama/**` only compiles when the active JDK is >= 22; optional, doesn't gate the main build.
- CI (`.github/workflows/build-pr.yml`) runs `ant jar` and checks `build/hafen.jar` exists.
- `tools/*.py` are standalone dev scripts, not part of the Java build.

**Before testing anything in-client**, verify you launched the artifact you think you launched — see
"Working with multiple worktrees" in `docs/development-workflow.md`. A build in one worktree does not
update another worktree's `bin/`.

## Practical feature workflow

One feature branch at a time; commit before starting an unrelated one; verify build/test/runtime
appropriate to the change; patch-and-report review before an important commit. Full guide, including
when (and when not) to use the mixed-WIP safety-snapshot pattern:
**[`docs/development-workflow.md`](docs/development-workflow.md)**.

## Routing to canonical work areas

| Working on… | Read first |
|---|---|
| Inventory/stack/cursor/equipment/drop/study/eat grid code | `.claude/rules/inventory-management.md` → `docs/inventory-grid-system.md` |
| LP Assistant bot, LP discovery markers | `.claude/rules/lp-assistant.md` → `docs/lp-assistant-bot.md` |
| Combat reactor (`src/nurgling/combat/**`, `Fightsess`/`NFightsess`) | `.claude/rules/combat-automation.md` → `docs/combat/README.md` |
| Land-based long-distance navigation research, cliff handling, rolling-horizon overland exploration, or related `DynamicPf`/`PathFinder`/`NPFMap`/`ChunkNav` work toward that goal | `.claude/rules/land-navigation.md` → `docs/land-navigation-research.md` |
| Hafen/upstream integration | `docs/hafen-integration-guide.md`, `docs/fork-sync-guide.md`, `docs/nurgling-modified-files.md` |
| Anything else — check the index first | `docs/README.md` |
| Recovering work referenced in the mixed-WIP snapshot | `docs/mixed-wip-recovery.md` |

`.claude/rules/*.md` files are path-scoped routers (see their `paths:` frontmatter) — they point you
at the right canonical doc and state only the safety rules needed before you open it. They do not
duplicate mechanics.
